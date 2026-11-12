#!/usr/bin/env python3
"""Offline, read-only validator for NuvioSync JSON exports.

Never commit an actual export: add-on URLs can contain private credentials.
Usage: python3 tools/validate_nuviosync_backup.py /path/to/export.json
"""
import argparse
import json
from pathlib import Path

SECTIONS = ("library", "watched", "progress", "addons", "collections")

def validate(path):
    with Path(path).open(encoding="utf-8") as stream:
        data = json.load(stream)
    if not isinstance(data, dict) or data.get("app") != "NuvioSync":
        raise ValueError("Not a NuvioSync export")
    if data.get("backupVersion") != "1.0.0":
        raise ValueError("Unsupported backup version; inspect schema before restoring")
    original = data.get("original")
    if not isinstance(original, dict):
        raise ValueError("Missing original section")
    included = data.get("includedSections", [])
    if not isinstance(included, list):
        raise ValueError("Invalid includedSections")
    counts = {}
    for section in SECTIONS:
        if section in included:
            items = original.get(section)
            if not isinstance(items, list):
                raise ValueError(f"Missing or invalid {section} array")
            counts[section] = len(items)
    return {"format": data["app"], "version": data["backupVersion"],
            "profile": data.get("profileName"), "counts": counts,
            "read_only": True, "restored": False}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", help="Local private NuvioSync JSON export")
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.export), indent=2))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(1, f"Validation failed: {exc}\n")
