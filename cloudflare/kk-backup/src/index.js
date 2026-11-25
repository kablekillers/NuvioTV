// Kable Killers backup: keeps a copy of each login's Profile 1 setup (add-ons,
// plugins, collections, Home rows, settings, profile list) outside Nuvio, and a
// list of every device signed in to those logins.
//
// Routes used by the apps:
//   POST /v1/sync      Nuvio access token  -> refresh the copy, register device, get device key
//   POST /v1/ping      device key          -> "still here" (works while Nuvio is down)
//   GET  /v1/config    device key          -> the copy to restore from
//   POST /v1/pin-login email + restore PIN -> device key, for a new device while Nuvio is down
//   POST /v1/unlock    device key + restore PIN -> edit token for changing Profile 1 while Nuvio is down
//   POST /v1/push      device key (+ edit token) + Profile 1 setup -> new copy every device follows
//   POST /v1/qr/start  device info         -> backup QR code for when Nuvio is down
//   POST /v1/qr/poll   code + poll token   -> waiting / approved (device key) / denied / expired
// QR link page (owner scans and approves): /link/<code>
// Owner page: /admin (password = ADMIN_PASSWORD secret)

import { handleAdmin } from "./admin.js";
import { handleLinkPage, handleQrPoll, handleQrStart } from "./qr.js";
import { handlePush, handleUnlock } from "./edit.js";
import { captureProfileOne, NuvioUnauthorized, NuvioUnavailable, verifyNuvioUser } from "./nuvio.js";
import {
  authenticateDevice, clearPinFailures, clearUploadLease, getCurrent, originOf, pinBlocked, recordPinFailure,
  registerDevice, saveSnapshot, SYNC_MIN_INTERVAL_SECONDS, takeUploadLease, touchDevice,
} from "./store.js";
import {
  allowedAccounts, bearer, cleanText, isDeviceId, json, normalizeEmail, now, readJson, safeEqual,
} from "./util.js";

function deviceInfo(body) {
  return {
    name: cleanText(body.device_name),
    model: cleanText(body.device_model),
    platform: ["tv", "mobile"].includes(body.platform) ? body.platform : cleanText(body.platform, 20),
    appVersion: cleanText(body.app_version, 40),
  };
}

const country = (request) => cleanText(request.cf?.country ?? request.headers.get("CF-IPCountry"), 8);

async function handleSync(request, env) {
  const token = bearer(request);
  const body = await readJson(request);
  if (!isDeviceId(body.device_id)) return json({ error: "Invalid device id" }, 400);

  let user;
  try {
    user = await verifyNuvioUser(env, token);
  } catch (error) {
    if (error instanceof NuvioUnauthorized) return json({ error: "Nuvio sign-in not accepted" }, 401);
    throw error;
  }
  if (!allowedAccounts(env).includes(user.email)) return json({ error: "Account not covered by this backup" }, 403);

  const registration = await registerDevice(env.DB, {
    deviceId: body.device_id,
    presentedKey: typeof body.device_key === "string" ? body.device_key : null,
    email: user.email,
    info: deviceInfo(body),
    country: country(request),
  });
  if (registration.blocked) return json({ error: "This device was blocked by the owner" }, 403);

  let current = await getCurrent(env.DB, user.email);
  const uploadedOutageChanges =
    current && originOf(current) === "device" && Number(body.uploaded_snapshot_id) === current.snapshot_id;

  // Several devices sync at once after an app update; one Nuvio read every couple of minutes is plenty.
  // (Never skipped while outage changes are waiting to go up to Nuvio.)
  if (current && originOf(current) !== "device" && now() - current.last_checked_at < SYNC_MIN_INTERVAL_SECONDS && body.force !== true) {
    return json({ ok: true, skipped: true, device_key: registration.key, snapshot_id: current.snapshot_id });
  }

  const capture = await captureProfileOne(env, token, user);
  const saved = await saveSnapshot(env.DB, {
    email: user.email, capture, deviceId: body.device_id, acceptOverDevice: uploadedOutageChanges,
  });
  if (uploadedOutageChanges) await clearUploadLease(env.DB, user.email);
  await touchDevice(env.DB, body.device_id, { field: "backup" });

  // Profile 1 was changed on the backup while Nuvio was down, and Nuvio still has the old setup.
  // One device that already has those changes (and is on Profile 1) sends them up to Nuvio.
  current = await getCurrent(env.DB, user.email);
  let outage = null;
  if (current && originOf(current) === "device") {
    const canUpload = body.can_upload === true && Number(body.applied_snapshot_id) === current.snapshot_id;
    outage = {
      snapshot_id: current.snapshot_id,
      upload: canUpload && (await takeUploadLease(env.DB, user.email, body.device_id, current.snapshot_id)),
    };
  }
  return json({
    ok: true,
    device_key: registration.key,
    snapshot_id: current?.snapshot_id ?? saved.snapshotId,
    changed: saved.changed,
    held_back: saved.changed && !saved.madeCurrent,
    summary: saved.summary,
    outage_changes: outage,
  });
}

