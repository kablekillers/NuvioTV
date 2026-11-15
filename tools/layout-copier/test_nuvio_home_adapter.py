import unittest
from nuvio_home_adapter import HomeSettingsAdapter

class FakeClient:
    def __init__(self): self.calls=[]
    def home_layout(self,jwt,profile,platform):
        self.calls.append(("pull",jwt,profile,platform))
        return [{"settings_json":{"items":[{"addon_id":"a","catalog_id":"c"}]}}]
    def _post(self,path,payload,jwt):
        self.calls.append(("post",path,payload,jwt))
        return None

class AdapterTests(unittest.TestCase):
    def test_pull_decodes_rpc_wrapper(self):
        c=FakeClient()
        a=HomeSettingsAdapter(c,"dest-token",1,"tv")
        self.assertEqual(len(a.pull()["items"]),1)
        self.assertEqual(c.calls[0],("pull","dest-token",1,"tv"))
    def test_push_only_home_settings(self):
        c=FakeClient()
        a=HomeSettingsAdapter(c,"dest-token",2,"mobile")
        a.push({"items":[{"catalog_id":"c","enabled":False}]})
        _,path,payload,jwt=c.calls[0]
        self.assertEqual(path,"/rest/v1/rpc/sync_push_home_catalog_settings")
        self.assertEqual(jwt,"dest-token")
        self.assertEqual(payload["p_profile_id"],2)
        self.assertEqual(payload["p_platform"],"mobile")
        self.assertEqual(set(payload),{"p_profile_id","p_platform","p_settings_json"})
    def test_invalid_rows_never_write(self):
        c=FakeClient()
        a=HomeSettingsAdapter(c,"dest-token",1,"tv")
        with self.assertRaises(ValueError): a.push({"items":[None]})
        self.assertFalse(c.calls)
    def test_invalid_platform(self):
        with self.assertRaises(ValueError): HomeSettingsAdapter(FakeClient(),"token",1,"both")

if __name__=="__main__": unittest.main()
