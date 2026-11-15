import unittest
from transfer_engine import transfer, TransferError

def row(id,order=0):
    return {"addon_id":"a","type":"movie","catalog_id":id,"order":order,"enabled":True}
class Fake:
    def __init__(self,layout,corrupt=False,fail=False):
        self.layout=layout
        self.corrupt=corrupt
        self.fail=fail
        self.writes=0
    def pull(self): return {"items":[dict(x) for x in self.layout["items"]]}
    def push(self,payload):
        self.writes+=1
        if self.fail and self.writes==1: raise RuntimeError("network failure")
        self.layout={"items":[dict(x) for x in payload["items"]]}
        if self.corrupt and self.writes==1: self.layout["items"][0]["enabled"]=False

class TransferTests(unittest.TestCase):
    def setUp(self):
        self.src={"items":[row("b",0),row("a",1)]}
        self.dst={"items":[row("a",0),row("b",1)]}
    def test_confirmation_required(self):
        f=Fake(self.dst)
        with self.assertRaises(TransferError): transfer(self.src,f,"yes")
        self.assertEqual(f.writes,0)
    def test_success(self):
        f=Fake(self.dst)
        result=transfer(self.src,f,"COPY HOME LAYOUT")
        self.assertEqual(result["status"],"applied")
        self.assertEqual([r["catalog_id"] for r in f.pull()["items"]],["b","a"])
    def test_unmatched_no_write(self):
        f=Fake(self.dst)
        with self.assertRaises(TransferError):
            transfer({"items":[row("missing")]},f,"COPY HOME LAYOUT")
        self.assertEqual(f.writes,0)
    def test_readback_mismatch_rolls_back(self):
        f=Fake(self.dst,corrupt=True)
        with self.assertRaises(TransferError): transfer(self.src,f,"COPY HOME LAYOUT")
        self.assertEqual(f.pull(),self.dst)
        self.assertEqual(f.writes,2)
    def test_write_failure_rolls_back(self):
        f=Fake(self.dst,fail=True)
        with self.assertRaises(TransferError): transfer(self.src,f,"COPY HOME LAYOUT")
        self.assertEqual(f.pull(),self.dst)
if __name__=="__main__": unittest.main()
