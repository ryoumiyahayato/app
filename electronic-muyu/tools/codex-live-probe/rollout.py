"""Allow-list rollout projection and JSONL/Zstd checkpoint scanning."""
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

@dataclasses.dataclass
class RolloutContext:
    threadHash: Optional[str] = None
    turnHash: Optional[str] = None
    explicitModel: Optional[str] = None
    explicitModelSource: Optional[str] = None


@dataclasses.dataclass
class RolloutProjection:
    sourceIdentity: str
    ordinal: Optional[int]
    persistedAt: Optional[str]
    occurredAt: None
    threadHash: str
    turnHash: Optional[str]
    last: Optional[TokenBreakdown]
    total: Optional[TokenBreakdown]
    model: Optional[str]
    modelSource: Optional[str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "sourceIdentity": self.sourceIdentity,
            "ordinal": self.ordinal,
            "persistedAt": self.persistedAt,
            "occurredAt": None,
            "threadHash": self.threadHash,
            "turnHash": self.turnHash,
            "last": None if self.last is None else self.last.to_dict(),
            "total": None if self.total is None else self.total.to_dict(),
            "model": self.model,
            "modelSource": self.modelSource,
        }


def project_rollout_record(
    value: Any, *, source_identity: str, context: RolloutContext
) -> Optional[RolloutProjection]:
    """Project one parsed rollout record and discard all non-allow-listed content."""
    if not isinstance(value, Mapping):
        return None
    record_type = nullable_string(value.get("type"), 64)
    payload = value.get("payload")
    if not isinstance(payload, Mapping):
        return None

    if record_type == "session_meta":
        raw_id = get_alias(payload, ("id", "thread_id", "threadId"))
        if isinstance(raw_id, str) and raw_id:
            context.threadHash = sha256_display(raw_id, 12)
        return None

    if record_type == "turn_context":
        # Conservative attribution: only retain an explicit model field from the
        # turn-context record. Never substitute the current/default Codex model.
        model = safe_model_string(payload.get("model"))
        if model:
            context.explicitModel = model
            context.explicitModelSource = "rollout.turn_context.model"
        return None

    if record_type != "event_msg":
        return None

    event_type = nullable_string(payload.get("type"), 64)
    if event_type in {"turn_started", "turn_start"}:
        raw_turn = get_alias(payload, ("turn_id", "turnId", "id"))
        if isinstance(raw_turn, str) and raw_turn:
            context.turnHash = sha256_display(raw_turn, 12)
        return None

    if event_type != "token_count":
        return None

    info = payload.get("info")
    if not isinstance(info, Mapping):
        return None
    total = TokenBreakdown.from_mapping(
        get_alias(info, ("total_token_usage", "totalTokenUsage", "total"))
    )
    last = TokenBreakdown.from_mapping(
        get_alias(info, ("last_token_usage", "lastTokenUsage", "last"))
    )
    if total is None and last is None:
        return None

    explicit_event_model = safe_model_string(payload.get("model"))
    if explicit_event_model:
        model = explicit_event_model
        model_source = "rollout.token_count.model"
    else:
        # turn_context is useful evidence but may not prove per-completion routing.
        # We preserve it only as a candidate source and leave model null unless the
        # token_count event itself declares it.
        model = None
        model_source = None

    persisted = safe_timestamp(value.get("timestamp"))
    ordinal = nullable_int(value.get("ordinal"))
    thread_hash = context.threadHash or source_identity
    return RolloutProjection(
        sourceIdentity=source_identity,
        ordinal=ordinal,
        persistedAt=persisted,
        occurredAt=None,
        threadHash=thread_hash,
        turnHash=context.turnHash,
        last=last,
        total=total,
        model=model,
        modelSource=model_source,
    )


