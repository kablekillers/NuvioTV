import unittest
from layout_plan import plan

def cat(ident, order=0, enabled=True):
    return dict(addon_id="a",type="movie",catalog_id=ident,order=order,enabled=enabled,is_collection=False)

def coll(cid, title="My Picks"):
    return dict(is_collection=True,collection_id=cid,custom_title=title,order=0,enabled=False)

class LayoutPlanTests(unittest.TestCase):
    def test_catalog_order_and_hidden(self):
        result=plan({"items":[cat("b",0,False),cat("a",1)]},{"items":[cat("a"),cat("b",1),cat("c",2)]})
        rows=result["preview"]["items"]
        self.assertEqual([x["catalog_id"] for x in rows],["b","a","c"])
        self.assertFalse(rows[0]["enabled"])
        self.assertTrue(result["safe_to_apply"])
    def test_missing_row_blocks_apply(self):
        self.assertFalse(plan({"items":[cat("missing")]},{"items":[cat("present")]})["safe_to_apply"])
    def test_same_collection_id(self):
        r=plan({"items":[coll("same")]},{"items":[coll("same")]})
        self.assertTrue(r["safe_to_apply"])
    def test_collection_title_fallback_requires_verification(self):
        r=plan({"items":[coll("source")]},{"items":[coll("destination")]})
        self.assertEqual(r["preview"]["items"][0]["collection_id"],"destination")
        self.assertFalse(r["safe_to_apply"])
        self.assertEqual(len(r["title_fallbacks_needing_verification"]),1)
    def test_duplicate_source_id_blocks_apply(self):
        self.assertFalse(plan({"items":[cat("x"),cat("x")]},{"items":[cat("x") ]})["safe_to_apply"])
    def test_duplicate_destination_id_blocks_apply(self):
        self.assertFalse(plan({"items":[cat("x")]},{"items":[cat("x"),cat("x")]})["safe_to_apply"])
    def test_duplicate_collection_titles_block_apply(self):
        self.assertFalse(plan({"items":[coll("a")]},{"items":[coll("b"),coll("c")]})["safe_to_apply"])
    def test_destination_only_retained(self):
        r=plan({"items":[cat("a")]},{"items":[cat("a"),cat("b")]})
        self.assertEqual(r["destination_only"],1)
        self.assertEqual(len(r["preview"]["items"]),2)
    def test_invalid_payload_raises(self):
        with self.assertRaises(ValueError):
            plan({"items":[None]},{"items":[]})

    def test_empty_source_blocks_transfer(self):
        self.assertFalse(plan({"items":[]},{"items":[cat("x")]})["safe_to_apply"])
    def test_empty_destination_blocks_transfer(self):
        self.assertFalse(plan({"items":[cat("x")]},{"items":[]})["safe_to_apply"])
    def test_invalid_destination_only_row_blocks_transfer(self):
        r=plan({"items":[cat("x")]},{"items":[cat("x"),{"order":1}]})
        self.assertFalse(r["safe_to_apply"])
        self.assertTrue(r["invalid_destination"])
    def test_duplicate_destination_only_rows_block_transfer(self):
        r=plan({"items":[cat("x")]},{"items":[cat("x"),cat("y"),cat("y")]})
        self.assertFalse(r["safe_to_apply"])
        self.assertTrue(r["duplicate_destination"])
if __name__=="__main__":
    unittest.main()
