"""Explicit preview receipt for a future secure two-account interface.

Receipt binds a source and destination Home layout to one profile/platform
comparison. A changed layout invalidates the receipt before any write.
"""
import hashlib
import hmac
import json
from layout_plan import plan
from transfer_engine import TransferError, transfer

def digest(value):
    canonical=json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

def preview_receipt(source, destination, platform, source_profile, destination_profile):
    if platform not in ("tv","mobile"):
        raise ValueError("Invalid platform")
    if type(source_profile) is not int or type(destination_profile) is not int:
        raise ValueError("Invalid profile")
    proposal=plan(source,destination)
    return {"platform":platform,"source_profile":source_profile,
            "destination_profile":destination_profile,
            "source_digest":digest(source),"destination_digest":digest(destination),
            "matched":proposal["matched"],"destination_only":proposal["destination_only"],
            "safe_to_apply":proposal["safe_to_apply"],
            "unmatched_count":len(proposal["unmatched_source"]),
            "ambiguous_count":len(proposal["ambiguous_source"]),
            "unverified_collection_count":len(proposal["title_fallbacks_needing_verification"])}

def apply_with_receipt(source, destination_adapter, receipt, confirmation,
                       source_profile, destination_profile):
    if not isinstance(receipt,dict):
        raise TransferError("Missing preview receipt")
    if receipt.get("platform")!=destination_adapter.platform or (
        receipt.get("source_profile")!=source_profile or
        receipt.get("destination_profile")!=destination_profile):
        raise TransferError("Preview targets a different profile or platform")
    fresh=preview_receipt(source,destination_adapter.pull(),destination_adapter.platform,
                          source_profile,destination_profile)
    if not fresh["safe_to_apply"] or not hmac.compare_digest(digest(fresh),digest(receipt)):
        raise TransferError("Preview is stale or unsafe; review again")
    return transfer(source,destination_adapter,confirmation)
