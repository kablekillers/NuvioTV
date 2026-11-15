"""Read-only, two-account Nuvio layout preview from a local terminal.

Run: NUVIO_ANON_KEY=<publishable key> python compare_live.py --platform tv --source-profile 1 --destination-profile 1
The passwords are requested with getpass (not saved or shown). The script
never sends any write RPC. Do not run on an untrusted computer.
"""
import argparse
import getpass
import json
import os
from pathlib import Path
from nuvio_readonly import NuvioClient
from layout_adapter import decode_home_layout
from layout_plan import plan

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--platform",choices=("tv","mobile"),required=True)
    p.add_argument("--source-profile",type=int,default=1)
    p.add_argument("--destination-profile",type=int,default=1)
    p.add_argument("--output",default="layout-preview")
    args=p.parse_args()
    key=os.getenv("NUVIO_ANON_KEY")
    if not key: p.error("NUVIO_ANON_KEY environment variable is required")
    api=NuvioClient(key)
    print("SOURCE: Kable Killers main account")
    source_email=input("Source email: ").strip()
    source_password=getpass.getpass("Source password (hidden): ")
    source_auth=api.login(source_email,source_password)
    del source_password
    print("DESTINATION: personal main account")
    dest_email=input("Destination email: ").strip()
    dest_password=getpass.getpass("Destination password (hidden): ")
    dest_auth=api.login(dest_email,dest_password)
    del dest_password
    if source_email.casefold()==dest_email.casefold():
        p.error("Source and destination must be different accounts")
    src=decode_home_layout(api.home_layout(source_auth["access_token"],args.source_profile,args.platform))
    dst=decode_home_layout(api.home_layout(dest_auth["access_token"],args.destination_profile,args.platform))
    result=plan(src,dst)
    out=Path(args.output)
    out.mkdir(parents=True,exist_ok=True)
    # Backup is local; permissions best-effort. Contains layout metadata.
    backup=out/("destination-"+args.platform+"-backup.json")
    backup.write_text(json.dumps(dst,indent=2),encoding="utf-8")
    backup.chmod(0o600)
    summary={k:v for k,v in result.items() if k!="preview"}
    (out/("comparison-"+args.platform+".json")).write_text(json.dumps(summary,indent=2),encoding="utf-8")
    if result["safe_to_apply"]:
        proposed=out/("proposed-"+args.platform+".json")
        proposed.write_text(json.dumps(result["preview"],indent=2),encoding="utf-8")
        proposed.chmod(0o600)
        print("All rows matched; proposed layout saved locally.")
    else:
        print("Transfer blocked: missing, duplicate, or unverified collection rows.")
    print("READ ONLY: neither Nuvio account was changed.")
    return 0 if result["safe_to_apply"] else 2

if __name__=="__main__":
    raise SystemExit(main())
