"""Read-only Codex app-server probe and live token notification projection."""
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

from core import *
from rollout import snapshot_rollout_metadata

def project_live_token_notification(value: Any) -> Optional[dict[str, Any]]:
    if not isinstance(value, Mapping) or value.get("method") != "thread/tokenUsage/updated":
        return None
    params = value.get("params")
    if not isinstance(params, Mapping):
        return None
    raw_thread = params.get("threadId")
    raw_turn = params.get("turnId")
    token_usage = params.get("tokenUsage")
    if not isinstance(raw_thread, str) or not isinstance(token_usage, Mapping):
        return None
    last = TokenBreakdown.from_mapping(token_usage.get("last"))
    total = TokenBreakdown.from_mapping(token_usage.get("total"))
    return {
        "threadHash": sha256_display(raw_thread, 12),
        "turnHash": sha256_display(raw_turn, 12) if isinstance(raw_turn, str) else None,
        "last": None if last is None else last.to_dict(),
        "total": None if total is None else total.to_dict(),
        "observedAt": utc_now(),
        "occurredAt": None,
        "model": None,
        "modelSource": None,
        "source": "app_server_live",
    }


def _project_bucket(bucket: Any, *, position: Optional[str] = None, limit_id: Optional[str] = None) -> Optional[dict[str, Any]]:
    if not isinstance(bucket, Mapping):
        return None
    duration = nullable_int(bucket.get("windowDurationMins"))
    projected = {
        "position": position,
        "limitIdHash": sha256_display(limit_id, 12) if limit_id else None,
        "usedPercent": nullable_number(bucket.get("usedPercent")),
        "windowDurationMins": duration,
        "resetsAt": nullable_int(bucket.get("resetsAt")),
    }
    if all(projected[k] is None for k in ("usedPercent", "windowDurationMins", "resetsAt")):
        return None
    return projected


def project_rate_limits(result: Any) -> dict[str, Any]:
    if not isinstance(result, Mapping):
        return {"buckets": [], "rateLimitReachedType": None, "resetCredits": None}
    buckets: list[dict[str, Any]] = []
    limits = result.get("rateLimits")
    if isinstance(limits, Mapping):
        for position in ("primary", "secondary"):
            item = _project_bucket(limits.get(position), position=position)
            if item:
                buckets.append(item)
    by_id = result.get("rateLimitsByLimitId")
    if isinstance(by_id, Mapping):
        for key, value in by_id.items():
            item = _project_bucket(value, limit_id=str(key))
            if item:
                buckets.append(item)
    credits = result.get("rateLimitResetCredits")
    reset_projection = None
    if isinstance(credits, Mapping):
        details = credits.get("credits")
        reset_projection = {
            "availableCount": nullable_int(credits.get("availableCount")),
            "detailsAvailable": isinstance(details, list),
            "detailCount": len(details) if isinstance(details, list) else None,
        }
    reached = safe_enum_string(result.get("rateLimitReachedType"))
    return {
        "buckets": buckets,
        "rateLimitReachedType": reached,
        "resetCredits": reset_projection,
    }


def project_account_usage(result: Any) -> dict[str, Any]:
    if not isinstance(result, Mapping):
        return {"summary": {}, "dailyUsageBuckets": []}
    summary_raw = result.get("summary")
    summary: dict[str, Optional[int]] = {}
    if isinstance(summary_raw, Mapping):
        for src, dst in (
            ("lifetimeTokens", "lifetimeTokens"),
            ("peakDailyTokens", "peakDailyTokens"),
            ("last30DaysTokens", "last30DaysTokens"),
            ("last7DaysTokens", "last7DaysTokens"),
        ):
            if src in summary_raw:
                summary[dst] = nullable_int(summary_raw.get(src))
    buckets: list[dict[str, Any]] = []
    raw_buckets = result.get("dailyUsageBuckets")
    if isinstance(raw_buckets, list):
        for raw in raw_buckets:
            if not isinstance(raw, Mapping):
                continue
            start = safe_date(raw.get("startDate"))
            tokens = nullable_int(raw.get("tokens"))
            if start is not None or tokens is not None:
                buckets.append({"startDate": start, "tokens": tokens})
    return {"summary": summary, "dailyUsageBuckets": buckets}


