"""Core state machine for owner-approved Kable Killers device activation.

No web routes or authentication are exposed by this module. The eventual API
must authenticate the owner (including MFA) BEFORE calling approve/deny.
Never send the poll secret to the owner dashboard or the admin session to a TV.
"""
from __future__ import annotations

import hashlib
import secrets
import sqlite3
import time
from dataclasses import dataclass

TTL_SECONDS = 300


def _digest(secret: str) -> str:
    return hashlib.sha256(secret.encode("ascii")).hexdigest()


@dataclass(frozen=True)
class Challenge:
    challenge_id: str
    poll_secret: str
    pairing_code: str
    expires_at: int


class ActivationRegistry:
    def __init__(self, connection: sqlite3.Connection):
        self.db = connection
        self.db.execute(
            """CREATE TABLE IF NOT EXISTS activation_challenges (
                id TEXT PRIMARY KEY,
                secret_hash TEXT NOT NULL,
                code TEXT NOT NULL UNIQUE,
                expires_at INTEGER NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('pending','approved','denied','consumed')),
                approved_by TEXT,
                device_id TEXT
            )"""
        )
        self.db.commit()

    def create(self, now: int | None = None) -> Challenge:
        now = int(time.time()) if now is None else now
        challenge_id = secrets.token_urlsafe(24)
        poll_secret = secrets.token_urlsafe(32)
        # Human-readable code is not an authentication secret; owner approval
        # is required and the TV must possess the separate private poll secret.
        for _ in range(10):
            code = f"{secrets.randbelow(1_000_000):06d}"
            try:
                with self.db:
                    self.db.execute(
                        "INSERT INTO activation_challenges(id,secret_hash,code,expires_at,state) VALUES(?,?,?,?,?)",
                        (challenge_id, _digest(poll_secret), code, now + TTL_SECONDS, "pending"),
                    )
                return Challenge(challenge_id, poll_secret, code, now + TTL_SECONDS)
            except sqlite3.IntegrityError:
                continue
        raise RuntimeError("Could not allocate unique pairing code")

    def approve(self, code: str, owner_id: str, now: int | None = None) -> bool:
        """Caller must be an MFA-authenticated owner; enforced by HTTP layer."""
        if not owner_id:
            raise ValueError("Authenticated owner identity required")
        now = int(time.time()) if now is None else now
        with self.db:
            cursor = self.db.execute(
                """UPDATE activation_challenges SET state='approved', approved_by=?
                   WHERE code=? AND state='pending' AND expires_at>?""",
                (owner_id, code, now),
            )
        return cursor.rowcount == 1

    def deny(self, code: str, now: int | None = None) -> bool:
        now = int(time.time()) if now is None else now
        with self.db:
            cursor = self.db.execute(
                """UPDATE activation_challenges SET state='denied'
                   WHERE code=? AND state='pending' AND expires_at>?""",
                (code, now),
            )
        return cursor.rowcount == 1

    def status(self, challenge_id: str, poll_secret: str, now: int | None = None) -> str:
        now = int(time.time()) if now is None else now
        row = self.db.execute(
            "SELECT secret_hash,expires_at,state FROM activation_challenges WHERE id=?",
            (challenge_id,),
        ).fetchone()
        if row is None or not secrets.compare_digest(row[0], _digest(poll_secret)):
            return "invalid"
        if row[1] <= now:
            return "expired"
        return row[2]

    def consume_approved(self, challenge_id: str, poll_secret: str, device_id: str,
                         now: int | None = None) -> bool:
        """One-time transition. Credential issuance is NOT implemented here."""
        if not device_id:
            raise ValueError("Device identity required")
        now = int(time.time()) if now is None else now
        with self.db:
            cursor = self.db.execute(
                """UPDATE activation_challenges SET state='consumed',device_id=?
                   WHERE id=? AND secret_hash=? AND state='approved' AND expires_at>?""",
                (device_id, challenge_id, _digest(poll_secret), now),
            )
        return cursor.rowcount == 1
