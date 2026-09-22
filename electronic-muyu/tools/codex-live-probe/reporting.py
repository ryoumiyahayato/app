"""Checkpoint sanitization, gate evaluation, reset observation and report rendering."""
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

def redact_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    # The checkpoint is already opaque. This function also prevents accidental
    # future additions of path-like keys from being persisted.
    return {
        "version": checkpoint.get("version", CHECKPOINT_VERSION),
        "files": checkpoint.get("files", {}),
        "ledger": checkpoint.get("ledger", {}),
        "quotaBaseline": checkpoint.get("quotaBaseline", {}),
    }


def load_checkpoint(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"version": CHECKPOINT_VERSION, "files": {}, "ledger": {}, "quotaBaseline": {}}
    return value if isinstance(value, dict) else {"version": CHECKPOINT_VERSION, "files": {}, "ledger": {}, "quotaBaseline": {}}


def save_checkpoint(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(redact_checkpoint(value), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sensitive_data_leak_check(value: Any) -> tuple[bool, list[str]]:
    findings: list[str] = []

    def walk(node: Any, key_path: str = "") -> None:
        if isinstance(node, Mapping):
            for key, child in node.items():
                key_s = str(key)
                if SENSITIVE_KEY_RE.search(key_s):
                    findings.append(f"forbidden-key:{key_path + key_s}")
                walk(child, key_path + key_s + ".")
        elif isinstance(node, list):
            for i, child in enumerate(node):
                walk(child, f"{key_path}{i}.")
        elif isinstance(node, str):
            if EMAIL_RE.search(node):
                findings.append("email-like-value")
            if ABS_WIN_PATH_RE.search(node) or ABS_UNIX_PATH_RE.search(node):
                findings.append("absolute-path-like-value")

    walk(value)
    return len(findings) == 0, sorted(set(findings))


def result_status_from_quota(quota_probe: Mapping[str, Any]) -> tuple[str, str, str, str]:
    if quota_probe.get("rateLimitRead") != "PASS":
        return "FAIL", "NOT PRESENT", "NOT PRESENT", "NOT PRESENT"
    quota = quota_probe.get("quota") if isinstance(quota_probe.get("quota"), Mapping) else {}
    buckets = quota.get("buckets", []) if isinstance(quota, Mapping) else []
    durations = {b.get("windowDurationMins") for b in buckets if isinstance(b, Mapping)}
    five = "OBSERVED" if 300 in durations else "NOT PRESENT"
    weekly = "OBSERVED" if 10080 in durations else "NOT PRESENT"
    credits = quota.get("resetCredits") if isinstance(quota, Mapping) else None
    reset = "OBSERVED" if isinstance(credits, Mapping) else "NOT PRESENT"
    return "PASS", five, weekly, reset


def quota_baseline_from_projection(quota: Any) -> dict[str, Any]:
    baseline: dict[str, Any] = {}
    if not isinstance(quota, Mapping):
        return baseline
    for bucket in quota.get("buckets", []):
        if not isinstance(bucket, Mapping):
            continue
        key = bucket.get("limitIdHash") or f"{bucket.get('position')}:{bucket.get('windowDurationMins')}"
        if not key:
            continue
        baseline[str(key)] = {
            "usedPercent": bucket.get("usedPercent"),
            "windowDurationMins": bucket.get("windowDurationMins"),
            "resetsAt": bucket.get("resetsAt"),
        }
    return baseline


def observe_reset(previous: Any, current: Any) -> bool:
    if not isinstance(previous, Mapping) or not isinstance(current, Mapping):
        return False
    now_epoch = int(time.time())
    for key, cur in current.items():
        old = previous.get(key)
        if not isinstance(old, Mapping) or not isinstance(cur, Mapping):
            continue
        if old.get("windowDurationMins") != cur.get("windowDurationMins"):
            continue
        old_reset = nullable_int(old.get("resetsAt"))
        new_reset = nullable_int(cur.get("resetsAt"))
        old_used = nullable_number(old.get("usedPercent"))
        new_used = nullable_number(cur.get("usedPercent"))
        reset_advanced = old_reset is not None and new_reset is not None and new_reset > old_reset
        scheduled_boundary_passed = old_reset is not None and old_reset <= now_epoch + 120
        usage_drop = old_used is not None and new_used is not None and new_used + 5.0 <= old_used
        if reset_advanced and (scheduled_boundary_passed or usage_drop):
            return True
    return False


def markdown_result(result: Mapping[str, Any]) -> str:
    fields = result["status"]
    lines = [
        "# C1-LIVE RESULT",
        "",
        f"Generated: `{result.get('generatedAt')}`",
        "",
        "```text",
        f"CODEX VERSION: {result.get('codexVersion') or 'UNAVAILABLE'}",
        "",
        f"G1 QUOTA READ: {fields['g1QuotaRead']}",
        f"RATE LIMIT READ: {fields['rateLimitRead']}",
        f"RATE LIMIT UPDATED: {fields['rateLimitUpdated']}",
        f"ACCOUNT USAGE: {fields['accountUsage']}",
        f"5H WINDOW: {fields['fiveHourWindow']}",
        f"WEEKLY WINDOW: {fields['weeklyWindow']}",
        f"RESET CREDITS: {fields['resetCredits']}",
        "",
        f"G2 TOKEN LEDGER: {fields['g2TokenLedger']}",
        f"LIVE TOKEN EVENT: {fields['liveTokenEvent']}",
        f"ROLLOUT TOKEN EVENT: {fields['rolloutTokenEvent']}",
        f"LIVE ↔ ROLLOUT MATCH: {fields['liveRolloutMatch']}",
        f"REPLAY DEDUP: {fields['replayDedup']}",
        f"RESTART DEDUP: {fields['restartDedup']}",
        f"JSONL: {fields['jsonl']}",
        f"JSONL.ZST: {fields['jsonlZst']}",
        f"SENSITIVE DATA LEAK CHECK: {fields['sensitiveDataLeakCheck']}",
        f"MODEL-FREE MONITORING: {fields['modelFreeMonitoring']}",
        f"LLM CALLS CAUSED BY MONITORING: {fields['llmCallsCausedByMonitoring']}",
        "",
        f"G3 RESET OBSERVATION: {fields['g3ResetObservation']}",
        f"RATE-LIMIT-EXHAUSTED READ: {fields['rateLimitExhaustedRead']}",
        f"LIVE USER ACCOUNT VALIDATION: {fields['liveUserAccountValidation']}",
        "",
        f"READY FOR C2 COLLECTOR MVP: {fields['readyForC2']}",
        "```",
        "",
        "`occurredAt` is left null unless a source explicitly declares token occurrence time. "
        "Rollout timestamps are reported only as `persistedAt`.",
        "",
        "The report contains projected quota/token metadata and opaque hashes only. Raw rollout "
        "records, prompts, responses, code, tool output, credentials, email, and full paths are not retained.",
    ]
    return "\n".join(lines) + "\n"

