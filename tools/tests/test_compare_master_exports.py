"""Tests for offline change detection; uses only fictional account data."""
import importlib.util
import unittest
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "compare_master_exports.py"
spec = importlib.util.spec_from_file_location("compare_exports", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def snapshot(urls=("a", "b"), collections=None):
    return {
        "addons": [{"url": u, "name": u, "enabled": True, "sort_order": i} for i, u in enumerate(urls)],
        "collections": [] if collections is None else collections,
    }

class CompareTests(unittest.TestCase):
    def test_no_changes(self):
        result = module.compare(snapshot(), snapshot())
        self.assertFalse(result["requires_review"])
        self.assertFalse(result["published_to_customers"])

    def test_add_remove_and_order(self):
        result = module.compare(snapshot(("a", "b")), snapshot(("b", "c")))
        self.assertEqual(result["addon_added_count"], 1)
        self.assertEqual(result["addon_removed_count"], 1)
        self.assertTrue(result["requires_review"])

    def test_enabled_change(self):
        before = snapshot()
        after = snapshot()
        after["addons"][0]["enabled"] = False
        self.assertEqual(module.compare(before, after)["addon_modified_count"], 1)

    def test_reorder(self):
        before = snapshot(("a", "b"))
        after = snapshot(("b", "a"))
        self.assertTrue(module.compare(before, after)["addon_array_order_changed"])

    def test_collection_changes(self):
        before = snapshot(collections=[{"title": "Movies"}])
        after = snapshot(collections=[{"title": "TV"}])
        self.assertTrue(module.compare(before, after)["collections_changed"])

if __name__ == "__main__":
    unittest.main()
