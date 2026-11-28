// Changing Profile 1 while Nuvio is down.
//
//   POST /v1/unlock  device key + restore PIN -> edit token (12 h). Needed on the customer
//                    login (kablekillers6), where Profile 1 is what every customer follows.
//   POST /v1/push    device key (+ edit token on the customer login) + Profile 1 setup
//                    -> saved as the copy every device follows.
//
// Pushed copies are marked origin "device". When Nuvio comes back, its (stale) copy is kept
// aside until one device has sent the outage changes up to Nuvio (see handleSync).

import {
  authenticateDevice, clearPinFailures, getCurrent, pinBlocked, recordPinFailure, saveSnapshot, touchDevice,
} from "./store.js";
import { bearer, hmacHex, isDeviceId, json, now, readJson, safeEqual } from "./util.js";

export const EDIT_TOKEN_SECONDS = 12 * 60 * 60;
const CUSTOMER_EMAIL = "kablekillers6@gmail.com";
const PUSHABLE = ["addons", "plugins", "collections", "home_catalog_settings", "profile_settings_tv", "profile_settings_mobile"];

const restorePin = (env) => String(env.RESTORE_PIN || "");
const pinEnabled = (env) => /^\d{6,}$/.test(restorePin(env));

async function editToken(env, deviceId, expiresAt) {
  return `${expiresAt}.${await hmacHex(restorePin(env), `kk-edit-v1:${deviceId}:${expiresAt}`)}`;
}

export async function editTokenValid(env, deviceId, token, at = now()) {
  if (!pinEnabled(env) || typeof token !== "string") return false;
  const match = /^(\d+)\.([0-9a-f]{64})$/.exec(token);
  if (!match || Number(match[1]) <= at) return false;
  return safeEqual(token, await editToken(env, deviceId, Number(match[1])));
}

/** The customer login needs the restore PIN to change Profile 1; other logins are the owner's own. */
export const needsUnlock = (email) => email === CUSTOMER_EMAIL;

async function deviceFrom(request, env) {
  const deviceId = request.headers.get("X-KK-Device-Id");
  if (!isDeviceId(deviceId)) return null;
  return authenticateDevice(env.DB, deviceId, bearer(request));
}

export async function handleUnlock(request, env) {
  if (!pinEnabled(env)) return json({ error: "Restore PIN isn't set up" }, 503);
  const device = await deviceFrom(request, env);
  if (!device) return json({ error: "Unknown or blocked device" }, 401);
  const body = await readJson(request);
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const buckets = [`unlock:${device.id}`, `ip:${ip}`];
  const at = now();
  if (await pinBlocked(env.DB, buckets, at)) return json({ error: "Too many tries. Wait 15 minutes." }, 429);
  if (!safeEqual(String(body.pin ?? ""), restorePin(env))) {
    await recordPinFailure(env.DB, buckets, at);
    return json({ error: "Wrong PIN" }, 401);
  }
  await clearPinFailures(env.DB, [`unlock:${device.id}`]);
  const expiresAt = at + EDIT_TOKEN_SECONDS;
  return json({ ok: true, edit_token: await editToken(env, device.id, expiresAt), expires_at: expiresAt });
}

function validSurfaces(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const out = {};
  for (const key of PUSHABLE) {
    if (!(key in raw)) continue;
    const value = raw[key];
    if (value !== null && !Array.isArray(value)) return null;
    out[key] = value;
  }
  return Object.keys(out).length ? out : null;
}

export async function handlePush(request, env) {
  const device = await deviceFrom(request, env);
  if (!device) return json({ error: "Unknown or blocked device" }, 401);
  const body = await readJson(request, 4 * 1024 * 1024);
  const email = device.account_email;
  if (needsUnlock(email) && !(await editTokenValid(env, device.id, body.edit_token))) {
    return json({ error: "Unlock Profile 1 with the restore PIN first" }, 403);
  }
  const pushed = validSurfaces(body.surfaces);
  if (!pushed) return json({ error: "Nothing to save" }, 400);

  const current = await getCurrent(env.DB, email);
  if (!current) return json({ error: "No backup saved for this login yet" }, 404);
  const base = JSON.parse(current.payload);
  // Only the parts this app sent change; the rest (e.g. the other app's settings, profiles) stay.
  // Apps send rows without Nuvio's ids; fill in what the restore code expects.
  for (const key of ["addons", "plugins"]) {
    if (Array.isArray(pushed[key])) {
      pushed[key] = pushed[key].map((row, index) => ({
        ...row, user_id: row?.user_id ?? base.nuvio_user_id, profile_id: 1, sort_order: row?.sort_order ?? index,
      }));
    }
  }
  const surfaces = { ...base.surfaces, ...pushed };
  const saved = await saveSnapshot(env.DB, {
    email,
    capture: { owner_user_id: base.nuvio_user_id, surfaces },
    deviceId: device.id,
    origin: "device",
  });
  await touchDevice(env.DB, device.id, { field: "backup" });
  const after = await getCurrent(env.DB, email);
  return json({
    ok: true,
    changed: saved.changed,
    snapshot_id: after.snapshot_id,
    held_back: saved.changed && !saved.madeCurrent,
    summary: saved.summary,
  });
}
