import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import apply_live

def row(name, order):
    return {"addon_id":"a","type":"movie","catalog_id":name,"order":order,"enabled":True}

class FakeClient:
    writes = []
    accounts = {}
    def __init__(self, key): self.key = key
    def login(self, email, password):
        return {"access_token": "source" if email == "source@example.com" else "destination"}
    def home_layout(self, jwt, profile, platform):
        return {"settings_json": self.accounts[jwt][platform]}
    def _post(self, path, payload, jwt):
        assert jwt == "destination"
        assert path.endswith("/sync_push_home_catalog_settings")
        self.writes.append((path,payload,jwt))
        self.accounts[jwt][payload["p_platform"]] = payload["p_settings_json"]

class LiveCliTests(unittest.TestCase):
    def setUp(self):
        FakeClient.accounts = {
            "source":{"tv":{"items":[row("b",0),row("a",1)]}},
            "destination":{"tv":{"items":[row("a",0),row("b",1)]}}}
        FakeClient.writes = []

    def run_cli(self, confirmation):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(sys, "argv", ["apply_live.py","--platform","tv","--backup-dir",folder]), patch.dict(os.environ, {"NUVIO_ANON_KEY":"test"}), patch.object(apply_live, "NuvioClient", FakeClient), patch("builtins.input", side_effect=["source@example.com","destination@example.com",confirmation]), patch.object(apply_live.getpass, "getpass", side_effect=["pass1","pass2"]), patch("sys.stdout", new_callable=io.StringIO):
                status = apply_live.main()
            return status, len(list(Path(folder).glob("*.json")))

    def test_cancelled_never_writes(self):
        status, backups = self.run_cli("no")
        self.assertEqual(status,1)
        self.assertEqual(backups,0)
        self.assertEqual(FakeClient.writes,[])

    def test_confirmed_writes_only_destination_and_saves_backup(self):
        status, backups = self.run_cli("COPY HOME LAYOUT")
        self.assertEqual(status,0)
        self.assertEqual(backups,1)
        self.assertEqual(len(FakeClient.writes),1)
        self.assertEqual(FakeClient.writes[0][2],"destination")
        self.assertEqual([r["catalog_id"] for r in FakeClient.accounts["source"]["tv"]["items"]],["b","a"])

if __name__ == "__main__":
    unittest.main()
