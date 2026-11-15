"""Pure offline Nuvio Home layout planner. Never contacts Nuvio or writes accounts."""
from collections import Counter
from copy import deepcopy

def identity(item):
    if item.get("is_collection"):
        cid = str(item.get("collection_id") or "").strip()
        return ("collection", cid) if cid else None
    addon = str(item.get("addon_id") or "").strip()
    kind = str(item.get("type") or "").strip()
    catalog = str(item.get("catalog_id") or "").strip()
    return ("catalog", addon, kind, catalog) if addon and catalog else None

def _rows(payload):
    if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
        raise ValueError("Home layout requires an items array")
    if not all(isinstance(x, dict) for x in payload["items"]):
        raise ValueError("Home layout contains an invalid row")
    return sorted(payload["items"], key=lambda x: int(x.get("order", 0)))

def plan(source, destination):
    """Preview matched rows only. Preserve destination IDs and destination-only rows.

    Reject duplicate source/destination identifiers, ambiguous collection titles,
    and missing source rows. Never infer that a collection with the same title
    has the same content: title fallback must be manually verified before use.
    """
    src = _rows(source)
    dst = _rows(destination)
    src_counts = Counter(identity(x) for x in src if identity(x))
    dst_counts = Counter(identity(x) for x in dst if identity(x))
    used, ordered, unmatched, ambiguous, title_fallbacks = set(), [], [], [], []
    # Empty RPC responses can mean sync failure or missing settings. Never
    # interpret them as permission to replace a real Home layout.
    empty_layout = not src or not dst
    invalid_destination = any(identity(x) is None for x in dst)
    duplicate_destination = any(count > 1 for count in dst_counts.values())
    for s in src:
        key = identity(s)
        if key is None or src_counts[key] > 1 or dst_counts[key] > 1:
            ambiguous.append(deepcopy(s))
            continue
        matches = [i for i, d in enumerate(dst) if i not in used and identity(d) == key]
        title_fallback = False
        if not matches and s.get("is_collection"):
            title = str(s.get("custom_title") or "").strip().casefold()
            if title:
                matches = [i for i, d in enumerate(dst) if i not in used
                           and d.get("is_collection")
                           and str(d.get("custom_title") or "").strip().casefold() == title]
                title_fallback = bool(matches)
        if len(matches) != 1:
            (ambiguous if len(matches) > 1 else unmatched).append(deepcopy(s))
            continue
        i = matches[0]
        used.add(i)
        row = deepcopy(dst[i])
        row["enabled"] = bool(s.get("enabled", True))
        ordered.append(row)
        if title_fallback:
            title_fallbacks.append({"source_collection_id": s.get("collection_id"),
                                    "destination_collection_id": dst[i].get("collection_id"),
                                    "title": s.get("custom_title")})
    ordered.extend(deepcopy(d) for i, d in enumerate(dst) if i not in used)
    for i, row in enumerate(ordered):
        row["order"] = i
    return {"preview": {"items": ordered}, "matched": len(used),
            "unmatched_source": unmatched, "ambiguous_source": ambiguous,
            "title_fallbacks_needing_verification": title_fallbacks,
            "destination_only": len(dst) - len(used),
            "safe_to_apply": not (empty_layout or invalid_destination or duplicate_destination
                                  or unmatched or ambiguous or title_fallbacks),
            "empty_layout": empty_layout,
            "invalid_destination": invalid_destination,
            "duplicate_destination": duplicate_destination}
