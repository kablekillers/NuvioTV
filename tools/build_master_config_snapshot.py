#!/usr/bin/env python3
"""Build a PRIVATE, deterministic master configuration snapshot from NuvioSync export.

Output may contain secret-bearing add-on URLs. Do not commit or publish it.
Does not modify the input, connect to a server, or restore to a device.
"""
import argparse
import hashlib
import json
from pathlib import Path

def build_snapshot(source):
    if not isinstance(source, dict) or source.get("app") != "NuvioSync" or source.get("backupVersion") != "1.0.0":
        raise ValueError("Expected NuvioSync backupVersion 1.0.0")
    original = source.get("original")
    if not isinstance(original, dict):
        raise ValueError("Missing original data")
    addons, collections = original.get("addons"), original.get("collections")
    if not isinstance(addons, list) or not isinstance(collections, list):
        raise ValueError("Backup must include addons and collections")
    cleaned_addons = []
    for a in addons:
        if not isinstance(a, dict) or not isinstance(a.get("url"), str):
            raise ValueError("Malformed add-on")
        cleaned_addons.append({k: a.get(k) for k in ("url", "name", "enabled", "sort_order")})
    # Collection/folder/source order is represented by array position. Preserve it exactly.
    cleaned_collections = json.loads(json.dumps(collections))
    result = {
        "schema": "kable-killers-master-config-v1",
        "profileName": source.get("profileName"),
        "addons": cleaned_addons,
        "collections": cleaned_collections,
    }
    payload = json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    return result, hashlib.sha256(payload).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export")
    parser.add_argument("output", help="PRIVATE local JSON output path")
    args = parser.parse_args()
    output = Path(args.output)
    if output.exists():
        parser.error("Output exists; refusing to overwrite")
    source = json.loads(Path(args.export).read_text(encoding="utf-8"))
    snapshot, digest = build_snapshot(source)
    # Restrict permissions before writing on systems supporting POSIX mode bits.
    import os
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(snapshot, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"profile": snapshot["profileName"],
                      "addons": len(snapshot["addons"]),
                      "collections": len(snapshot["collections"]),
                      "sha256_canonical": digest,
                      "private_output": str(output),
                      "restored": False}, indent=2))

if __name__ == "__main__":
    main()