async function deviceFromRequest(request, env) {
  const deviceId = request.headers.get("X-KK-Device-Id");
  if (!isDeviceId(deviceId)) return null;
  return authenticateDevice(env.DB, deviceId, bearer(request));
}

async function handlePing(request, env) {
  const device = await deviceFromRequest(request, env);
  if (!device) return json({ error: "Unknown or blocked device" }, 401);
  const body = await readJson(request).catch(() => ({}));
  await touchDevice(env.DB, device.id, { info: deviceInfo(body), country: country(request) });
  const current = await getCurrent(env.DB, device.account_email);
  return json({
    ok: true, account_email: device.account_email, snapshot_id: current?.snapshot_id ?? null,
    origin: originOf(current),
  });
}

async function handleConfig(request, env) {
  const device = await deviceFromRequest(request, env);
  if (!device) return json({ error: "Unknown or blocked device" }, 401);
  const current = await getCurrent(env.DB, device.account_email);
  if (!current) return json({ error: "No backup saved for this login yet" }, 404);
  await touchDevice(env.DB, device.id, { field: "restore", country: country(request) });
  const payload = JSON.parse(current.payload);
  return json({ snapshot_id: current.snapshot_id, saved_at: current.created_at, ...payload });
}

async function handlePinLogin(request, env) {
  if (!env.RESTORE_PIN || String(env.RESTORE_PIN).length < 6) {
    return json({ error: "Restore PIN sign-in is turned off" }, 503);
  }
  const body = await readJson(request);
  const email = normalizeEmail(body.email);
  if (!isDeviceId(body.device_id)) return json({ error: "Invalid device id" }, 400);
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const buckets = [`email:${email}`, `ip:${ip}`];
  if (await pinBlocked(env.DB, buckets)) return json({ error: "Too many tries. Wait 15 minutes." }, 429);

  const pinOk = safeEqual(String(body.pin ?? ""), String(env.RESTORE_PIN));
  if (!pinOk || !allowedAccounts(env).includes(email)) {
    await recordPinFailure(env.DB, buckets);
    return json({ error: "Email or PIN is wrong" }, 401);
  }
  await clearPinFailures(env.DB, [`email:${email}`]);

  const registration = await registerDevice(env.DB, {
    deviceId: body.device_id, presentedKey: null, email, info: deviceInfo(body), country: country(request),
  });
  if (registration.blocked) return json({ error: "This device was blocked by the owner" }, 403);
  return json({ ok: true, device_key: registration.key, account_email: email });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const route = `${request.method} ${url.pathname}`;
    try {
      if (route === "GET /health" || route === "GET /") return json({ status: "ok", service: "kable-killers-backup" });
      if (!env.DB) return json({ error: "Backup database is not connected" }, 503);
      if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) return await handleAdmin(request, env, url);
      if (url.pathname.startsWith("/link/")) return await handleLinkPage(request, env, url);
      switch (route) {
        case "POST /v1/sync": return await handleSync(request, env);
        case "POST /v1/ping": return await handlePing(request, env);
        case "GET /v1/config": return await handleConfig(request, env);
        case "POST /v1/pin-login": return await handlePinLogin(request, env);
        case "POST /v1/unlock": return await handleUnlock(request, env);
        case "POST /v1/push": return await handlePush(request, env);
        case "POST /v1/qr/start": return await handleQrStart(request, env, url);
        case "POST /v1/qr/poll": return await handleQrPoll(request, env);
        default: return json({ error: "Not found" }, 404);
      }
    } catch (error) {
      if (error instanceof NuvioUnavailable) return json({ error: error.message }, 502);
      if (error instanceof SyntaxError || /JSON|Request too large/.test(error.message)) {
        return json({ error: "Bad request" }, 400);
      }
      console.error(error);
      return json({ error: "Backup error" }, 500);
    }
  },
};
