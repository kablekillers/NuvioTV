// Built-in VPN config pool. The owner uploads IPVanish WireGuard configs on /admin;
// each device signed in to a covered login gets its own config the first time it
// asks, and keeps it. One config = one key = one device, so connections never fight.

import { sha256Hex, now } from "./util.js";

const MAX_CONFIG_LENGTH = 4096;

/** Splits pasted text or uploaded files into separate WireGuard configs. Returns { configs, rejected }. */
export function parseConfigs(text) {
  const normalized = String(text ?? "").replace(/\r\n?/g, "\n");
  const parts = normalized.split(/(?=^\s*\[Interface\]\s*$)/im).map((p) => p.trim()).filter(Boolean);
  const configs = [];
  let rejected = 0;
  for (const part of parts) {
    if (isValidConfig(part)) configs.push(part);
    else rejected++;
  }
  return { configs, rejected };
}

export function isValidConfig(text) {
  if (typeof text !== "string" || text.length > MAX_CONFIG_LENGTH) return false;
  return /^\s*\[Interface\]\s*$/im.test(text)
    && /^\s*PrivateKey\s*=\s*\S+/im.test(text)
    && /^\s*\[Peer\]\s*$/im.test(text)
    && /^\s*PublicKey\s*=\s*\S+/im.test(text)
    && /^\s*Endpoint\s*=\s*\S+/im.test(text);
}

export function endpointHost(text) {
  const match = /^\s*Endpoint\s*=\s*(\S+)/im.exec(text ?? "");
  if (!match) return null;
  const value = match[1];
  const colon = value.lastIndexOf(":");
  return (colon > 0 ? value.slice(0, colon) : value).replace(/^\[|\]$/g, "") || null;
}

/** Adds configs to the pool, skipping ones already there. Returns how many were added. */
export async function addConfigs(db, configs, at = now()) {
  let added = 0;
  for (const config of configs) {
    const hash = await sha256Hex(config);
    const result = await db.prepare(
      `INSERT OR IGNORE INTO vpn_configs (sha256, config, label, created_at) VALUES (?, ?, ?, ?)`
    ).bind(hash, config, endpointHost(config), at).run();
    if (result.meta?.changes) added++;
  }
  return added;
}

/** The device's config, giving it the next free one if it has none. Null when the pool is empty. */
export async function claimConfig(db, deviceId, at = now()) {
  let row = await db.prepare(`SELECT * FROM vpn_configs WHERE device_id = ?`).bind(deviceId).first();
  if (!row) {
    await db.prepare(
      `UPDATE vpn_configs SET device_id = ?, assigned_at = ?
       WHERE id = (SELECT id FROM vpn_configs WHERE device_id IS NULL ORDER BY id LIMIT 1) AND device_id IS NULL`
    ).bind(deviceId, at).run();
    row = await db.prepare(`SELECT * FROM vpn_configs WHERE device_id = ?`).bind(deviceId).first();
  }
  if (!row) return null;
  await db.prepare(`UPDATE vpn_configs SET delivered_at = ? WHERE id = ?`).bind(at, row.id).run();
  return row;
}

export async function listConfigs(db) {
  const { results } = await db.prepare(
    `SELECT v.id, v.label, v.created_at, v.device_id, v.assigned_at, v.delivered_at,
            d.name AS device_name, d.model AS device_model, d.account_email, d.platform, d.last_seen_at
     FROM vpn_configs v LEFT JOIN devices d ON d.id = v.device_id
     ORDER BY (v.device_id IS NULL), v.assigned_at DESC, v.id`
  ).all();
  return results ?? [];
}

/** Frees a config so another device can get it. The old device drops it on its next check. */
export async function releaseConfig(db, id) {
  await db.prepare(`UPDATE vpn_configs SET device_id = NULL, assigned_at = NULL, delivered_at = NULL WHERE id = ?`)
    .bind(id).run();
}

export async function releaseDeviceConfig(db, deviceId) {
  await db.prepare(`UPDATE vpn_configs SET device_id = NULL, assigned_at = NULL, delivered_at = NULL WHERE device_id = ?`)
    .bind(deviceId).run();
}

export async function deleteConfig(db, id) {
  await db.prepare(`DELETE FROM vpn_configs WHERE id = ?`).bind(id).run();
}
