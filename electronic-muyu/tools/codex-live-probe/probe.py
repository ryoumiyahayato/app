#!/usr/bin/env python3
"""C1-LIVE Codex data-source validation probe.

Validation harness only: no product Collector, no turn creation, no credentials.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Optional

from core import *
from rollout import *
from appserver import *
from reporting import *

def run_self_tests() -> dict[str, Any]:
    tests_dir = Path(__file__).with_name("tests")
    proc = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", str(tests_dir), "-p", "test_*.py", "-v"],
        cwd=str(Path(__file__).parent),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=90,
        check=False,
    )
    text = proc.stdout or ""
    ran_match = re.search(r"Ran (\d+) tests?", text)
    return {
        "pass": proc.returncode == 0,
        "testsRun": int(ran_match.group(1)) if ran_match else None,
        "zstdAvailable": zstd_backend_available(),
    }


def zstd_backend_available() -> bool:
    try:
        import compression.zstd  # type: ignore  # noqa:F401
        return True
    except ImportError:
        pass
    try:
        import zstandard  # type: ignore  # noqa:F401
        return True
    except ImportError:
        return shutil.which("zstd") is not None


def live_command(args: argparse.Namespace) -> int:
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_path = output_dir / ".c1_live_checkpoint.json"
    checkpoint = load_checkpoint(checkpoint_path)
    roots = [Path(p) for p in args.rollout_root] if args.rollout_root else default_rollout_roots()

    self_tests = run_self_tests()
    codex_bin = args.codex_bin or os.environ.get("CODEX_BIN") or shutil.which("codex")
    version = codex_version(codex_bin) if codex_bin else None
    quota_probe: dict[str, Any]
    if codex_bin:
        quota_probe = run_quota_probe(codex_bin, roots, args.observe_seconds)
    else:
        quota_probe = {
            "rateLimitRead": "FAIL",
            "accountUsage": "FAIL",
            "rateLimitUpdated": "NOT OBSERVED",
            "quota": None,
            "accountUsageProjection": None,
            "monitoringSideEffectEvidence": "INCONCLUSIVE",
            "outboundMethods": [],
            "modelFreeMonitoring": "PARTIAL",
            "errorClass": "Codex executable not found",
            "liveTokenNotifications": [],
        }

    ledger = TokenLedger(checkpoint.get("ledger") if isinstance(checkpoint, Mapping) else None)
    live_events: list[dict[str, Any]] = []
    for live in quota_probe.get("liveTokenNotifications", []):
        if not isinstance(live, Mapping):
            continue
        thread_hash = nullable_string(live.get("threadHash"), 64)
        if not thread_hash:
            continue
        last = TokenBreakdown.from_mapping(live.get("last"))
        total = TokenBreakdown.from_mapping(live.get("total"))
        decision = ledger.apply(thread_hash=thread_hash, last=last, total=total)
        live_events.append({**dict(live), "ledger": decision.to_dict()})

    events, next_checkpoint, scan_stats = scan_rollouts(roots, ledger=ledger, checkpoint=checkpoint)

    g1, five, weekly, reset_credits = result_status_from_quota(quota_probe)
    rollout_event = "PASS" if scan_stats["tokenSnapshots"] > 0 else "NOT OBSERVED"
    parser_plain = "PASS" if self_tests["pass"] else "FAIL"
    parser_zstd = "PASS" if self_tests["pass"] and self_tests["zstdAvailable"] else "FAIL"
    replay_dedup = "PASS" if self_tests["pass"] else "FAIL"
    restart_dedup = "PASS" if self_tests["pass"] else "FAIL"

    live_token_status = "PASS" if live_events else "NOT OBSERVED"
    live_keys: set[tuple[str, str]] = set()
    for live in live_events:
        total = TokenBreakdown.from_mapping(live.get("total"))
        if total is not None:
            live_keys.add((str(live.get("threadHash")), total.fingerprint()))
    rollout_keys: set[tuple[str, str]] = set()
    for event in events:
        total = TokenBreakdown.from_mapping(event.get("total"))
        if total is not None:
            rollout_keys.add((str(event.get("threadHash")), total.fingerprint()))
    if live_keys and rollout_keys:
        live_rollout_match = "PASS" if (live_keys & rollout_keys) else "FAIL"
    else:
        live_rollout_match = "NOT OBSERVED"

    previous_quota_baseline = checkpoint.get("quotaBaseline", {}) if isinstance(checkpoint, Mapping) else {}
    current_quota_baseline = quota_baseline_from_projection(quota_probe.get("quota"))
    reset_observed = observe_reset(previous_quota_baseline, current_quota_baseline)
    next_checkpoint["quotaBaseline"] = current_quota_baseline or previous_quota_baseline
    save_checkpoint(checkpoint_path, next_checkpoint)

    provisional = {
        "probeVersion": PROBE_VERSION,
        "generatedAt": utc_now(),
        "codexVersion": version,
        "quotaProbe": quota_probe,
        "rolloutScan": {"stats": scan_stats, "events": events},
        "liveNotifications": live_events,
        "selfTests": self_tests,
    }
    leak_ok, leak_findings = sensitive_data_leak_check(provisional)

    g2 = (
        "PASS"
        if live_token_status == "PASS"
        and rollout_event == "PASS"
        and live_rollout_match == "PASS"
        and replay_dedup == "PASS"
        and restart_dedup == "PASS"
        and parser_plain == "PASS"
        and parser_zstd == "PASS"
        else "PARTIAL"
    )
    model_free = quota_probe.get("modelFreeMonitoring", "PARTIAL")
    sensitive = "PASS" if leak_ok else "FAIL"
    ready = (
        "YES"
        if g1 == "PASS"
        and g2 == "PASS"
        and model_free == "PASS"
        and sensitive == "PASS"
        and parser_plain == "PASS"
        and parser_zstd == "PASS"
        and replay_dedup == "PASS"
        and restart_dedup == "PASS"
        else "NO"
    )

    status = {
        "g1QuotaRead": g1,
        "rateLimitRead": quota_probe.get("rateLimitRead", "FAIL"),
        "rateLimitUpdated": quota_probe.get("rateLimitUpdated", "NOT OBSERVED"),
        "accountUsage": quota_probe.get("accountUsage", "FAIL"),
        "fiveHourWindow": five,
        "weeklyWindow": weekly,
        "resetCredits": reset_credits,
        "g2TokenLedger": g2,
        "liveTokenEvent": live_token_status,
        "rolloutTokenEvent": rollout_event,
        "liveRolloutMatch": live_rollout_match,
        "replayDedup": replay_dedup,
        "restartDedup": restart_dedup,
        "jsonl": parser_plain,
        "jsonlZst": parser_zstd,
        "sensitiveDataLeakCheck": sensitive,
        "modelFreeMonitoring": model_free,
        "llmCallsCausedByMonitoring": 0 if model_free == "PASS" else "UNPROVEN",
        "g3ResetObservation": "PASS" if reset_observed else "PARTIAL",
        "rateLimitExhaustedRead": (
            "PASS"
            if quota_probe.get("rateLimitRead") == "PASS"
            and isinstance(quota_probe.get("quota"), Mapping)
            and quota_probe["quota"].get("rateLimitReachedType") is not None
            else "NOT OBSERVED"
        ),
        "liveUserAccountValidation": "PENDING" if ready == "NO" else "PASS",
        "readyForC2": ready,
    }
    result = {**provisional, "status": status, "sensitiveDataLeakFindings": leak_findings}
    final_ok, final_findings = sensitive_data_leak_check(result)
    result["status"]["sensitiveDataLeakCheck"] = "PASS" if final_ok else "FAIL"
    result["sensitiveDataLeakFindings"] = final_findings
    if not final_ok:
        result["status"]["readyForC2"] = "NO"
        result = {
            "probeVersion": PROBE_VERSION,
            "generatedAt": utc_now(),
            "codexVersion": None,
            "selfTests": self_tests,
            "status": result["status"],
            "sensitiveDataLeakFindings": final_findings,
            "reportRedactedFailClosed": True,
        }

    json_path = output_dir / "C1_LIVE_RESULT.json"
    md_path = output_dir / "C1_LIVE_RESULT.md"
    json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    md_path.write_text(markdown_result(result), encoding="utf-8")

    print(markdown_result(result), end="")
    return 0 if self_tests["pass"] else 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Strictly isolated C1-LIVE Codex validation probe")
    sub = parser.add_subparsers(dest="command", required=True)

    live = sub.add_parser("live", help="Run read-only quota probe, rollout projection, and invariant tests")
    live.add_argument("--codex-bin", default=None, help="Codex executable; defaults to CODEX_BIN/PATH")
    live.add_argument(
        "--rollout-root",
        action="append",
        default=[],
        help="Override rollout root. Full path is used transiently and never saved in reports.",
    )
    live.add_argument("--output-dir", default=str(Path(__file__).parent))
    live.add_argument("--observe-seconds", type=float, default=2.0)
    live.set_defaults(func=live_command)

    test = sub.add_parser("self-test", help="Run synthetic parser/dedup safety tests")
    test.set_defaults(func=lambda _args: (print(json.dumps(run_self_tests(), indent=2)), 0)[1])
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
