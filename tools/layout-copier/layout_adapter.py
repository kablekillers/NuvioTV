"""Strict adapter for Nuvio sync_pull_home_catalog_settings RPC responses."""
import json

class LayoutFormatError(ValueError):
    pass

def decode_home_layout(raw):
    """Accept upstream RPC list/object/JSON-string envelopes, fail closed."""
    if isinstance(raw,str):
        try: raw=json.loads(raw)
        except (ValueError,TypeError) as exc: raise LayoutFormatError("Invalid JSON response") from exc
    if isinstance(raw,list):
        if len(raw)!=1 or not isinstance(raw[0],dict):
            raise LayoutFormatError("Expected exactly one settings record")
        raw=raw[0]
    if not isinstance(raw,dict):
        raise LayoutFormatError("Expected settings object")
    settings=raw.get("settings_json",raw)
    if isinstance(settings,str):
        try: settings=json.loads(settings)
        except (ValueError,TypeError) as exc: raise LayoutFormatError("Invalid settings JSON") from exc
    if not isinstance(settings,dict) or not isinstance(settings.get("items"),list):
        raise LayoutFormatError("Missing Home layout items array")
    if not all(isinstance(row,dict) for row in settings["items"]):
        raise LayoutFormatError("Invalid Home layout row")
    return {"items":settings["items"]}
