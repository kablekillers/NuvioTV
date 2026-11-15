"""Read-only, two-account Nuvio Home comparison for TV, Mobile, or both.

Requires NUVIO_ANON_KEY (publishable key). Prompts for both passwords without
echoing them. Produces local backups and proposed layouts; NEVER pushes to Nuvio.
"""
import argparse
import getpass
import json
import os
from pathlib import Path
from nuvio_readonly import NuvioClient
from layout_adapter import decode_home_layout
from layout_plan import plan

def save_private(path, payload):
    path.parent.mkdir(parents=True,exist_ok=True)
    # Exclusive creation avoids overwriting an existing backup.
    fd=os.open(str(path),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,"w",encoding="utf-8") as stream:
        json.dump(payload,stream,indent=2)

def compare_platform(api, source_token, dest_token, source_profile, dest_profile, platform, out):
    src=decode_home_layout(api.home_layout(source_token,source_profile,platform))
    dst=decode_home_layout(api.home_layout(dest_token,dest_profile,platform))
    result=plan(src,dst)
    save_private(out/("destination-"+platform+"-backup.json"),dst)
    summary={k:v for k,v in result.items() if k!="preview"}
    save_private(out/("comparison-"+platform+".json"),summary)
    if result["safe_to_apply"]:
        save_private(out/("proposed-"+platform+".json"),result["preview"])
        print(platform.upper()+": all rows matched; proposal saved locally.")
    else:
        print(platform.upper()+": BLOCKED; review unmatched/ambiguous rows in comparison.")
    return result["safe_to_apply"]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--platform",choices=("tv","mobile","both"),default="both")
    p.add_argument("--source-profile",type=int,choices=(1,2,3,4),default=1)
    p.add_argument("--destination-profile",type=int,choices=(1,2,3,4),default=1)
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
    if source_email.casefold()==dest_email.casefold():
        p.error("Source and destination must be different accounts")
    dest_password=getpass.getpass("Destination password (hidden): ")
    dest_auth=api.login(dest_email,dest_password)
    del dest_password
    # Validate profile existence before requesting layouts.
    for label,auth,profile in (("source",source_auth,args.source_profile),
                               ("destination",dest_auth,args.destination_profile)):
        profiles=api.profiles(auth["access_token"])
        if not profiles:
            raise ValueError(label+" account has no profiles; refusing to proceed")
        # Profile IDs vary in upstream responses. Don't assume a list index is an ID.
        ids={int(row["profile_id"]) for row in profiles
             if isinstance(row,dict) and str(row.get("profile_id","")).isdigit()}
        if ids and profile not in ids:
            raise ValueError(label+" profile ID is not present in account")
    out=Path(args.output)
    if out.exists() and any(out.iterdir()):
        p.error("Output directory must be empty to avoid overwriting backups")
    platforms=("tv","mobile") if args.platform=="both" else (args.platform,)
    outcomes=[compare_platform(api,source_auth["access_token"],dest_auth["access_token"],
                               args.source_profile,args.destination_profile,platform,out)
              for platform in platforms]
    print("READ ONLY: neither account was modified.")
    return 0 if all(outcomes) else 2

if __name__=="__main__":
    raise SystemExit(main())
