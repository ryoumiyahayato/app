"""Core token-ledger and safe projection primitives for the C1-LIVE probe."""
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any, BinaryIO, Iterable, Iterator, Mapping, Optional

PROBE_VERSION = "0.1.0"
CHECKPOINT_VERSION = 1
TOKEN_FIELDS = (
    "inputTokens",
    "cachedInputTokens",
    "cacheWriteInputTokens",
    "outputTokens",
    "reasoningOutputTokens",
    "totalTokens",
)
TOKEN_ALIASES = {
    "inputTokens": ("inputTokens", "input_tokens"),
    "cachedInputTokens": ("cachedInputTokens", "cached_input_tokens"),
    "cacheWriteInputTokens": ("cacheWriteInputTokens", "cache_write_input_tokens"),
    "outputTokens": ("outputTokens", "output_tokens"),
    "reasoningOutputTokens": ("reasoningOutputTokens", "reasoning_output_tokens"),
    "totalTokens": ("totalTokens", "total_tokens"),
}
READ_ONLY_RPC_METHODS = {
    "initialize",
    "account/rateLimits/read",
    "account/usage/read",
}
FORBIDDEN_RPC_METHOD_PREFIXES = (
    "thread/start",
    "turn/start",
    "account/rateLimitResetCredit/consume",
)
SENSITIVE_KEY_RE = re.compile(
    r"(?i)(prompt|assistant.?response|source.?code|tool.?output|auth|access.?token|"
    r"refresh.?token|cookie|api.?key|email|filesystem.?path|full.?path|cwd)"
)
EMAIL_RE = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I)
ABS_WIN_PATH_RE = re.compile(r"(?i)\b[A-Z]:\\(?:[^\r\n]+)")
ABS_UNIX_PATH_RE = re.compile(r"(?<![A-Za-z0-9_.-])/(?:home|Users|root|mnt|var|tmp|opt)/[^\s\"']+")

SAFE_ENUM_RE = re.compile(r"^[A-Za-z0-9._:+-]{1,96}$")
SAFE_MODEL_RE = re.compile(r"^[A-Za-z0-9._:+-]+(?:/[A-Za-z0-9._:+-]+){0,2}$")
RFC3339_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CODEX_VERSION_RE = re.compile(r"^[A-Za-z0-9._-]+(?: [A-Za-z0-9._+-]+){1,3}$")


class ProbeError(RuntimeError):
    pass


class ZstdUnavailable(ProbeError):
    pass


class ZstdCorrupt(ProbeError):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_display(value: str, length: int = 12) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()[:length]


def sha256_bytes(value: bytes, length: int = 16) -> str:
    return hashlib.sha256(value).hexdigest()[:length]


def nullable_int(value: Any) -> Optional[int]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value >= 0 else None
    if isinstance(value, float) and value.is_integer() and value >= 0:
        return int(value)
    return None


def nullable_number(value: Any) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


def nullable_string(value: Any, max_len: int = 128) -> Optional[str]:
    if not isinstance(value, str):
        return None
    value = value.strip()
    if not value or len(value) > max_len:
        return None
    return value


def safe_enum_string(value: Any) -> Optional[str]:
    value = nullable_string(value, 96)
    if value is None or not SAFE_ENUM_RE.fullmatch(value):
        return None
    return value


def safe_model_string(value: Any) -> Optional[str]:
    value = nullable_string(value, 96)
    if value is None or ".." in value or not SAFE_MODEL_RE.fullmatch(value):
        return None
    return value


def safe_timestamp(value: Any) -> Optional[str]:
    value = nullable_string(value, 64)
    if value is None or not RFC3339_RE.fullmatch(value):
        return None
    return value


def safe_date(value: Any) -> Optional[str]:
    value = nullable_string(value, 16)
    if value is None or not DATE_RE.fullmatch(value):
        return None
    return value


def get_alias(mapping: Mapping[str, Any], aliases: Iterable[str]) -> Any:
    for key in aliases:
        if key in mapping:
            return mapping[key]
    return None


