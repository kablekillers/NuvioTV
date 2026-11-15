import tempfile
import unittest
from pathlib import Path
from guarded_apply import apply_with_durable_backup
from preview_receipt import preview_receipt
from transfer_engine import TransferError

def row(name,order):
    return {"addon_id":"a","type":"movie","catalog_id":name,"order":order,"enabled":True}
class Fake:
    platform="tv"
    def __init__(self):
        self.items={"items":[row("a",0),row("b",1)]}
        self.writes=0
    def pull(self):
        from copy import deepcopy
        return deepcopy(self.items)
    def push(self,data):
        from copy import deepcopy
        self.items=deepcopy(data)
        self.writes+=1

class DurableTests(unittest.TestCase):
    def setUp(self):
        self.src={"items":[row("b",0),row("a",1)]}
        self.dst=Fake()
        self.receipt=preview_receipt(self.src,self.dst.pull(),"tv",1,1)
    def test_backup_exists_before_success(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"backup.json"
            result=apply_with_durable_backup(self.src,self.dst,self.receipt,
                "COPY HOME LAYOUT",1,1,path)
            self.assertEqual(result["status"],"applied")
            self.assertTrue(path.is_file())
            self.assertEqual(self.dst.writes,1)
    def test_existing_backup_blocks_write(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"backup.json"
            path.write_text("old")
            with self.assertRaises(TransferError):
                apply_with_durable_backup(self.src,self.dst,self.receipt,
                    "COPY HOME LAYOUT",1,1,path)
            self.assertEqual(path.read_text(),"old")
            self.assertEqual(self.dst.writes,0)
    def test_stale_preview_blocks_backup_and_write(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"backup.json"
            self.dst.items["items"][0]["enabled"]=False
            with self.assertRaises(TransferError):
                apply_with_durable_backup(self.src,self.dst,self.receipt,
                    "COPY HOME LAYOUT",1,1,path)
            self.assertFalse(path.exists())
            self.assertEqual(self.dst.writes,0)
if __name__=="__main__": unittest.main()
