import unittest
from layout_adapter import decode_home_layout, LayoutFormatError

class AdapterTests(unittest.TestCase):
    def test_direct(self):
        self.assertEqual(decode_home_layout({"items":[{"order":0}]}),{"items":[{"order":0}]})
    def test_list_wrapper(self):
        self.assertEqual(decode_home_layout([{"settings_json":{"items":[]}}]),{"items":[]})
    def test_json_string_wrapper(self):
        self.assertEqual(decode_home_layout([{"settings_json":'{"items":[]}'}]),{"items":[]})
    def test_invalid_response_fails_closed(self):
        for value in ([],{},[{},{}],{"settings_json":"invalid"},{"items":[None]}):
            with self.subTest(value=value):
                with self.assertRaises(LayoutFormatError): decode_home_layout(value)
if __name__=="__main__": unittest.main()
