import unittest
from copy import deepcopy
from preview_receipt import preview_receipt, apply_with_receipt
from transfer_engine import TransferError

def row(ident,order):
    return {"addon_id":"a","type":"movie","catalog_id":ident,"order":order,"enabled":True}
class Adapter:
    platform="tv"
    def __init__(self,items): self.layout={"items":deepcopy(items)};self.writes=0
    def pull(self): return deepcopy(self.layout)
    def push(self,payload): self.layout=deepcopy(payload);self.writes+=1

class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.source={"items":[row("b",0),row("a",1)]}
        self.dest=Adapter([row("a",0),row("b",1)])
    def receipt(self):
        return preview_receipt(self.source,self.dest.pull(),"tv",1,1)
    def test_approved_preview(self):
        receipt=self.receipt()
        result=apply_with_receipt(self.source,self.dest,receipt,"COPY HOME LAYOUT",1,1)
        self.assertEqual(result["status"],"applied")
        self.assertEqual(self.dest.writes,1)
    def test_stale_destination_blocks(self):
        receipt=self.receipt()
        self.dest.layout["items"][0]["enabled"]=False
        with self.assertRaises(TransferError):
            apply_with_receipt(self.source,self.dest,receipt,"COPY HOME LAYOUT",1,1)
        self.assertEqual(self.dest.writes,0)
    def test_changed_source_blocks(self):
        receipt=self.receipt()
        self.source["items"][0]["enabled"]=False
        with self.assertRaises(TransferError):
            apply_with_receipt(self.source,self.dest,receipt,"COPY HOME LAYOUT",1,1)
        self.assertEqual(self.dest.writes,0)
    def test_wrong_profile_blocks(self):
        with self.assertRaises(TransferError):
            apply_with_receipt(self.source,self.dest,self.receipt(),"COPY HOME LAYOUT",1,2)
        self.assertEqual(self.dest.writes,0)
if __name__=="__main__": unittest.main()
