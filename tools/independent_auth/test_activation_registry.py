"""Run: python -m unittest discover -s tools/independent_auth -p 'test_*.py'"""
import sqlite3
import unittest

from activation_registry import ActivationRegistry


class ActivationRegistryTests(unittest.TestCase):
    def setUp(self):
        self.registry = ActivationRegistry(sqlite3.connect(":memory:"))
        self.challenge = self.registry.create(now=1000)

    def test_owner_approval_then_single_use(self):
        c = self.challenge
        self.assertEqual(self.registry.status(c.challenge_id, c.poll_secret, now=1001), "pending")
        self.assertFalse(self.registry.consume_approved(c.challenge_id, c.poll_secret, "tv1", now=1001))
        self.assertTrue(self.registry.approve(c.pairing_code, "owner1", now=1002))
        self.assertEqual(self.registry.status(c.challenge_id, c.poll_secret, now=1003), "approved")
        self.assertTrue(self.registry.consume_approved(c.challenge_id, c.poll_secret, "tv1", now=1004))
        self.assertFalse(self.registry.consume_approved(c.challenge_id, c.poll_secret, "tv2", now=1004))
        self.assertEqual(self.registry.status(c.challenge_id, c.poll_secret, now=1004), "consumed")

    def test_wrong_poll_secret(self):
        c = self.challenge
        self.assertEqual(self.registry.status(c.challenge_id, "wrong", now=1001), "invalid")
        self.assertFalse(self.registry.consume_approved(c.challenge_id, "wrong", "tv1", now=1001))

    def test_expiry(self):
        c = self.challenge
        self.assertEqual(self.registry.status(c.challenge_id, c.poll_secret, now=1300), "expired")
        self.assertFalse(self.registry.approve(c.pairing_code, "owner1", now=1300))

    def test_denial(self):
        c = self.challenge
        self.assertTrue(self.registry.deny(c.pairing_code, now=1001))
        self.assertFalse(self.registry.approve(c.pairing_code, "owner1", now=1002))
        self.assertEqual(self.registry.status(c.challenge_id, c.poll_secret, now=1002), "denied")

    def test_owner_required(self):
        with self.assertRaises(ValueError):
            self.registry.approve(self.challenge.pairing_code, "", now=1001)


if __name__ == "__main__":
    unittest.main()
