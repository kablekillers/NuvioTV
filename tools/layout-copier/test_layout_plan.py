import unittest
from layout_plan import plan

class LayoutPlanTests(unittest.TestCase):
    def test_catalog_order_and_hidden(self):
        a=lambda ident, order, enabled=True: dict(addon_id="a",type="movie",catalog_id=ident,order=order,enabled=enabled,is_collection=False)
        result=plan({"items":[a("b",0,False),a("a",1)]},{"items":[a("a",0),a("b",1),a("c",2)]})
        rows=result["preview"]["items"]
        self.assertEqual([x["catalog_id"] for x in rows],["b","a","c"])
        self.assertFalse(rows[0]["enabled"])
        self.assertTrue(result["safe_to_apply"])
    def test_missing_row_blocks_apply(self):
        a=lambda ident: dict(addon_id="a",type="movie",catalog_id=ident,order=0)
        self.assertFalse(plan({"items":[a("missing")]},{"items":[a("present")]})["safe_to_apply"])
    def test_collection_title_fallback_preserves_destination_id(self):
        a=lambda cid: dict(is_collection=True,collection_id=cid,custom_title="My Picks",order=0,enabled=False)
        r=plan({"items":[a("source")]},{"items":[a("destination")]})
        self.assertEqual(r["preview"]["items"][0]["collection_id"],"destination")
        self.assertTrue(r["safe_to_apply"])
if __name__=="__main__": unittest.main()
