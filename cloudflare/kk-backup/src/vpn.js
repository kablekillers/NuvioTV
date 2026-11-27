// Kable Killers VPN: the owner sends an IPVanish WireGuard .conf to one device from /admin.
// The file is encrypted (AES-GCM, key derived from ADMIN_PASSWORD) while it waits, and deleted
// as soon as the device collects it. Devices check every couple of minutes.

import { now } from "./util.js";

const enc = new TextEncoder();
const dec = new TextDecoder();
const b64 = (bytes) => btoa(String.fromCharCode(...bytes));
const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

async function vpnKey(env) {
  const raw = await crypto.subtle.digest("SHA-256", enc.encode(`kk-vpn:${env.ADMIN_PASSWORD}`));
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

export async function encryptConf(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await vpnKey(env), enc.encode(text)));
  const out = new Uint8Array(iv.length + ct.length);
  out.set(iv);
  out.set(ct, iv.length);
  return b64(out);
}

export async function decryptConf(env, stored) {
  const bytes = unb64(stored);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: bytes.slice(0, 12) }, await vpnKey(env), bytes.slice(12));
  return dec.decode(pt);
}

/** A WireGuard client config: an [Interface] with a PrivateKey and Address, and at least one [Peer]. */
export function looksLikeWireGuard(text) {
  if (typeof text !== "string" || text.length > 8192) return false;
  return /\[Interface\]/i.test(text) && /PrivateKey\s*=/i.test(text) && /Address\s*=/i.test(text) &&
    /\[Peer\]/i.test(text) && /PublicKey\s*=/i.test(text) && /Endpoint\s*=/i.test(text);
}

export async function queueVpn(db, env, deviceId, action, conf = null, at = now()) {
  const payload = action === "set" ? await encryptConf(env, conf) : null;
  await db.prepare(
    "INSERT INTO vpn_pushes (device_id, action, payload, created_at) VALUES (?, ?, ?, ?) " +
      "ON CONFLICT(device_id) DO UPDATE SET action = excluded.action, payload = excluded.payload, created_at = excluded.created_at",
  ).bind(deviceId, action, payload, at).run();
}

export async function cancelVpn(db, deviceId) {
  await db.prepare("DELETE FROM vpn_pushes WHERE device_id = ?").bind(deviceId).run();
}

export async function pendingVpn(db) {
  const { results } = await db.prepare("SELECT device_id, action, created_at FROM vpn_pushes").all();
  return new Map((results || []).map((r) => [r.device_id, r]));
}

export async function vpnStatus(db) {
  const { results } = await db.prepare("SELECT device_id, has_profile, is_on, updated_at FROM device_vpn").all();
  return new Map((results || []).map((r) => [r.device_id, r]));
}

/** The device's check: report its VPN state, collect anything waiting (then it's deleted). */
export async function collectVpn(db, env, deviceId, report, at = now()) {
  await db.prepare(
    "INSERT INTO device_vpn (device_id, has_profile, is_on, updated_at) VALUES (?, ?, ?, ?) " +
      "ON CONFLICT(device_id) DO UPDATE SET has_profile = excluded.has_profile, is_on = excluded.is_on, updated_at = excluded.updated_at",
  ).bind(deviceId, report.hasProfile ? 1 : 0, report.on ? 1 : 0, at).run();
  const row = await db.prepare("SELECT action, payload FROM vpn_pushes WHERE device_id = ?").bind(deviceId).first();
  if (!row) return { ok: true };
  await cancelVpn(db, deviceId);
  if (row.action === "remove") return { ok: true, action: "remove" };
  try {
    return { ok: true, action: "set", conf: await decryptConf(env, row.payload) };
  } catch {
    return { ok: true }; // ADMIN_PASSWORD changed since it was sent: the owner sends it again.
  }
}
