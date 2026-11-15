import unittest
from profile_resolver import resolve_profiles,verify_profile_membership

class ProfileResolverTests(unittest.TestCase):
    def test_explicit_ids(self):
        self.assertEqual(resolve_profiles([{"profile_id":2,"name":"Child"},{"profile_id":"1","name":"Main"}]),[(2,"Child"),(1,"Main")])
    def test_zero_based_index(self):
        self.assertEqual(resolve_profiles([{"profile_index":0},{"profile_index":1}]),[(1,"Profile 1"),(2,"Profile 2")])
    def test_one_based_index_with_four(self):
        self.assertEqual(resolve_profiles([{"profile_index":4},{"profile_index":2}]),[(4,"Profile 1"),(2,"Profile 2")])
    def test_ambiguous_indexes_rejected(self):
        with self.assertRaises(ValueError):resolve_profiles([{"profile_index":1},{"profile_index":2}])
    def test_no_list_position_guess(self):
        with self.assertRaises(ValueError):resolve_profiles([{"name":"Main"}])
    def test_duplicate_ids_rejected(self):
        with self.assertRaises(ValueError):resolve_profiles([{"profile_id":1},{"profile_id":1}])
    def test_out_of_range_rejected(self):
        with self.assertRaises(ValueError):resolve_profiles([{"profile_id":5}])
    def test_membership(self):
        class Client:
            def profiles(self,jwt):return [{"profile_index":0},{"profile_index":1}]
        verify_profile_membership(Client(),"fake",2)
        with self.assertRaises(ValueError):verify_profile_membership(Client(),"fake",3)
if __name__=="__main__":unittest.main()
