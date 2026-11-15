"""Nuvio API transport for a future two-account copier.

This module deliberately exposes read-only operations. No password storage,
no logging of tokens, and no RPC writes. Do not deploy publicly without
an authenticated server session, CSRF protection, and rate limiting.
"""
import os
import requests

DEFAULT_URL = "https://dpyhjjcoabcglfmgecug.supabase.co"

class NuvioApiError(RuntimeError):
    pass

class NuvioClient:
    def __init__(self, anon_key, project_url=None, transport=None):
        if not anon_key or not isinstance(anon_key,str):
            raise ValueError("A publishable Nuvio anon key is required")
        self.anon_key=anon_key
        self.base=(project_url or os.getenv("NUVIO_PROJECT_URL") or DEFAULT_URL).rstrip("/")
        if not self.base.startswith("https://"):
            raise ValueError("HTTPS Nuvio project URL required")
        self.transport=transport or requests

    def _post(self,path,payload,jwt=None):
        headers={"apikey":self.anon_key,"Content-Type":"application/json"}
        if jwt:
            headers["Authorization"]="Bearer "+jwt
        try:
            response=self.transport.post(self.base+path,json=payload,headers=headers,timeout=20)
            response.raise_for_status()
            return response.json()
        except (requests.RequestException,ValueError) as exc:
            # Do not echo response bodies: they may contain sensitive information.
            raise NuvioApiError("Nuvio request failed") from exc

    def login(self,email,password):
        if not email or not password:
            raise ValueError("Email and password required")
        response=self._post("/auth/v1/token?grant_type=password",
                            {"email":email.strip(),"password":password})
        if not isinstance(response,dict) or not response.get("access_token"):
            raise NuvioApiError("Nuvio authentication failed")
        return response

    def refresh(self,refresh_token):
        if not refresh_token:
            raise ValueError("Refresh token required")
        return self._post("/auth/v1/token?grant_type=refresh_token",
                          {"refresh_token":refresh_token})

    def profiles(self,jwt):
        if not jwt:
            raise ValueError("Access token required")
        data=self._post("/rest/v1/rpc/sync_pull_profiles",{},jwt)
        if not isinstance(data,list):
            raise NuvioApiError("Unexpected profile response")
        return data

    def home_layout(self,jwt,profile_id,platform):
        if platform not in ("tv","mobile"):
            raise ValueError("Platform must be tv or mobile")
        if type(profile_id) is not int or profile_id <= 0:
            raise ValueError("Profile ID must be a positive integer")
        data=self._post("/rest/v1/rpc/sync_pull_home_catalog_settings",
                        {"p_profile_id":profile_id,"p_platform":platform},jwt)
        # RPC response can be a wrapped settings_json object or JSON string;
        # defer parsing to a verified adapter rather than guessing its schema.
        return data
