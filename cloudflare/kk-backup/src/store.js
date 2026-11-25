import { looksWiped, summarize } from "./nuvio.js";
import { now, randomHex, sha256Hex } from "./util.js";

export const KEEP_SNAPSHOTS = 40;
export const SYNC_MIN_INTERVAL_SECONDS = 120;

// ---------- Devices ----------

export async function getDevice(db, deviceId) {
  return db.prepare("SELECT * FROM devices WHERE id = ?").bind(deviceId).first();
}

/** Returns the device row only if the key is right and the device isn't blocked. */
export async function authenticateDevice(db, deviceId, key) {
  if (!deviceId || !key) return null;
  const device = await getDevice(db, deviceId);
  if (!device || device.revoked_at) return null;
  const hash = await sha256Hex(key);
  return hash === device.key_hash ? device : null;
}

/**
 * Creates or refreshes a device after its account was proven (Nuvio sign-in or
 * restore PIN). Keeps a still-valid key; otherwise issues a new one.
 * Returns { device, key } where key is only set when a new key was issued,
 * or { blocked: true } if the owner blocked this device.
 */
export async function registerDevice(db, { deviceId, presentedKey, email, info, country, at = now() }) {
  const existing = await getDevice(db, deviceId);
  if (existing?.revoked_at) return { blocked: true };

  let key = null;
  let keyHash = existing?.key_hash;
  const keyStillValid =
    existing && presentedKey && existing.account_email === email &&
    (await sha256Hex(presentedKey)) === existing.key_hash;
  if (!keyStillValid) {
    key = randomHex(32);
    keyHash = await sha256Hex(key);
  }

  if (existing) {
    await db.prepare(
      `UPDATE devices SET account_email = ?, key_hash = ?, name = COALESCE(?, name), model = COALESCE(?, model),
         platform = COALESCE(?, platform), app_version = COALESCE(?, app_version), last_seen_at = ?,
         last_country = COALESCE(?, last_country) WHERE id = ?`
    ).bind(email, keyHash, info.name, info.model, info.platform, info.appVersion, at, country, deviceId).run();
  } else {
    await db.prepare(
      `INSERT INTO devices (id, account_email, key_hash, name, model, platform, app_version, created_at, last_seen_at, last_country)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(deviceId, email, keyHash, info.name, info.model, info.platform, info.appVersion, at, at, country).run();
  }
  return { device: await getDevice(db, deviceId), key };
}

export async function touchDevice(db, deviceId, { info = {}, country = null, field = null, at = now() } = {}) {
  const extra = field === "backup" ? ", last_backup_at = ?" : field === "restore" ? ", last_restore_at = ?" : "";
  const statement = db.prepare(
    `UPDATE devices SET last_seen_at = ?, app_version = COALESCE(?, app_version), name = COALESCE(?, name),
       last_country = COALESCE(?, last_country)${extra} WHERE id = ?`
  );
  const values = [at, info.appVersion ?? null, info.name ?? null, country];
  if (extra) values.push(at);
  values.push(deviceId);
  await statement.bind(...values).run();
}

export async function setDeviceBlocked(db, deviceId, blocked, at = now()) {
  const result = await db.prepare("UPDATE devices SET revoked_at = ? WHERE id = ?")
    .bind(blocked ? at : null, deviceId).run();
  return (result.meta?.changes ?? 0) === 1;
}

export async function listDevices(db) {
  const rows = await db.prepare("SELECT * FROM devices ORDER BY last_seen_at DESC LIMIT 500").all();
  return rows.results || [];
}

// ---------- Snapshots ----------

export async function getCurrent(db, email) {
  return db.prepare(
    `SELECT c.snapshot_id, c.updated_at, c.last_checked_at, c.locked, s.payload, s.summary, s.created_at, s.sha256
       FROM current_snapshot c JOIN snapshots s ON s.id = c.snapshot_id WHERE c.account_email = ?`
  ).bind(email).first();
}

/**
 * Stores a captured copy. Unchanged copies only bump the "checked" time.
 * A copy that looks like a wipe is kept for the owner to look at, but never
 * becomes the one devices restore from. A locked account keeps its copy.
 */
export async function saveSnapshot(db, { email, capture, deviceId, at = now() }) {
  const surfacesJson = JSON.stringify(capture.surfaces);
  const sha = await sha256Hex(surfacesJson);
  const summary = summarize(capture.surfaces);
  const current = await getCurrent(db, email);

  if (current && current.sha256 === sha) {
    await db.prepare("UPDATE current_snapshot SET last_checked_at = ? WHERE account_email = ?").bind(at, email).run();
    return { changed: false, snapshotId: current.snapshot_id, summary };
  }

  const previousSummary = current ? JSON.parse(current.summary) : null;
  const suspicious = looksWiped(summary, previousSummary);
  const payload = JSON.stringify({
    schema: "kk-backup-v1",
    account_email: email,
    nuvio_user_id: capture.owner_user_id,
    captured_at: at,
    surfaces: capture.surfaces,
  });
  const inserted = await db.prepare(
    `INSERT INTO snapshots (account_email, nuvio_user_id, sha256, payload, summary, created_at, source_device_id, suspicious)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(email, capture.owner_user_id, sha, payload, JSON.stringify(summary), at, deviceId, suspicious ? 1 : 0).run();
  const snapshotId = inserted.meta?.last_row_id;

  let madeCurrent = false;
  if (!current) {
    await db.prepare(
      "INSERT INTO current_snapshot (account_email, snapshot_id, updated_at, last_checked_at, locked) VALUES (?, ?, ?, ?, 0)"
    ).bind(email, snapshotId, at, at).run();
    madeCurrent = true;
  } else if (!suspicious && !current.locked) {
    await db.prepare(
      "UPDATE current_snapshot SET snapshot_id = ?, updated_at = ?, last_checked_at = ? WHERE account_email = ?"
    ).bind(snapshotId, at, at, email).run();
    madeCurrent = true;
  } else {
    await db.prepare("UPDATE current_snapshot SET last_checked_at = ? WHERE account_email = ?").bind(at, email).run();
  }

  await pruneSnapshots(db, email);
  return { changed: true, snapshotId, summary, suspicious, madeCurrent, locked: Boolean(current?.locked) };
}

export async function pruneSnapshots(db, email, keep = KEEP_SNAPSHOTS) {
  await db.prepare(
    `DELETE FROM snapshots WHERE account_email = ?
       AND id NOT IN (SELECT snapshot_id FROM current_snapshot WHERE account_email = ?)
       AND id NOT IN (SELECT id FROM snapshots WHERE account_email = ? ORDER BY created_at DESC, id DESC LIMIT ?)`
  ).bind(email, email, email, keep).run();
}

export async function listSnapshots(db, email) {
  const rows = await db.prepare(
    `SELECT id, summary, created_at, source_device_id, suspicious FROM snapshots
      WHERE account_email = ? ORDER BY created_at DESC, id DESC LIMIT ?`
  ).bind(email, KEEP_SNAPSHOTS).all();
  return rows.results || [];
}

export async function getSnapshot(db, email, id) {
  return db.prepare("SELECT * FROM snapshots WHERE account_email = ? AND id = ?").bind(email, id).first();
}

export async function makeCurrent(db, email, id, at = now()) {
  const snapshot = await getSnapshot(db, email, id);
  if (!snapshot) return false;
  await db.prepare(
    `INSERT INTO current_snapshot (account_email, snapshot_id, updated_at, last_checked_at, locked) VALUES (?, ?, ?, ?, 0)
     ON CONFLICT(account_email) DO UPDATE SET snapshot_id = excluded.snapshot_id, updated_at = excluded.updated_at`
  ).bind(email, id, at, at).run();
  return true;
}

export async function setLocked(db, email, locked) {
  const result = await db.prepare("UPDATE current_snapshot SET locked = ? WHERE account_email = ?")
    .bind(locked ? 1 : 0, email).run();
  return (result.meta?.changes ?? 0) === 1;
}

// ---------- Restore PIN brute-force protection ----------

const PIN_WINDOW_SECONDS = 15 * 60;
const PIN_MAX_FAILURES = 5;

export async function pinBlocked(db, buckets, at = now()) {
  for (const bucket of buckets) {
    const row = await db.prepare("SELECT attempts, window_start FROM pin_attempts WHERE bucket = ?").bind(bucket).first();
    if (row && at - row.window_start < PIN_WINDOW_SECONDS && row.attempts >= PIN_MAX_FAILURES) return true;
  }
  return false;
}

export async function recordPinFailure(db, buckets, at = now()) {
  for (const bucket of buckets) {
    await db.prepare(
      `INSERT INTO pin_attempts (bucket, attempts, window_start) VALUES (?, 1, ?)
       ON CONFLICT(bucket) DO UPDATE SET
         attempts = CASE WHEN ? - window_start >= ? THEN 1 ELSE attempts + 1 END,
         window_start = CASE WHEN ? - window_start >= ? THEN ? ELSE window_start END`
    ).bind(bucket, at, at, PIN_WINDOW_SECONDS, at, PIN_WINDOW_SECONDS, at).run();
  }
}

export async function clearPinFailures(db, buckets) {
  for (const bucket of buckets) {
    await db.prepare("DELETE FROM pin_attempts WHERE bucket = ?").bind(bucket).run();
  }
}

/** Counts one hit in a fixed window; true once the bucket is over `max` (for QR start rate limits). */
export async function hitLimit(db, bucket, max, windowSeconds, at = now()) {
  await db.prepare(
    `INSERT INTO pin_attempts (bucket, attempts, window_start) VALUES (?, 1, ?)
     ON CONFLICT(bucket) DO UPDATE SET
       attempts = CASE WHEN ? - window_start >= ? THEN 1 ELSE attempts + 1 END,
       window_start = CASE WHEN ? - window_start >= ? THEN ? ELSE window_start END`
  ).bind(bucket, at, at, windowSeconds, at, windowSeconds, at).run();
  const row = await db.prepare("SELECT attempts FROM pin_attempts WHERE bucket = ?").bind(bucket).first();
  return (row?.attempts ?? 0) > max;
}
