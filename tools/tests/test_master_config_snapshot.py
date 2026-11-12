"""Offline tests for the NuvioSync master snapshot builder.

Run: python3 -m unittest discover -s tools/tests -v
No real account data or network access required.
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "build_master_config_snapshot.py"
spec = importlib.util.spec_from_file_location("snapshot_builder", SCRIPT)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.export = {
            "app": "NuvioSync", "backupVersion": "1.0.0",
            "profileName": "Kable Killers",
            "original": {
                "addons": [
                    {"url": "https://example.invalid/b", "name": "B", "enabled": False, "sort_order": 1, "user_id": "private"},
                    {"url": "https://example.invalid/a", "name": "A", "enabled": True, "sort_order": 0, "user_id": "private"}
                ],
                "collections": [{"title": "Second", "folders": [{"name": "Z"}, {"name": "A"}], "viewMode": "grid"},
                                {"title": "First", "folders": [], "pinToTop": True}],
                "watched": [{"title": "PRIVATE WATCH HISTORY"}],
                "progress": [{"position": 42}],
            },
        }

    def test_preserves_order_and_flags(self):
        snapshot, digest = builder.build_snapshot(self.export)
        self.assertEqual([x["name"] for x in snapshot["addons"]], ["B", "A"])
        self.assertEqual([x["title"] for x in snapshot["collections"]], ["Second", "First"])
        self.assertEqual([x["name"] for x in snapshot["collections"][0]["folders"]], ["Z", "A"])
        self.assertFalse(snapshot["addons"][0]["enabled"])
        self.assertEqual(len(digest), 64)

    def test_does_not_share_history_or_user_id(self):
        snapshot, _ = builder.build_snapshot(self.export)
        payload = json.dumps(snapshot)
        self.assertNotIn("PRIVATE WATCH HISTORY", payload)
        self.assertNotIn("user_id", payload)
        self.assertNotIn("progress", snapshot)

    def test_deterministic_and_nonmutating(self):
        before = json.dumps(self.export, sort_keys=True)
        self.assertEqual(builder.build_snapshot(self.export), builder.build_snapshot(self.export))
        self.assertEqual(json.dumps(self.export, sort_keys=True), before)

    def test_rejects_invalid_exports(self):
        for bad in ({}, {"app": "NuvioSync", "backupVersion": "2.0.0"},
                    {"app": "NuvioSync", "backupVersion": "1.0.0", "original": {"addons": [], "collections": None}}):
            with self.assertRaises(ValueError):
                builder.build_snapshot(bad)

if __name__ == "__main__":
    unittest.main()
