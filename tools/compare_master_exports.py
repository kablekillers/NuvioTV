#!/usr/bin/env python3
"""Compare two private NuvioSync exports without revealing add-on URLs.

This is an OFFLINE change detector, not automatic Nuvio server synchronization.
Usage: python3 tools/compare_master_exports.py previous.json current.json
"""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("snapshot_builder", HERE / "build_master_config_snapshot.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

def load(path):
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    return builder.build_snapshot(raw)[0]

def compare(old, new):
    # URL is used only as a local matching key; never print it.
    before = {a["url"]: a for a in old["addons"]}
    after = {a["url"]: a for a in new["addons"]}
    added = set(after) - set(before)
    removed = set(before) - set(after)
    changed = [u for u in set(before) & set(after) if before[u] != after[u]]
    old_order = [a["url"] for a in old["addons"] if a["url"] in after]
    new_order = [a["url"] for a in new["addons"] if a["url"] in before]
    return {
        "addon_added_count": len(added),
        "addon_removed_count": len(removed),
        "addon_modified_count": len(changed),
        "addon_array_order_changed": old_order != new_order,
        "collection_count_before": len(old["collections"]),
        "collection_count_after": len(new["collections"]),
        "collections_changed": old["collections"] != new["collections"],
        "requires_review": bool(added or removed or changed or old_order != new_order or old["collections"] != new["collections"]),
        "pushed_to_nuvio": False,
        "published_to_customers": False,
    }

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("previous")
    p.add_argument("current")
    args = p.parse_args()
    print(json.dumps(compare(load(args.previous), load(args.current)), indent=2))
