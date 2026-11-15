"""Strict profile RPC resolver. Never infer a profile ID from list position."""

def _positive_int(value, allow_zero=False):
    if type(value) is int:
        result=value
    elif isinstance(value,str) and value.isdecimal():
        result=int(value)
    else:
        return None
    return result if (result>=0 if allow_zero else result>0) else None

def resolve_profiles(profiles):
    """Return (RPC profile ID, label) pairs, or fail closed on ambiguity.

    Explicit unique profile_id fields take precedence. Otherwise accept
    profile_index only if the index base is provable from a zero or a four,
    and indices are contiguous and within Nuvio's four-profile range.
    """
    if not isinstance(profiles,list) or not profiles or len(profiles)>4:
        raise ValueError("Unrecognized profile list")
    if not all(isinstance(p,dict) for p in profiles):
        raise ValueError("Unrecognized profile entry")
    ids=[_positive_int(p.get("profile_id")) for p in profiles]
    if all(x is not None and x<=4 for x in ids) and len(set(ids))==len(ids):
        resolved=ids
    else:
        indexes=[_positive_int(p.get("profile_index"),allow_zero=True) for p in profiles]
        if any(x is None for x in indexes) or len(set(indexes))!=len(indexes):
            raise ValueError("Missing or duplicate profile identifiers")
        if 0 in indexes:
            resolved=[x+1 for x in indexes]
        elif 4 in indexes:
            resolved=indexes
        else:
            raise ValueError("Ambiguous profile index base; no positional guessing")
        if any(x<1 or x>4 for x in resolved):
            raise ValueError("Profile ID outside supported range")
    return [(id_,str(p.get("name") or p.get("profile_name") or "Profile "+str(i+1)))
            for i,(id_,p) in enumerate(zip(resolved,profiles))]

def verify_profile_membership(client,jwt,requested_id):
    ids={profile_id for profile_id,_ in resolve_profiles(client.profiles(jwt))}
    if requested_id not in ids:
        raise ValueError("Selected profile not found in authenticated account")
