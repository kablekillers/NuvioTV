"""Local, explicitly confirmed Nuvio Home layout transfer.

Run from a trusted computer only. Set NUVIO_ANON_KEY in the environment.
python apply_live.py --platform tv --backup-dir ./private-backups
Use --platform mobile separately. Never paste passwords into chat.

The script authenticates two distinct accounts, fetches layouts, shows a
preview, requires typed confirmation, saves an exclusive local backup,
and writes ONLY destination Home catalog settings with readback/rollback.
"""
import argparse
import getpass
import os
from pathlib import Path
from datetime import datetime, timezone
from nuvio_readonly import NuvioClient
from nuvio_home_adapter import HomeSettingsAdapter
from layout_adapter import decode_home_layout
from preview_receipt import preview_receipt
from guarded_apply import apply_with_durable_backup

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--platform",choices=("tv","mobile"),required=True)
    p.add_argument("--source-profile",type=int,choices=(1,2,3,4),default=1)
    p.add_argument("--destination-profile",type=int,choices=(1,2,3,4),default=1)
    p.add_argument("--backup-dir",default="private-backups")
    args=p.parse_args()
    key=os.getenv("NUVIO_ANON_KEY")
    if not key:
        p.error("NUVIO_ANON_KEY publishable key is required")
    client=NuvioClient(key)
    print("Source: Kable Killers main account")
    src_email=input("Source email: ").strip()
    dst_email=input("Destination email: ").strip()
    if not src_email or not dst_email or src_email.casefold()==dst_email.casefold():
        p.error("Source and destination must be distinct accounts")
    src_password=getpass.getpass("Source password (hidden): ")
    src_auth=client.login(src_email,src_password)
    del src_password
    print("Destination: personal main account")
    dst_password=getpass.getpass("Destination password (hidden): ")
    dst_auth=client.login(dst_email,dst_password)
    del dst_password
    if src_auth["access_token"]==dst_auth["access_token"]:
        p.error("Both sessions appear identical; transfer blocked")
    source=decode_home_layout(client.home_layout(
        src_auth["access_token"],args.source_profile,args.platform))
    destination=HomeSettingsAdapter(client,dst_auth["access_token"],
                                    args.destination_profile,args.platform)
    before=destination.pull()
    receipt=preview_receipt(source,before,args.platform,
                            args.source_profile,args.destination_profile)
    print("Preview:", "platform=",args.platform,
          "matched=",receipt["matched"],
          "destination_only=",receipt["destination_only"],
          "missing=",receipt["unmatched_count"],
          "ambiguous=",receipt["ambiguous_count"],
          "unverified_collections=",receipt["unverified_collection_count"])
    if not receipt["safe_to_apply"]:
        print("BLOCKED: cannot safely map all source rows. No changes made.")
        return 2
    print("This will replace ONLY the destination's Home row order and visibility.")
    print("Source and destination:",src_email,"->",dst_email)
    phrase=input("To approve, type exactly COPY HOME LAYOUT: ")
    if phrase!="COPY HOME LAYOUT":
        print("Cancelled; no changes made.")
        return 1
    folder=Path(args.backup_dir)
    folder.mkdir(parents=True,exist_ok=True)
    stamp=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup=folder/("destination-"+args.platform+"-"+stamp+".json")
    result=apply_with_durable_backup(
        source,destination,receipt,phrase,args.source_profile,
        args.destination_profile,backup)
    print("Result:",result["status"],"Matched:",result["matched"])
    print("Backup:",result["backup_path"])
    return 0

if __name__=="__main__":
    raise SystemExit(main())
