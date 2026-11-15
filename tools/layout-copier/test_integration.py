"""End-to-end simulated Nuvio RPC tests: no external network or credentials."""
import unittest
from copy import deepcopy
from nuvio_readonly import NuvioClient
from nuvio_home_adapter import HomeSettingsAdapter
from layout_adapter import decode_home_layout
from transfer_engine import transfer, TransferError

def row(name,order):
    return {"addon_id":"addon","type":"movie","catalog_id":name,
            "order":order,"enabled":True,"is_collection":False}

class FakeResponse:
    def __init__(self,data): self.data=data
    def raise_for_status(self): pass
    def json(self): return deepcopy(self.data)

class FakeNuvio:
    def __init__(self,fail_verification=False):
        self.accounts={
            "source-token":{"tv":{"items":[row("b",0),row("a",1)]},
                            "mobile":{"items":[row("a",0),row("b",1)]}},
            "destination-token":{"tv":{"items":[row("a",0),row("b",1)]},
                                 "mobile":{"items":[row("b",0),row("a",1)]}}}
        self.calls=[]
        self.fail_verification=fail_verification
        self.corrupted=False
    def post(self,url,json,headers,timeout):
        token=headers["Authorization"].removeprefix("Bearer ")
        platform=json.get("p_platform")
        self.calls.append((token,url,platform))
        if url.endswith("/sync_pull_home_catalog_settings"):
            return FakeResponse([{"settings_json":self.accounts[token][platform]}])
        if url.endswith("/sync_push_home_catalog_settings"):
            if token!="destination-token": raise AssertionError("Source account must never be written")
            self.accounts[token][platform]=deepcopy(json["p_settings_json"])
            if self.fail_verification and not self.corrupted:
                self.accounts[token][platform]["items"][0]["enabled"]=False
                self.corrupted=True
            return FakeResponse(None)
        raise AssertionError("Unexpected RPC: "+url)

class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self.fake=FakeNuvio()
        self.api=NuvioClient("public-test-key",transport=self.fake)
    def test_tv_transfer_does_not_change_mobile_or_source(self):
        before_source=deepcopy(self.fake.accounts["source-token"])
        before_mobile=deepcopy(self.fake.accounts["destination-token"]["mobile"])
        source=decode_home_layout(self.api.home_layout("source-token",1,"tv"))
        dest=HomeSettingsAdapter(self.api,"destination-token",1,"tv")
        result=transfer(source,dest,"COPY HOME LAYOUT")
        self.assertEqual(result["status"],"applied")
        self.assertEqual([r["catalog_id"] for r in dest.pull()["items"]],["b","a"])
        self.assertEqual(self.fake.accounts["source-token"],before_source)
        self.assertEqual(self.fake.accounts["destination-token"]["mobile"],before_mobile)
        writes=[c for c in self.fake.calls if "sync_push" in c[1]]
        self.assertEqual(len(writes),1)
        self.assertEqual(writes[0][0],"destination-token")
    def test_readback_failure_restores_destination(self):
        self.fake.fail_verification=True
        before=deepcopy(self.fake.accounts["destination-token"]["tv"])
        source=decode_home_layout(self.api.home_layout("source-token",1,"tv"))
        dest=HomeSettingsAdapter(self.api,"destination-token",1,"tv")
        with self.assertRaises(TransferError):
            transfer(source,dest,"COPY HOME LAYOUT")
        self.assertEqual(dest.pull(),before)
        self.assertEqual(len([c for c in self.fake.calls if "sync_push" in c[1]]),2)
    def test_confirmation_blocks_all_writes(self):
        source=decode_home_layout(self.api.home_layout("source-token",1,"tv"))
        dest=HomeSettingsAdapter(self.api,"destination-token",1,"tv")
        with self.assertRaises(TransferError): transfer(source,dest,"proceed")
        self.assertFalse([c for c in self.fake.calls if "sync_push" in c[1]])

if __name__=="__main__": unittest.main()
