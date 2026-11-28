// Backup QR sign-in, for when Nuvio is down.
//
//   1. The TV/phone calls POST /v1/qr/start and shows the returned link as a QR.
//   2. The customer sends the QR to the owner, who scans it. The /link/<code> page
//      asks for the owner password once (the phone stays signed in after that),
//      then shows the device with an Approve button and a choice of login.
//   3. A device that was approved before (and isn't blocked) goes straight
//      through as soon as anyone opens its link.
//   4. The TV/phone polls POST /v1/qr/poll and, once approved, receives its
//      device key (handed out once, then wiped from the database).
//
// Signing out on the device forgets its key, so getting back in always takes a
// new QR scan, but a device the owner approved before never needs approving again.

import { clearPinFailures, getDevice, hitLimit, pinBlocked, recordPinFailure, registerDevice } from "./store.js";
import {
  allowedAccounts, cleanText, hmacHex, isDeviceId, json, normalizeEmail, now, randomHex, readJson, safeEqual, sha256Hex,
} from "./util.js";

export const QR_SECONDS = 30 * 60; // time for the customer to send the QR and the owner to approve it
export const OWNER_SESSION_SECONDS = 365 * 24 * 60 * 60;
const POLL_SECONDS = 3;
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
const CODE_PATTERN = /^[A-HJ-NP-Z2-9]{8}$/;

const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => (
  { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
));