def _zstd_reader(path: Path) -> tuple[BinaryIO, Optional[subprocess.Popen[bytes]]]:
    # Python 3.14+ stdlib, when available.
    try:
        import compression.zstd as std_zstd  # type: ignore

        raw = path.open("rb")
        return std_zstd.ZstdFile(raw, mode="rb"), None  # type: ignore[attr-defined]
    except (ImportError, AttributeError):
        pass

    # Widely used PyPI backend.
    try:
        import zstandard as zstd  # type: ignore

        raw = path.open("rb")
        reader = zstd.ZstdDecompressor().stream_reader(raw)
        return reader, None
    except ImportError:
        pass

    # Last resort: an installed zstd executable. Stderr is discarded so a corrupt
    # file cannot echo local path/content into a report.
    executable = shutil.which("zstd")
    if executable:
        proc: subprocess.Popen[bytes] = subprocess.Popen(
            [executable, "-dc", "--", str(path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        if proc.stdout is None:
            proc.kill()
            raise ZstdUnavailable("zstd stdout unavailable")
        return proc.stdout, proc
    raise ZstdUnavailable("No Zstandard backend: Python compression.zstd, zstandard, or zstd CLI")


def iter_decompressed_lines(path: Path) -> Iterator[tuple[bytes, bool]]:
    """Yield (line, newline_terminated). Raw bytes are never retained by callers."""
    proc: Optional[subprocess.Popen[bytes]] = None
    stream: Optional[BinaryIO] = None
    try:
        if path.name.endswith(".jsonl.zst"):
            stream, proc = _zstd_reader(path)
        else:
            stream = path.open("rb")
        while True:
            line = stream.readline()
            if not line:
                break
            yield line, line.endswith(b"\n")
        if proc is not None:
            rc = proc.wait(timeout=5)
            if rc != 0:
                raise ZstdCorrupt("Zstandard decompression failed")
    except (OSError, EOFError) as exc:
        raise ZstdCorrupt("Unable to read compressed rollout") from exc
    finally:
        if stream is not None:
            try:
                stream.close()
            except Exception:
                pass
        if proc is not None and proc.poll() is None:
            proc.kill()


def source_identity(path: Path) -> str:
    # Full path is transiently used only to create an opaque hash.
    try:
        raw = str(path.resolve(strict=False))
    except OSError:
        raw = str(path.absolute())
    return sha256_display(raw, 16)


def file_identity(path: Path) -> str:
    stat = path.stat()
    return sha256_display(f"{stat.st_dev}:{stat.st_ino}", 16)


def discover_rollouts(roots: Iterable[Path]) -> list[Path]:
    found: list[Path] = []
    for root in roots:
        if not root.exists() or not root.is_dir():
            continue
        for base, dirs, files in os.walk(root, followlinks=False):
            dirs[:] = [d for d in dirs if not Path(base, d).is_symlink()]
            for name in files:
                if name.endswith(".jsonl") or name.endswith(".jsonl.zst"):
                    found.append(Path(base, name))
    return sorted(found, key=lambda p: source_identity(p))


def default_rollout_roots() -> list[Path]:
    codex_home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
    return [codex_home / "sessions", codex_home / "archived_sessions"]


def snapshot_rollout_metadata(roots: Iterable[Path]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for path in discover_rollouts(roots):
        try:
            stat = path.stat()
        except OSError:
            continue
        result[source_identity(path)] = {
            "size": stat.st_size,
            "mtimeNs": stat.st_mtime_ns,
            "fileIdentity": file_identity(path),
        }
    return result


def scan_rollouts(
    roots: Iterable[Path],
    *,
    ledger: TokenLedger,
    checkpoint: Optional[Mapping[str, Any]] = None,
) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    """Scan rollout token metadata only.

    Plain JSONL uses a byte-offset checkpoint. Compressed files are replay-scanned;
    cumulative-fingerprint dedup makes replay idempotent.
    """
    checkpoint = checkpoint or {}
    file_state = dict(checkpoint.get("files", {})) if isinstance(checkpoint, Mapping) else {}
    events: list[dict[str, Any]] = []
    stats = {
        "filesSeen": 0,
        "jsonlFiles": 0,
        "zstdFiles": 0,
        "tokenSnapshots": 0,
        "addedEvents": 0,
        "duplicateSnapshots": 0,
        "lineageDiscontinuities": 0,
        "truncatedTailIgnored": 0,
        "malformedLines": 0,
        "corruptCompressedFiles": 0,
        "fileReplacements": 0,
        "fileShrinks": 0,
        "zstdBackendUnavailable": 0,
    }

    next_files: dict[str, Any] = {}
    for path in discover_rollouts(roots):
        stats["filesSeen"] += 1
        sid = source_identity(path)
        is_zstd = path.name.endswith(".jsonl.zst")
        stats["zstdFiles" if is_zstd else "jsonlFiles"] += 1
        try:
            stat = path.stat()
            fid = file_identity(path)
        except OSError:
            continue
        previous = file_state.get(sid, {}) if isinstance(file_state.get(sid), Mapping) else {}
        offset = 0
        if not is_zstd:
            previous_offset = nullable_int(previous.get("offset")) or 0
            if previous and previous.get("fileIdentity") != fid:
                stats["fileReplacements"] += 1
            elif previous_offset > stat.st_size:
                stats["fileShrinks"] += 1
            else:
                offset = previous_offset

        context = RolloutContext()
        # For plain incremental reads, metadata needed for association may be before
        # offset. Re-scan only allow-listed context records up to the offset without
        # applying token events.
        if not is_zstd and offset > 0:
            try:
                with path.open("rb") as warm:
                    consumed = 0
                    while consumed < offset:
                        start = warm.tell()
                        line = warm.readline()
                        if not line:
                            break
                        consumed = warm.tell()
                        if not line.endswith(b"\n"):
                            break
                        try:
                            obj = json.loads(line)
                        except (json.JSONDecodeError, UnicodeDecodeError):
                            continue
                        # Projection updates context; token projection is discarded.
                        project_rollout_record(obj, source_identity=sid, context=context)
                        if consumed >= offset:
                            break
            except OSError:
                offset = 0

        last_complete_offset = offset
        try:
            if is_zstd:
                line_iter = iter_decompressed_lines(path)
                for raw_line, terminated in line_iter:
                    if not terminated:
                        stats["truncatedTailIgnored"] += 1
                        break
                    try:
                        obj = json.loads(raw_line)
                    except (json.JSONDecodeError, UnicodeDecodeError):
                        stats["malformedLines"] += 1
                        continue
                    projection = project_rollout_record(obj, source_identity=sid, context=context)
                    if projection is None:
                        continue
                    stats["tokenSnapshots"] += 1
                    decision = ledger.apply(
                        thread_hash=projection.threadHash,
                        last=projection.last,
                        total=projection.total,
                    )
                    if decision.duplicate:
                        stats["duplicateSnapshots"] += 1
                    if decision.lineageDiscontinuity:
                        stats["lineageDiscontinuities"] += 1
                    if decision.added and (decision.added.totalTokens or 0) > 0:
                        stats["addedEvents"] += 1
                    events.append({**projection.to_dict(), "ledger": decision.to_dict()})
            else:
                with path.open("rb") as fh:
                    fh.seek(offset)
                    while True:
                        line_start = fh.tell()
                        raw_line = fh.readline()
                        if not raw_line:
                            break
                        if not raw_line.endswith(b"\n"):
                            stats["truncatedTailIgnored"] += 1
                            last_complete_offset = line_start
                            break
                        last_complete_offset = fh.tell()
                        try:
                            obj = json.loads(raw_line)
                        except (json.JSONDecodeError, UnicodeDecodeError):
                            stats["malformedLines"] += 1
                            continue
                        projection = project_rollout_record(obj, source_identity=sid, context=context)
                        if projection is None:
                            continue
                        stats["tokenSnapshots"] += 1
                        decision = ledger.apply(
                            thread_hash=projection.threadHash,
                            last=projection.last,
                            total=projection.total,
                        )
                        if decision.duplicate:
                            stats["duplicateSnapshots"] += 1
                        if decision.lineageDiscontinuity:
                            stats["lineageDiscontinuities"] += 1
                        if decision.added and (decision.added.totalTokens or 0) > 0:
                            stats["addedEvents"] += 1
                        events.append({**projection.to_dict(), "ledger": decision.to_dict()})
        except ZstdUnavailable:
            stats["zstdBackendUnavailable"] += 1
        except ZstdCorrupt:
            stats["corruptCompressedFiles"] += 1

        next_files[sid] = {
            "fileIdentity": fid,
            "offset": 0 if is_zstd else last_complete_offset,
            "size": stat.st_size,
            "mtimeNs": stat.st_mtime_ns,
            "compressed": is_zstd,
        }

    next_checkpoint = {
        "version": CHECKPOINT_VERSION,
        "files": next_files,
        "ledger": ledger.export_state(),
    }
    return events, next_checkpoint, stats

