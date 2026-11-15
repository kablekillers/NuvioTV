"""Pure, offline Nuvio Home layout transfer planner. No credentials or network writes."""
from copy import deepcopy

def identity(item):
    if item.get("is_collection"):
        return ("collection", str(item.get("collection_id") or "").strip())
    return ("catalog", str(item.get("addon_id") or "").strip(),
            str(item.get("type") or "").strip(), str(item.get("catalog_id") or "").strip())

def plan(source, destination):
    """Return a non-destructive preview. Never insert source-only catalog IDs."""
    src = sorted(source["items"], key=lambda x: int(x.get("order", 0)))
    dst = sorted(destination["items"], key=lambda x: int(x.get("order", 0)))
    used = set()
    ordered = []
    unmatched = []
    ambiguous = []
    for s in src:
        key = identity(s)
        matches = [i for i, d in enumerate(dst) if i not in used and identity(d) == key and key[-1]]
        if not matches and s.get("is_collection"):
            # Collection UUIDs can differ between accounts; exact title is only a fallback.
            title = str(s.get("custom_title") or "").strip().casefold()
            if title:
                matches = [i for i, d in enumerate(dst) if i not in used and d.get("is_collection")
                           and str(d.get("custom_title") or "").strip().casefold() == title]
        if len(matches) != 1:
            (ambiguous if len(matches) > 1 else unmatched).append(deepcopy(s))
            continue
        i = matches[0]
        used.add(i)
        row = deepcopy(dst[i])  # preserve destination IDs and all non-layout fields
        row["enabled"] = bool(s.get("enabled", True))
        ordered.append(row)
    # Retain destination-only rows and their visibility, rather than silently deleting them.
    ordered.extend(deepcopy(d) for i, d in enumerate(dst) if i not in used)
    for i, row in enumerate(ordered):
        row["order"] = i
    return {"preview": {"items": ordered}, "matched": len(used),
            "unmatched_source": unmatched, "ambiguous_source": ambiguous,
            "destination_only": len(dst) - len(used),
            "safe_to_apply": not unmatched and not ambiguous}

# This module intentionally has no push function. A future authenticated
# backend must enforce preview, explicit confirmation, backup and rollback.
