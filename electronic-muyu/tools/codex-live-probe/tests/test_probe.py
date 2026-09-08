from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import probe  # noqa: E402


def usage(total: int, *, input_tokens: int | None = None, output_tokens: int | None = None,
          cached: int | None = None, reasoning: int | None = None) -> probe.TokenBreakdown:
    if input_tokens is None and output_tokens is None:
        input_tokens = total
        output_tokens = 0
    return probe.TokenBreakdown(
        inputTokens=input_tokens,
        cachedInputTokens=cached,
        outputTokens=output_tokens,
        reasoningOutputTokens=reasoning,
        totalTokens=total,
    )


def token_record(total: int, last: int, ordinal: int, thread_id: str = "thread-secret", turn_id: str = "turn-secret") -> list[dict]:
    return [
        {
            "timestamp": "2026-09-08T10:00:00.000Z",
            "type": "session_meta",
            "payload": {
                "id": thread_id,
                "cwd": "C:\\Users\\private\\project",
                "prompt": "must never survive projection",
            },
        },
        {
            "timestamp": "2026-09-08T10:00:01.000Z",
            "type": "event_msg",
            "payload": {"type": "turn_started", "turn_id": turn_id, "message": "private"},
        },
        {
            "timestamp": f"2026-09-08T10:00:{ordinal + 1:02d}.000Z",
            "ordinal": ordinal,
            "type": "event_msg",
            "payload": {
                "type": "token_count",
                "info": {
                    "last_token_usage": {
                        "input_tokens": last,
                        "cached_input_tokens": min(last, last // 2),
                        "output_tokens": 0,
                        "reasoning_output_tokens": 0,
                        "total_tokens": last,
                    },
                    "total_token_usage": {
                        "input_tokens": total,
                        "cached_input_tokens": min(total, total // 2),
                        "output_tokens": 0,
                        "reasoning_output_tokens": 0,
                        "total_tokens": total,
                    },
                },
                "raw_response": "private",
            },
        },
    ]


def write_jsonl(path: Path, records: list[dict], *, trailing_newline: bool = True) -> None:
    data = "\n".join(json.dumps(x, separators=(",", ":")) for x in records)
    if trailing_newline:
        data += "\n"
    path.write_text(data, encoding="utf-8")


def compress_zstd(src: Path, dst: Path) -> bool:
    try:
        import compression.zstd as zstd  # type: ignore
        with src.open("rb") as inp, zstd.ZstdFile(dst, "wb") as out:  # type: ignore[attr-defined]
            shutil.copyfileobj(inp, out)
        return True
    except (ImportError, AttributeError):
        pass
    try:
        import zstandard as zstd  # type: ignore
        cctx = zstd.ZstdCompressor()
        with src.open("rb") as inp, dst.open("wb") as out:
            cctx.copy_stream(inp, out)
        return True
    except ImportError:
        pass
    exe = shutil.which("zstd")
    if not exe:
        return False
    with dst.open("wb") as out:
        proc = subprocess.run([exe, "-q", "-c", str(src)], stdout=out, stderr=subprocess.DEVNULL)
    return proc.returncode == 0


class LedgerInvariantTests(unittest.TestCase):
    def test_case_a_first_last_100_total_100_adds_100(self):
        ledger = probe.TokenLedger()
        decision = ledger.apply(thread_hash="t", last=usage(100), total=usage(100))
        self.assertEqual(decision.added.totalTokens, 100)
        self.assertEqual(decision.semantics, "incremental_last")

    def test_case_b_last_40_total_140_adds_40(self):
        ledger = probe.TokenLedger()
        ledger.apply(thread_hash="t", last=usage(100), total=usage(100))
        decision = ledger.apply(thread_hash="t", last=usage(40), total=usage(140))
        self.assertEqual(decision.added.totalTokens, 40)

    def test_case_c_replay_last_40_total_140_adds_zero(self):
        ledger = probe.TokenLedger()
        ledger.apply(thread_hash="t", last=usage(100), total=usage(100))
        ledger.apply(thread_hash="t", last=usage(40), total=usage(140))
        replay = ledger.apply(thread_hash="t", last=usage(40), total=usage(140))
        self.assertTrue(replay.duplicate)
        self.assertEqual(replay.added.totalTokens, 0)

    def test_case_d_total_only_200_from_140_infers_60(self):
        ledger = probe.TokenLedger()
        ledger.apply(thread_hash="t", last=usage(140), total=usage(140))
        decision = ledger.apply(thread_hash="t", last=None, total=usage(200))
        self.assertEqual(decision.semantics, "inferred_delta")
        self.assertEqual(decision.added.totalTokens, 60)

    def test_case_e_total_regression_is_discontinuity_not_negative(self):
        ledger = probe.TokenLedger()
        ledger.apply(thread_hash="t", last=usage(200), total=usage(200))
        decision = ledger.apply(thread_hash="t", last=usage(20), total=usage(120))
        self.assertTrue(decision.lineageDiscontinuity)
        self.assertIsNone(decision.added)

    def test_cached_and_reasoning_are_subsets_not_added_to_total(self):
        item = usage(130, input_tokens=100, output_tokens=30, cached=80, reasoning=20)
        item.validate()
        self.assertEqual(item.totalTokens, 130)
        self.assertNotEqual(item.totalTokens, 100 + 80 + 30 + 20)

    def test_invalid_cached_subset_is_rejected(self):
        with self.assertRaises(probe.ProbeError):
            usage(100, input_tokens=50, output_tokens=50, cached=60).validate()

    def test_invalid_reasoning_subset_is_rejected(self):
        with self.assertRaises(probe.ProbeError):
            usage(100, input_tokens=50, output_tokens=50, reasoning=60).validate()

    def test_restart_state_preserves_seen_cumulative_fingerprint(self):
        first = probe.TokenLedger()
        first.apply(thread_hash="t", last=usage(100), total=usage(100))
        first.apply(thread_hash="t", last=usage(40), total=usage(140))
        restarted = probe.TokenLedger(first.export_state())
        replay = restarted.apply(thread_hash="t", last=usage(40), total=usage(140))
        self.assertTrue(replay.duplicate)
        self.assertEqual(replay.added.totalTokens, 0)


class ProjectionSafetyTests(unittest.TestCase):
    def test_live_notification_hashes_ids_and_leaves_time_model_null(self):
        raw = {
            "method": "thread/tokenUsage/updated",
            "params": {
                "threadId": "thread-secret",
                "turnId": "turn-secret",
                "tokenUsage": {
                    "last": {"inputTokens": 4, "outputTokens": 1, "totalTokens": 5},
                    "total": {"inputTokens": 4, "outputTokens": 1, "totalTokens": 5},
                },
                "model": "do-not-trust-default",
            },
        }
        projected = probe.project_live_token_notification(raw)
        text = json.dumps(projected)
        self.assertNotIn("thread-secret", text)
        self.assertNotIn("turn-secret", text)
        self.assertIsNone(projected["occurredAt"])
        self.assertIsNone(projected["model"])

    def test_rollout_projection_discards_prompt_paths_and_raw_content(self):
        context = probe.RolloutContext()
        records = token_record(100, 100, 1)
        projection = None
        for record in records:
            projection = probe.project_rollout_record(record, source_identity="sourcehash", context=context) or projection
        payload = projection.to_dict()
        text = json.dumps(payload)
        self.assertNotIn("must never survive", text)
        self.assertNotIn("Users", text)
        self.assertNotIn("thread-secret", text)
        self.assertNotIn("turn-secret", text)
        self.assertIsNone(payload["occurredAt"])
        self.assertEqual(payload["persistedAt"], "2026-09-08T10:00:02.000Z")

    def test_external_string_allowlists_reject_path_email_and_malformed_timestamp(self):
        self.assertIsNone(probe.safe_model_string("person@example.com"))
        self.assertIsNone(probe.safe_model_string("C:\\Users\\private"))
        self.assertIsNone(probe.safe_timestamp("C:\\Users\\private"))
        self.assertEqual(probe.safe_model_string("gpt-5.6-codex"), "gpt-5.6-codex")
        self.assertEqual(probe.safe_timestamp("2026-09-08T10:00:00.000Z"), "2026-09-08T10:00:00.000Z")

    def test_sensitive_leak_check_catches_email_and_absolute_path(self):
        ok, findings = probe.sensitive_data_leak_check({"safe": "person@example.com", "x": "C:\\Users\\a\\x"})
        self.assertFalse(ok)
        self.assertTrue(findings)

    def test_checkpoint_contains_no_full_path(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "rollout.jsonl"
            write_jsonl(path, token_record(100, 100, 1))
            ledger = probe.TokenLedger()
            _, checkpoint, _ = probe.scan_rollouts([root], ledger=ledger)
            text = json.dumps(checkpoint)
            self.assertNotIn(str(root), text)
            self.assertNotIn("rollout.jsonl", text)


class RolloutParserTests(unittest.TestCase):
    def test_plain_and_zstd_projection_are_equivalent(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            plain = root / "a.jsonl"
            zst = root / "b.jsonl.zst"
            write_jsonl(plain, token_record(100, 100, 1))
            if not compress_zstd(plain, zst):
                self.fail("No Zstandard backend available")

            plain_dir = root / "plain"
            zst_dir = root / "zst"
            plain_dir.mkdir(); zst_dir.mkdir()
            plain.rename(plain_dir / "a.jsonl")
            zst.rename(zst_dir / "b.jsonl.zst")
            e1, _, s1 = probe.scan_rollouts([plain_dir], ledger=probe.TokenLedger())
            e2, _, s2 = probe.scan_rollouts([zst_dir], ledger=probe.TokenLedger())
            self.assertEqual(s1["tokenSnapshots"], 1)
            self.assertEqual(s2["tokenSnapshots"], 1)
            p1 = {k: v for k, v in e1[0].items() if k not in {"sourceIdentity"}}
            p2 = {k: v for k, v in e2[0].items() if k not in {"sourceIdentity"}}
            self.assertEqual(p1, p2)

    def test_truncated_final_json_line_is_ignored_and_offset_not_advanced(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "a.jsonl"
            write_jsonl(path, token_record(100, 100, 1))
            with path.open("ab") as fh:
                fh.write(b'{"timestamp":"2026-09-08T10:00')
            ledger = probe.TokenLedger()
            _, checkpoint, stats = probe.scan_rollouts([root], ledger=ledger)
            self.assertEqual(stats["truncatedTailIgnored"], 1)
            sid = probe.source_identity(path)
            self.assertLess(checkpoint["files"][sid]["offset"], path.stat().st_size)

    def test_corrupt_compressed_file_is_isolated(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            bad = root / "bad.jsonl.zst"
            bad.write_bytes(b"not-a-zstd-frame")
            _, _, stats = probe.scan_rollouts([root], ledger=probe.TokenLedger())
            if stats["zstdBackendUnavailable"]:
                self.fail("No Zstandard backend available")
            self.assertEqual(stats["corruptCompressedFiles"], 1)

    def test_duplicate_replay_does_not_add_again(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "a.jsonl"
            write_jsonl(path, token_record(100, 100, 1))
            ledger = probe.TokenLedger()
            _, checkpoint, first_stats = probe.scan_rollouts([root], ledger=ledger)
            self.assertEqual(first_stats["addedEvents"], 1)
            checkpoint["files"] = {}
            restarted = probe.TokenLedger(checkpoint["ledger"])
            _, _, second_stats = probe.scan_rollouts([root], ledger=restarted, checkpoint=checkpoint)
            self.assertEqual(second_stats["addedEvents"], 0)
            self.assertEqual(second_stats["duplicateSnapshots"], 1)

    def test_plain_file_offset_restart_reads_no_old_token_snapshot(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "a.jsonl"
            write_jsonl(path, token_record(100, 100, 1))
            ledger = probe.TokenLedger()
            _, checkpoint, first_stats = probe.scan_rollouts([root], ledger=ledger)
            self.assertEqual(first_stats["tokenSnapshots"], 1)
            restarted = probe.TokenLedger(checkpoint["ledger"])
            _, _, second_stats = probe.scan_rollouts([root], ledger=restarted, checkpoint=checkpoint)
            self.assertEqual(second_stats["tokenSnapshots"], 0)
            self.assertEqual(second_stats["addedEvents"], 0)

    def test_file_replacement_replays_old_snapshot_without_double_count(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "a.jsonl"
            write_jsonl(path, token_record(100, 100, 1))
            ledger = probe.TokenLedger()
            _, checkpoint, _ = probe.scan_rollouts([root], ledger=ledger)

            replacement = root / "replacement.tmp"
            records = token_record(100, 100, 1) + token_record(140, 40, 2)[2:]
            write_jsonl(replacement, records)
            os.replace(replacement, path)
            restarted = probe.TokenLedger(checkpoint["ledger"])
            _, _, stats = probe.scan_rollouts([root], ledger=restarted, checkpoint=checkpoint)
            self.assertEqual(stats["fileReplacements"], 1)
            self.assertEqual(stats["duplicateSnapshots"], 1)
            self.assertEqual(stats["addedEvents"], 1)

    def test_file_shrink_resets_offset_without_negative_tokens(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "a.jsonl"
            records = token_record(100, 100, 1) + token_record(140, 40, 2)[2:]
            write_jsonl(path, records)
            ledger = probe.TokenLedger()
            _, checkpoint, _ = probe.scan_rollouts([root], ledger=ledger)
            write_jsonl(path, token_record(100, 100, 1))
            restarted = probe.TokenLedger(checkpoint["ledger"])
            _, _, stats = probe.scan_rollouts([root], ledger=restarted, checkpoint=checkpoint)
            self.assertEqual(stats["fileShrinks"], 1)
            self.assertEqual(stats["addedEvents"], 0)
            self.assertEqual(stats["duplicateSnapshots"], 1)


if __name__ == "__main__":
    unittest.main()
