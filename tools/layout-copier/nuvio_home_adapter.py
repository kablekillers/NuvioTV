"""Account-scoped Nuvio Home settings adapter.

Use only in a trusted, authenticated process. The write RPC changes ONLY
sync_push_home_catalog_settings for the selected destination profile/platform.
This module does not create or persist sessions or expose an HTTP endpoint.
"""
from layout_adapter import decode_home_layout
from nuvio_readonly import NuvioApiError

class HomeSettingsAdapter:
    def __init__(self, client, destination_jwt, profile_id, platform):
        if not isinstance(destination_jwt,str) or not destination_jwt.strip():
            raise ValueError("Destination access token required")
        if type(profile_id) is not int or profile_id <= 0:
            raise ValueError("Invalid profile ID")
        if platform not in ("tv","mobile"):
            raise ValueError("Invalid platform")
        self.client=client
        self.jwt=destination_jwt
        self.profile_id=profile_id
        self.platform=platform

    def pull(self):
        return decode_home_layout(self.client.home_layout(
            self.jwt,self.profile_id,self.platform))

    def push(self,layout):
        if not isinstance(layout,dict) or not isinstance(layout.get("items"),list):
            raise ValueError("Expected Home layout items")
        if not all(isinstance(item,dict) for item in layout["items"]):
            raise ValueError("Invalid Home layout row")
        # Never include collections, add-ons, profiles, or history RPCs.
        return self.client._post(
            "/rest/v1/rpc/sync_push_home_catalog_settings",
            {"p_profile_id":self.profile_id,"p_platform":self.platform,
             "p_settings_json":{"items":layout["items"]}},
            self.jwt)
