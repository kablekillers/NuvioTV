// Device credentials are opaque bearer secrets; only SHA-256 digests are stored.
// The caller must authenticate the activation poll secret before issuance.
const encode = new TextEncoder();
const toHex = bytes => [...bytes].map(b => b.toString(16).padStart(2, "0")).join("");
export async function hashToken(token) {
  return toHex(new Uint8Array(await crypto.subtle.digest("SHA-256", encode.encode(token))));
}
export function makeDeviceToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return toHex(bytes);
}
export async function issueDeviceCredential(db, { challengeId, challengeSecretHash, displayName = null, now = Math.floor(Date.now() / 1000) }) {
  if (!/^[a-f0-9]{48}$/.test(challengeId) || !/^[a-f0-9]{64}$/.test(challengeSecretHash))
    throw new Error("Invalid activation credentials");

  const deviceId = crypto.randomUUID();
  const token = makeDeviceToken();
  const tokenHash = await hashToken(token);
  const expiresAt = now + 30 * 24 * 3600;
  // D1 batch executes transactionally; the challenge transition must succeed
  // before a credential is returned. Conditional changes are checked below.
  const results = await db.batch([
    db.prepare("UPDATE activation_challenges SET state='consumed',consumed_at=?,device_id=? WHERE id=? AND poll_secret_hash=? AND state='approved' AND expires_at>? AND approved_household_id IS NOT NULL")
      .bind(now, deviceId, challengeId, challengeSecretHash, now),
    db.prepare("INSERT INTO registered_devices(id,household_id,display_name,created_at) SELECT ?,approved_household_id,?,? FROM activation_challenges WHERE id=? AND state='consumed' AND device_id=? AND approved_household_id IS NOT NULL")
      .bind(deviceId, displayName, now, challengeId, deviceId),
    db.prepare("INSERT INTO device_credentials(device_id,token_hash,issued_at,expires_at) SELECT ?,?,?,? WHERE EXISTS (SELECT 1 FROM registered_devices WHERE id=?)")
      .bind(deviceId, tokenHash, now, expiresAt, deviceId)
  ]);
  if (results[0]?.meta?.changes !== 1 || results[1]?.meta?.changes !== 1 || results[2]?.meta?.changes !== 1)
    return null;
  return { device_id: deviceId, token, expires_at: expiresAt };
}
export async function authenticateDevice(db, token, now = Math.floor(Date.now() / 1000)) {
  if (typeof token !== "string" || !/^[a-f0-9]{64}$/.test(token)) return null;
  const hash = await hashToken(token);
  return db.prepare("SELECT d.id,d.household_id FROM device_credentials c JOIN registered_devices d ON d.id=c.device_id WHERE c.token_hash=? AND c.expires_at>? AND c.revoked_at IS NULL AND d.revoked_at IS NULL")
    .bind(hash, now).first();
}
export async function revokeDevice(db, deviceId, now = Math.floor(Date.now() / 1000)) {
  const results = await db.batch([
    db.prepare("UPDATE registered_devices SET revoked_at=? WHERE id=? AND revoked_at IS NULL").bind(now, deviceId),
    db.prepare("UPDATE device_credentials SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL").bind(now, deviceId)
  ]);
  return results[0]?.meta?.changes === 1;
}
