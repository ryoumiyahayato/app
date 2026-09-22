from __future__ import annotations

from pathlib import Path
import sys
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import appserver  # noqa: E402
import probe  # noqa: E402


class LiveGateBoundaryTests(unittest.TestCase):
    def test_replayed_live_notification_is_not_a_live_token_event(self):
        event = {
            "threadHash": "abc123",
            "total": {"inputTokens": 100, "outputTokens": 0, "totalTokens": 100},
            "ledger": {
                "duplicate": True,
                "lineageDiscontinuity": False,
                "added": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0},
            },
        }
        self.assertFalse(probe.live_event_has_positive_new_usage(event))
        self.assertIsNone(probe.live_event_cumulative_key(event))

    def test_positive_incremental_live_notification_is_eligible(self):
        event = {
            "threadHash": "abc123",
            "total": {"inputTokens": 140, "outputTokens": 0, "totalTokens": 140},
            "ledger": {
                "duplicate": False,
                "lineageDiscontinuity": False,
                "added": {"inputTokens": 40, "outputTokens": 0, "totalTokens": 40},
            },
        }
        self.assertTrue(probe.live_event_has_positive_new_usage(event))
        key = probe.live_event_cumulative_key(event)
        self.assertIsNotNone(key)
        self.assertEqual(key[0], "abc123")

    def test_lineage_discontinuity_is_not_a_live_token_event(self):
        event = {
            "threadHash": "abc123",
            "total": {"inputTokens": 120, "outputTokens": 0, "totalTokens": 120},
            "ledger": {
                "duplicate": False,
                "lineageDiscontinuity": True,
                "added": None,
            },
        }
        self.assertFalse(probe.live_event_has_positive_new_usage(event))


class ModelFreeEvidenceBoundaryTests(unittest.TestCase):
    class FakeClient:
        def __init__(self, _codex_bin: str):
            self.outbound_methods: list[str] = []
            self.notifications: list[dict] = []

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return None

        def initialize(self):
            self.outbound_methods.extend(["initialize", "initialized"])
            return {}

        def request(self, method: str, _params):
            self.outbound_methods.append(method)
            if method == "account/rateLimits/read":
                return {
                    "rateLimits": {
                        "primary": {
                            "usedPercent": 10,
                            "windowDurationMins": 300,
                            "resetsAt": 1_800_000_000,
                        }
                    }
                }
            if method == "account/usage/read":
                return {"summary": {}, "dailyUsageBuckets": []}
            raise AssertionError(method)

        def observe(self, _seconds: float):
            return None

    def test_user_activity_after_reads_does_not_invalidate_model_free_read_evidence(self):
        stable = {"rollout-a": {"size": 100}}
        changed_later = {"rollout-a": {"size": 200}}
        with patch.object(appserver, "AppServerClient", self.FakeClient), patch.object(
            appserver,
            "snapshot_rollout_metadata",
            side_effect=[stable, stable, changed_later],
        ):
            result = appserver.run_quota_probe("codex", [Path("synthetic")], 2.0)

        self.assertEqual(result["rateLimitRead"], "PASS")
        self.assertEqual(result["accountUsage"], "PASS")
        self.assertEqual(result["monitoringSideEffectEvidence"], "PASS")
        self.assertTrue(result["rolloutChangedDuringObservation"])
        self.assertEqual(result["modelFreeMonitoring"], "PASS")

    def test_rollout_change_during_read_phase_keeps_model_free_unproven(self):
        before = {"rollout-a": {"size": 100}}
        changed = {"rollout-a": {"size": 101}}
        with patch.object(appserver, "AppServerClient", self.FakeClient), patch.object(
            appserver,
            "snapshot_rollout_metadata",
            side_effect=[before, changed, changed],
        ):
            result = appserver.run_quota_probe("codex", [Path("synthetic")], 0.0)

        self.assertEqual(result["monitoringSideEffectEvidence"], "CONCURRENT ROLLOUT CHANGE OBSERVED")
        self.assertEqual(result["modelFreeMonitoring"], "PARTIAL")


if __name__ == "__main__":
    unittest.main()