class AppServerClient:
    def __init__(self, codex_bin: str, timeout: float = 12.0):
        self.codex_bin = codex_bin
        self.timeout = timeout
        self.proc: Optional[subprocess.Popen[str]] = None
        self.incoming: "queue.Queue[Any]" = queue.Queue()
        self.reader: Optional[threading.Thread] = None
        self.next_id = 1
        self.outbound_methods: list[str] = []
        self.notifications: list[dict[str, Any]] = []

    def __enter__(self) -> "AppServerClient":
        self.proc = subprocess.Popen(
            [self.codex_bin, "app-server"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        if self.proc.stdout is None or self.proc.stdin is None:
            raise ProbeError("Unable to open Codex app-server stdio")
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if self.proc is None:
            return
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.terminate()
            self.proc.wait(timeout=2)
        except Exception:
            try:
                self.proc.kill()
            except Exception:
                pass

    def _read_loop(self) -> None:
        assert self.proc is not None and self.proc.stdout is not None
        try:
            for line in self.proc.stdout:
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                self.incoming.put(value)
        finally:
            self.incoming.put(EOFError("Codex app-server stdout closed"))

    def _write(self, message: Mapping[str, Any]) -> None:
        if self.proc is None or self.proc.stdin is None:
            raise ProbeError("app-server not started")
        method = message.get("method")
        if not isinstance(method, str):
            raise ProbeError("Outbound message missing method")
        if method in FORBIDDEN_RPC_METHOD_PREFIXES or any(method.startswith(x) for x in FORBIDDEN_RPC_METHOD_PREFIXES):
            raise ProbeError(f"Forbidden RPC blocked: {method}")
        if method not in READ_ONLY_RPC_METHODS and method != "initialized":
            raise ProbeError(f"Non-allow-listed RPC blocked: {method}")
        self.outbound_methods.append(method)
        self.proc.stdin.write(canonical_json(dict(message)) + "\n")
        self.proc.stdin.flush()

    def initialize(self) -> Any:
        result = self.request(
            "initialize",
            {
                "clientInfo": {
                    "name": "electronic-muyu-c1-live-probe",
                    "title": "Electronic Muyu C1 Live Probe",
                    "version": PROBE_VERSION,
                },
                "capabilities": {"experimentalApi": False},
            },
        )
        self._write({"method": "initialized"})
        return result

    def request(self, method: str, params: Optional[Mapping[str, Any]] = None) -> Any:
        if method not in READ_ONLY_RPC_METHODS:
            raise ProbeError(f"RPC not allow-listed: {method}")
        request_id = str(self.next_id)
        self.next_id += 1
        message: dict[str, Any] = {"id": request_id, "method": method}
        if params is not None:
            message["params"] = dict(params)
        self._write(message)
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            try:
                incoming = self.incoming.get(timeout=max(0.05, deadline - time.monotonic()))
            except queue.Empty:
                break
            if isinstance(incoming, BaseException):
                raise ProbeError("Codex app-server terminated before response")
            if isinstance(incoming, Mapping) and str(incoming.get("id")) == request_id:
                if "error" in incoming:
                    err = incoming.get("error")
                    if isinstance(err, Mapping):
                        code = err.get("code")
                    else:
                        code = None
                    raise ProbeError(f"{method} failed: code={code}")
                return incoming.get("result")
            self._consume_notification(incoming)
        raise ProbeError(f"Timed out waiting for {method}")

    def _consume_notification(self, incoming: Any) -> None:
        if not isinstance(incoming, Mapping):
            return
        method = incoming.get("method")
        if method == "account/rateLimits/updated":
            params = incoming.get("params")
            self.notifications.append(
                {"method": method, "observedAt": utc_now(), "quota": project_rate_limits(params)}
            )
        elif method == "thread/tokenUsage/updated":
            projected = project_live_token_notification(incoming)
            if projected:
                self.notifications.append({"method": method, **projected})

    def observe(self, seconds: float) -> None:
        deadline = time.monotonic() + max(0.0, seconds)
        while time.monotonic() < deadline:
            try:
                incoming = self.incoming.get(timeout=min(0.2, deadline - time.monotonic()))
            except queue.Empty:
                continue
            if isinstance(incoming, BaseException):
                return
            self._consume_notification(incoming)


def codex_version(codex_bin: str) -> Optional[str]:
    try:
        proc = subprocess.run(
            [codex_bin, "--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=6,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    text = (proc.stdout or "").strip().splitlines()[0] if (proc.stdout or "").strip() else ""
    if not text or not CODEX_VERSION_RE.fullmatch(text):
        return None
    return text


def run_quota_probe(codex_bin: str, roots: list[Path], observe_seconds: float) -> dict[str, Any]:
    before = snapshot_rollout_metadata(roots)
    result: dict[str, Any] = {
        "rateLimitRead": "FAIL",
        "accountUsage": "FAIL",
        "rateLimitUpdated": "NOT OBSERVED",
        "quota": None,
        "accountUsageProjection": None,
        "monitoringSideEffectEvidence": "INCONCLUSIVE",
        "outboundMethods": [],
        "errorClass": None,
        "liveTokenNotifications": [],
    }
    try:
        with AppServerClient(codex_bin) as client:
            client.initialize()
            raw_quota = client.request("account/rateLimits/read", {})
            result["quota"] = project_rate_limits(raw_quota)
            result["rateLimitRead"] = "PASS"
            raw_usage = client.request("account/usage/read", {})
            result["accountUsageProjection"] = project_account_usage(raw_usage)
            result["accountUsage"] = "PASS"
            client.observe(observe_seconds)
            if any(x.get("method") == "account/rateLimits/updated" for x in client.notifications):
                result["rateLimitUpdated"] = "PASS"
            result["liveTokenNotifications"] = [
                {k: v for k, v in x.items() if k != "method"}
                for x in client.notifications
                if x.get("method") == "thread/tokenUsage/updated"
            ]
            result["outboundMethods"] = list(client.outbound_methods)
    except ProbeError as exc:
        result["errorClass"] = str(exc)[:240]

    after = snapshot_rollout_metadata(roots)
    result["monitoringSideEffectEvidence"] = "PASS" if before == after else "CONCURRENT ROLLOUT CHANGE OBSERVED"
    allowed_runtime = all(m in READ_ONLY_RPC_METHODS or m == "initialized" for m in result["outboundMethods"])
    result["modelFreeMonitoring"] = (
        "PASS"
        if result["rateLimitRead"] == "PASS"
        and result["accountUsage"] == "PASS"
        and result["monitoringSideEffectEvidence"] == "PASS"
        and allowed_runtime
        else "PARTIAL"
    )
    return result