function newCode() {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

export const normalizeCode = (value) => String(value ?? "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
const ipOf = (request) => request.headers.get("CF-Connecting-IP") || "unknown";
const country = (request) => cleanText(request.cf?.country ?? request.headers.get("CF-IPCountry"), 8);

function deviceInfo(body) {
  return {
    name: cleanText(body.device_name),
    model: cleanText(body.device_model),
    platform: ["tv", "mobile"].includes(body.platform) ? body.platform : cleanText(body.platform, 20),
    appVersion: cleanText(body.app_version, 40),
  };
}

export async function getQr(db, code) {
  if (!CODE_PATTERN.test(code)) return null;
  return db.prepare("SELECT * FROM qr_logins WHERE code = ?").bind(code).first();
}

const isOpen = (qr, at = now()) => qr.status === "waiting" && at < qr.expires_at;

async function approve(db, qr, { email, auto, at = now() }) {
  // Claim the code first so two taps (or two phones) can't issue two different keys.
  const claim = await db.prepare("UPDATE qr_logins SET status = 'approving' WHERE code = ? AND status = 'waiting'")
    .bind(qr.code).run();
  if ((claim.meta?.changes ?? 0) !== 1) return false;
  const registration = await registerDevice(db, {
    deviceId: qr.device_id,
    presentedKey: null,
    email,
    info: { name: qr.device_name, model: qr.device_model, platform: qr.platform, appVersion: qr.app_version },
    country: qr.country,
    at,
  });
  if (registration.blocked) {
    await db.prepare("UPDATE qr_logins SET status = 'denied', decided_at = ? WHERE code = ?").bind(at, qr.code).run();
    return false;
  }
  await db.prepare(
    `UPDATE qr_logins SET status = 'approved', account_email = ?, device_key = ?, auto_approved = ?, claimed_at = ?, decided_at = ?
      WHERE code = ? AND status = 'approving'`
  ).bind(email, registration.key, auto ? 1 : 0, at, at, qr.code).run();
  return true;
}

async function pruneQr(db, at = now()) {
  // Keep a day of history; never keep an uncollected key longer than its window.
  await db.prepare("UPDATE qr_logins SET device_key = NULL WHERE device_key IS NOT NULL AND expires_at <= ?").bind(at).run();
  await db.prepare("DELETE FROM qr_logins WHERE expires_at <= ?").bind(at - 24 * 60 * 60).run();
}

// ---------- App routes ----------

export async function handleQrStart(request, env, url) {
  const body = await readJson(request);
  if (!isDeviceId(body.device_id)) return json({ error: "Invalid device id" }, 400);
  if (await hitLimit(env.DB, `qrstart:${ipOf(request)}`, 30, 15 * 60)) {
    return json({ error: "Too many QR codes. Wait 15 minutes." }, 429);
  }
  const existing = await getDevice(env.DB, body.device_id);
  if (existing?.revoked_at) return json({ error: "This device was blocked by the owner" }, 403);

  await pruneQr(env.DB);
  const at = now();
  const info = deviceInfo(body);
  const pollToken = randomHex(32);
  let code = newCode();
  for (let i = 0; i < 4 && (await getQr(env.DB, code)); i++) code = newCode();

  await env.DB.prepare(
    `INSERT INTO qr_logins (code, poll_hash, device_id, device_name, device_model, platform, app_version, country,
       status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'waiting', ?, ?)`
  ).bind(code, await sha256Hex(pollToken), body.device_id, info.name, info.model, info.platform, info.appVersion,
    country(request), at, at + QR_SECONDS).run();

  return json({
    ok: true,
    code,
    poll_token: pollToken,
    url: `${url.origin}/link/${code}`,
    expires_in: QR_SECONDS,
    poll_interval: POLL_SECONDS,
  });
}

export async function handleQrPoll(request, env) {
  const body = await readJson(request);
  const qr = await getQr(env.DB, normalizeCode(body.code));
  if (!qr || !safeEqual(await sha256Hex(String(body.poll_token ?? "")), qr.poll_hash)) {
    return json({ error: "Unknown QR code" }, 404);
  }
  const at = now();
  const base = { poll_interval: POLL_SECONDS, expires_in: Math.max(0, qr.expires_at - at) };

  if (qr.status === "approved" && qr.device_key) {
    const device = await getDevice(env.DB, qr.device_id);
    // Hand the key out exactly once.
    await env.DB.prepare("UPDATE qr_logins SET status = 'done', device_key = NULL WHERE code = ? AND status = 'approved'")
      .bind(qr.code).run();
    if (!device || device.revoked_at) return json({ ...base, status: "denied" });
    return json({
      ...base, status: "approved", device_key: qr.device_key, account_email: qr.account_email,
      auto_approved: Boolean(qr.auto_approved),
    });
  }
  if (qr.status === "denied") return json({ ...base, status: "denied" });
  if (qr.status === "approving") return json({ ...base, status: "waiting" });
  if (qr.status === "done") return json({ ...base, status: "used" });
  if (!isOpen(qr, at)) return json({ ...base, status: "expired" });
  return json({ ...base, status: qr.status });
}

// ---------- Owner sign-in on the phone that scans the QR ----------

const OWNER_COOKIE = "kk_owner";
const ownerSecret = (env) => String(env.ADMIN_PASSWORD || "");
const ownerEnabled = (env) => ownerSecret(env).length >= 8;

async function ownerCookieValue(env, issuedAt = now()) {
  return `${issuedAt}.${await hmacHex(ownerSecret(env), `kk-owner-session-v1:${issuedAt}`)}`;
}

/** True when this phone signed in as the owner before (changing the password signs every phone out). */
export async function isOwner(request, env, at = now()) {
  if (!ownerEnabled(env)) return false;
  const cookies = request.headers.get("Cookie") || "";
  const match = new RegExp(`(?:^|;\\s*)${OWNER_COOKIE}=(\\d+)\\.([0-9a-f]{64})`).exec(cookies);
  if (!match) return false;
  const issuedAt = Number(match[1]);
  if (!(issuedAt <= at && at - issuedAt < OWNER_SESSION_SECONDS)) return false;
  return safeEqual(`${match[1]}.${match[2]}`, await ownerCookieValue(env, issuedAt));
}

const approveToken = (env, code) => hmacHex(ownerSecret(env), `kk-qr-approve:${code}`);

// ---------- Link page ----------

function phonePage(title, body, { refresh = false, headers = {} } = {}) {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">${refresh ? '<meta http-equiv="refresh" content="5">' : ""}
<title>${escapeHtml(title)} · Kable Killers</title>
<style>
:root{color-scheme:dark;--bg:#0b0b0d;--card:#151518;--line:#2a2a30;--text:#f2f0ea;--muted:#a19d93;--gold:#d4af37;--bad:#e5484d;--ok:#46a758}
*{box-sizing:border-box}body{margin:0;min-height:100vh;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;display:flex;align-items:center;justify-content:center}
main{width:100%;max-width:420px;padding:24px 18px}.brand{font-weight:700;letter-spacing:.02em;margin-bottom:18px}.brand span{color:var(--gold)}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:22px 20px}h1{font-size:21px;margin:0 0 8px}p{margin:8px 0}.muted{color:var(--muted);font-size:14px}
label{display:block;font-size:13px;color:var(--muted);margin:14px 0 6px}input[type=password]{width:100%;padding:12px 14px;border-radius:10px;border:1px solid var(--line);background:#0f0f12;color:var(--text);font:inherit}
.choice{display:flex;gap:10px;align-items:center;padding:12px 14px;border:1px solid var(--line);border-radius:10px;margin:8px 0;font-size:15px;color:var(--text)}
button{width:100%;margin-top:16px;padding:13px;border-radius:10px;border:0;background:var(--gold);color:#111;font:inherit;font-weight:600;cursor:pointer}
button.ghost{background:transparent;color:var(--bad);border:1px solid var(--bad);margin-top:10px}
.ok{color:var(--ok)}.bad{color:var(--bad)}.device{border:1px solid var(--line);border-radius:12px;padding:12px 14px;margin:12px 0}.device strong{display:block}
</style></head><body><main><div class="brand">KABLE <span>KILLERS</span></div><div class="card">${body}</div></main></body></html>`, {
    headers: {
      "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Frame-Options": "DENY",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
      "Referrer-Policy": "no-referrer",
      ...headers,
    },
  });
}

const deviceLabel = (qr) => escapeHtml(qr.device_name || qr.device_model || (qr.platform === "mobile" ? "Phone" : "TV"));
const deviceBox = (qr) => `<div class="device"><strong>${deviceLabel(qr)}</strong><span class="muted">${escapeHtml(
  [qr.platform === "tv" ? "TV" : qr.platform === "mobile" ? "Mobile" : qr.platform, qr.device_model, qr.app_version && `app ${qr.app_version}`, qr.country]
    .filter(Boolean).join(" · "))}</span></div>`;

function finishedPage(qr) {
  if (qr.status === "approved" || qr.status === "done") {
    return phonePage("Approved", `<h1 class="ok">Device approved</h1>${deviceBox(qr)}<p>It's signing in to <strong>${escapeHtml(qr.account_email)}</strong> and loading the Kable Killers setup now.</p><p class="muted">You can close this page.</p>`);
  }
  if (qr.status === "denied") {
    return phonePage("Not approved", `<h1 class="bad">Not approved</h1>${deviceBox(qr)}<p>This device won't be signed in.</p>`);
  }
  return phonePage("Expired", `<h1>This code expired</h1><p>Show a new QR code on the device and scan that one.</p>`);
}

async function signInPage(qr, error = null) {
  return phonePage("Approve device", `<h1>Approve a device</h1>
<p class="muted">Nuvio is offline, so this sign-in uses the Kable Killers backup. Sign in as the owner once, and this phone stays signed in.</p>
${deviceBox(qr)}
<form method="post" action="/link/${escapeHtml(qr.code)}"><input type="hidden" name="step" value="owner-login">
<label for="pw">Owner password</label><input id="pw" name="password" type="password" autocomplete="current-password" required>
${error ? `<p class="bad">${escapeHtml(error)}</p>` : ""}
<button type="submit">Sign in</button></form>`);
}

async function approvePage(env, qr) {
  const token = await approveToken(env, qr.code);
  const accounts = allowedAccounts(env);
  const customer = accounts.find((a) => a.startsWith("kablekillers6")) ?? accounts[0];
  const choices = accounts.map((a) => `<label class="choice"><input type="radio" name="account" value="${escapeHtml(a)}"${a === customer ? " checked" : ""}> ${escapeHtml(a)}</label>`).join("");
  return phonePage("Approve device", `<h1>Approve this device?</h1>${deviceBox(qr)}
<form method="post" action="/link/${escapeHtml(qr.code)}"><input type="hidden" name="step" value="approve"><input type="hidden" name="token" value="${token}">
<label>Sign it in to</label>${choices}
<button type="submit" name="decision" value="approve">Approve device</button>
<button type="submit" name="decision" value="deny" class="ghost">Don't approve</button></form>
<p class="muted">You only approve a device once. If it signs out later, its next QR goes straight through.</p>`);
}

export async function handleLinkPage(request, env, url) {
  const code = normalizeCode(url.pathname.slice("/link/".length));
  let qr = await getQr(env.DB, code);
  if (!qr) return phonePage("Not found", `<h1>Code not found</h1><p>Show a new QR code on the device and scan that one.</p>`);
  const at = now();
  if (!isOpen(qr, at)) return finishedPage(qr);

  // Approved once = good for good: a known, unblocked device goes straight through on its old login.
  const device = await getDevice(env.DB, qr.device_id);
  if (device?.revoked_at) {
    await env.DB.prepare("UPDATE qr_logins SET status = 'denied', decided_at = ? WHERE code = ?").bind(at, code).run();
    return finishedPage(await getQr(env.DB, code));
  }
  if (device && allowedAccounts(env).includes(device.account_email)) {
    await approve(env.DB, qr, { email: device.account_email, auto: true, at });
    return finishedPage(await getQr(env.DB, code));
  }

  if (!ownerEnabled(env)) {
    return phonePage("Unavailable", `<h1>Backup sign-in is off</h1><p>The owner password hasn't been set up yet.</p>`);
  }
  const owner = await isOwner(request, env, at);

  if (request.method === "GET") return owner ? approvePage(env, qr) : signInPage(qr);
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const origin = request.headers.get("Origin");
  if (origin && origin !== url.origin) return new Response("Forbidden", { status: 403 });

  const form = await request.formData();
  const step = String(form.get("step") || "");

  if (step === "owner-login") {
    const buckets = [`owner:${ipOf(request)}`];
    if (await pinBlocked(env.DB, buckets, at)) return signInPage(qr, "Too many tries. Wait 15 minutes.");
    if (!safeEqual(String(form.get("password") ?? ""), ownerSecret(env))) {
      await recordPinFailure(env.DB, buckets, at);
      return signInPage(qr, "That password isn't right.");
    }
    await clearPinFailures(env.DB, buckets);
    const cookie = `${OWNER_COOKIE}=${await ownerCookieValue(env, at)}; Max-Age=${OWNER_SESSION_SECONDS}; Path=/link; HttpOnly; Secure; SameSite=Lax`;
    return new Response(null, { status: 303, headers: { Location: `/link/${code}`, "Set-Cookie": cookie, "Cache-Control": "no-store" } });
  }

  if (step === "approve") {
    if (!owner || !safeEqual(String(form.get("token") || ""), await approveToken(env, code))) return signInPage(qr);
    if (String(form.get("decision")) === "deny") {
      await env.DB.prepare("UPDATE qr_logins SET status = 'denied', decided_at = ? WHERE code = ? AND status = 'waiting'").bind(at, code).run();
    } else {
      const email = normalizeEmail(form.get("account"));
      if (!allowedAccounts(env).includes(email)) return approvePage(env, qr);
      await approve(env.DB, qr, { email, auto: false, at });
    }
    qr = await getQr(env.DB, code);
    return finishedPage(qr);
  }
  return owner ? approvePage(env, qr) : signInPage(qr);
}
