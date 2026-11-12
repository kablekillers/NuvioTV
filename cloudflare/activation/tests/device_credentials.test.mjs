import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { hashToken, makeDeviceToken, issueDeviceCredential, authenticateDevice, revokeDevice } from "../src/device_credentials.js";

globalThis.crypto ??= webcrypto;

test("device token is 256-bit random hex and hashes consistently", async () => {
  const a = makeDeviceToken();
  const b = makeDeviceToken();
  assert.match(a, /^[a-f0-9]{64}$/);
  assert.notEqual(a, b);
  assert.equal(await hashToken(a), await hashToken(a));
  assert.notEqual(await hashToken(a), a);
});

test("invalid activation credentials are rejected before DB access", async () => {
  const db = { batch: () => { throw new Error("unexpected DB call"); } };
  await assert.rejects(() => issueDeviceCredential(db, {
    challengeId: "invalid", challengeSecretHash: "x"
  }), /Invalid activation credentials/);
});

test("no credential is returned if an activation was already consumed", async () => {
  const db = {
    prepare(sql) {
      return { bind() { return this; } };
    },
    async batch() { return [{ meta: { changes: 0 } }, { meta: { changes: 0 } }, { meta: { changes: 0 } }]; }
  };
  const result = await issueDeviceCredential(db, {
    challengeId: "a".repeat(48), challengeSecretHash: "b".repeat(64), now: 1000
  });
  assert.equal(result, null);
});

test("successful activation returns a credential only when all inserts succeed", async () => {
  const db = {
    prepare() { return { bind() { return this; } }; },
    async batch() { return [1, 1, 1].map(changes => ({ meta: { changes } })); }
  };
  const result = await issueDeviceCredential(db, {
    challengeId: "a".repeat(48), challengeSecretHash: "b".repeat(64), now: 1000
  });
  assert.match(result.device_id, /^[0-9a-f-]{36}$/);
  assert.match(result.token, /^[a-f0-9]{64}$/);
  assert.equal(result.expires_at, 1000 + 30 * 24 * 3600);
});

test("revocation updates both device and credential", async () => {
  const calls = [];
  const db = {
    prepare(sql) {
      calls.push(sql);
      return { bind() { return this; } };
    },
    async batch() { return [{ meta: { changes: 1 } }, { meta: { changes: 1 } }]; }
  };
  assert.equal(await revokeDevice(db, "device-1", 1000), true);
  assert.equal(calls.length, 2);
  assert.match(calls[0], /registered_devices/);
  assert.match(calls[1], /device_credentials/);
});

test("invalid bearer token is rejected without DB query", async () => {
  assert.equal(await authenticateDevice({ prepare() { throw Error("DB should not be called"); } }, "wrong"), null);
});