@dataclasses.dataclass(frozen=True)
class TokenBreakdown:
    inputTokens: Optional[int] = None
    cachedInputTokens: Optional[int] = None
    cacheWriteInputTokens: Optional[int] = None
    outputTokens: Optional[int] = None
    reasoningOutputTokens: Optional[int] = None
    totalTokens: Optional[int] = None

    @classmethod
    def from_mapping(cls, value: Any) -> Optional["TokenBreakdown"]:
        if not isinstance(value, Mapping):
            return None
        kwargs = {
            field: nullable_int(get_alias(value, TOKEN_ALIASES[field])) for field in TOKEN_FIELDS
        }
        if all(v is None for v in kwargs.values()):
            return None
        obj = cls(**kwargs)
        obj.validate()
        return obj

    def validate(self) -> None:
        if (
            self.inputTokens is not None
            and self.cachedInputTokens is not None
            and self.cachedInputTokens > self.inputTokens
        ):
            raise ProbeError("cachedInputTokens exceeds inputTokens")
        if (
            self.outputTokens is not None
            and self.reasoningOutputTokens is not None
            and self.reasoningOutputTokens > self.outputTokens
        ):
            raise ProbeError("reasoningOutputTokens exceeds outputTokens")

    def to_dict(self) -> dict[str, Optional[int]]:
        return {field: getattr(self, field) for field in TOKEN_FIELDS}

    def fingerprint(self) -> str:
        return sha256_display(canonical_json(self.to_dict()), 20)

    def delta_from(self, previous: "TokenBreakdown") -> Optional["TokenBreakdown"]:
        values: dict[str, Optional[int]] = {}
        for field in TOKEN_FIELDS:
            cur = getattr(self, field)
            prev = getattr(previous, field)
            if cur is None or prev is None:
                values[field] = None
            elif cur < prev:
                return None
            else:
                values[field] = cur - prev
        delta = TokenBreakdown(**values)
        delta.validate()
        return delta

    def compatible_with(self, other: "TokenBreakdown") -> bool:
        compared = 0
        for field in TOKEN_FIELDS:
            left = getattr(self, field)
            right = getattr(other, field)
            if left is not None and right is not None:
                compared += 1
                if left != right:
                    return False
        return compared > 0


@dataclasses.dataclass
class LedgerDecision:
    added: Optional[TokenBreakdown]
    semantics: str
    duplicate: bool = False
    lineageDiscontinuity: bool = False
    cumulativeFingerprint: Optional[str] = None
    note: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "added": None if self.added is None else self.added.to_dict(),
            "semantics": self.semantics,
            "duplicate": self.duplicate,
            "lineageDiscontinuity": self.lineageDiscontinuity,
            "cumulativeFingerprint": self.cumulativeFingerprint,
            "note": self.note,
        }


class TokenLedger:
    """Minimal validation ledger.

    The key invariant is that one cumulative fingerprint for one thread lineage can
    never produce a second increment, even after restart or cross-source replay.
    """

    def __init__(self, state: Optional[Mapping[str, Any]] = None):
        self._threads: dict[str, dict[str, Any]] = {}
        if state:
            for thread_hash, raw in state.get("threads", {}).items():
                if not isinstance(raw, Mapping):
                    continue
                last_total = TokenBreakdown.from_mapping(raw.get("lastTotal"))
                seen = {str(x) for x in raw.get("seenCumulative", []) if isinstance(x, str)}
                self._threads[str(thread_hash)] = {
                    "lastTotal": last_total,
                    "seenCumulative": seen,
                    "lineage": int(raw.get("lineage", 0) or 0),
                }

    def export_state(self) -> dict[str, Any]:
        threads: dict[str, Any] = {}
        for key, state in self._threads.items():
            last = state["lastTotal"]
            threads[key] = {
                "lastTotal": None if last is None else last.to_dict(),
                "seenCumulative": sorted(state["seenCumulative"]),
                "lineage": state["lineage"],
            }
        return {"threads": threads}

    def apply(
        self,
        *,
        thread_hash: str,
        last: Optional[TokenBreakdown],
        total: Optional[TokenBreakdown],
    ) -> LedgerDecision:
        state = self._threads.setdefault(
            thread_hash,
            {"lastTotal": None, "seenCumulative": set(), "lineage": 0},
        )

        if total is None:
            return LedgerDecision(
                added=None,
                semantics="unresolved_snapshot",
                note="No cumulative total; probe refuses to infer a unique increment.",
            )

        fp = total.fingerprint()
        if fp in state["seenCumulative"]:
            return LedgerDecision(
                added=TokenBreakdown(totalTokens=0),
                semantics="replay",
                duplicate=True,
                cumulativeFingerprint=fp,
            )

        previous: Optional[TokenBreakdown] = state["lastTotal"]
        state["seenCumulative"].add(fp)

        if previous is None:
            state["lastTotal"] = total
            if last is None:
                return LedgerDecision(
                    added=None,
                    semantics="baseline_snapshot",
                    cumulativeFingerprint=fp,
                    note="First cumulative snapshot without last; established baseline only.",
                )
            return LedgerDecision(
                added=last,
                semantics="incremental_last",
                cumulativeFingerprint=fp,
            )

        inferred = total.delta_from(previous)
        if inferred is None:
            state["lineage"] += 1
            state["lastTotal"] = total
            return LedgerDecision(
                added=None,
                semantics="lineage_discontinuity",
                lineageDiscontinuity=True,
                cumulativeFingerprint=fp,
                note="Cumulative total regressed; no negative token event emitted.",
            )

        state["lastTotal"] = total
        if last is not None:
            if last.compatible_with(inferred):
                return LedgerDecision(
                    added=last,
                    semantics="incremental_last",
                    cumulativeFingerprint=fp,
                )
            return LedgerDecision(
                added=None,
                semantics="inconsistent_last_vs_total",
                cumulativeFingerprint=fp,
                note="last did not reconcile with cumulative delta; probe did not guess.",
            )

        return LedgerDecision(
            added=inferred,
            semantics="inferred_delta",
            cumulativeFingerprint=fp,
        )
