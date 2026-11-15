import unittest
from nuvio_readonly import NuvioClient, NuvioApiError

class Response:
    def __init__(self,payload): self.payload=payload
    def raise_for_status(self): pass
    def json(self): return self.payload

class Transport:
    def __init__(self): self.calls=[]
    def post(self,url,**kw):
        self.calls.append((url,kw))
        if "grant_type=password" in url: return Response({"access_token":"test-jwt","refresh_token":"test-refresh"})
        if "sync_pull_profiles" in url: return Response([{"profile_id":1}])
        return Response({"items":[]})

class ReadonlyTests(unittest.TestCase):
    def setUp(self):
        self.transport=Transport()
        self.client=NuvioClient("publishable-test-key",transport=self.transport)
    def test_login_uses_password_grant(self):
        data=self.client.login("test@example.com","password")
        self.assertEqual(data["access_token"],"test-jwt")
        self.assertIn("grant_type=password",self.transport.calls[0][0])
    def test_profiles_uses_bearer(self):
        self.assertEqual(self.client.profiles("token"),[{"profile_id":1}])
        self.assertEqual(self.transport.calls[0][1]["headers"]["Authorization"],"Bearer token")
    def test_platform_scoped_pull(self):
        self.client.home_layout("token",1,"mobile")
        self.assertEqual(self.transport.calls[0][1]["json"]["p_platform"],"mobile")
    def test_rejects_invalid_platform(self):
        with self.assertRaises(ValueError): self.client.home_layout("token",1,"both")
    def test_rejects_non_https(self):
        with self.assertRaises(ValueError): NuvioClient("key",project_url="http://example.com")
if __name__=="__main__": unittest.main()
