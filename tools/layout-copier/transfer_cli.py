"""Offline CLI for reviewing Home layout transfers; no network calls.

Usage:
  python transfer_cli.py source-tv.json destination-tv.json --out ./preview-tv
Run separately for Mobile and TV. Files must be Home layout exports with items arrays.
"""
import argparse
import json
from pathlib import Path
from layout_plan import plan

def read(path):
    obj=json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(obj,dict) or not isinstance(obj.get("items"),list):
        raise ValueError(f"{path}: expected Home layout JSON object with items array")
    if not all(isinstance(item,dict) for item in obj["items"]):
        raise ValueError(f"{path}: invalid row")
    return obj

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("destination")
    parser.add_argument("--out",default="layout-preview")
    args=parser.parse_args()
    source=read(args.source)
    destination=read(args.destination)
    result=plan(source,destination)
    out=Path(args.out)
    out.mkdir(parents=True,exist_ok=True)
    (out/"destination-backup.json").write_text(json.dumps(destination,indent=2),encoding="utf-8")
    summary={k:v for k,v in result.items() if k!="preview"}
    (out/"comparison.json").write_text(json.dumps(summary,indent=2),encoding="utf-8")
    if result["safe_to_apply"]:
        (out/"proposed-layout.json").write_text(json.dumps(result["preview"],indent=2),encoding="utf-8")
        print("All source rows matched; proposal saved. No Nuvio settings were changed.")
    else:
        print("UNMATCHED OR AMBIGUOUS ROWS: proposal withheld. Review comparison.json.")
    print("Destination backup saved. No network requests made.")
    return 0 if result["safe_to_apply"] else 2

if __name__=="__main__":
    raise SystemExit(main())
