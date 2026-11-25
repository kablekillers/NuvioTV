import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { createD1 } from "./d1-shim.mjs";

const OWNER_PW = "owner-password-1";
const CUSTOMER = "kablekillers6@gmail.com";
const OWNER = "millertime42085@gmail.com";
const TOKENS = { "token-customer-aaaaaaaaaaaaaaaaa": { id: "user-customer", email: CUSTOMER } };

function setup() {
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    const user = TOKENS[(init.headers?.Authorization || "").replace("Bearer ", "")];
    if (!user) return new Response("{}", { status: 401 });
    const reply = (b) => new Response(JSON.stringify(b), { status: 200 });
    switch (url.pathname) {
      case "/auth/v1/user": return reply({ id: user.id, email: user.email });
      case "/rest/v1/rpc/get_sync_owner": return reply(user.id);
      case "/rest/v1/addons": return reply([{ url: "https://a/manifest.json", sort_order: 0 }]);
      case "/rest/v1/rpc/sync_pull_profiles": return reply([{ profile_index: 1, name: "Main" }]);
      default: return reply([]);
    }
  };
  return { DB: createD1(), SUPABASE_URL: "https://nuvio.example.co", SUPABASE_ANON_KEY: "anon-key", ADMIN_PASSWORD: OWNER_PW, RESTORE_PIN: "482913" };
}

const req = (env, path, { method = "GET", body, form, headers = {} } = {}) => {
  const init = { method, headers: { ...headers }, redirect: "manual" };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers["Content-Type"] = "application/json"; }
  if (form) { init.body = new URLSearchParams(form).toString(); init.headers["Content-Type"] = "application/x-www-form-urlencoded"; }
  return worker.fetch(new Request(`https://kk-backup.test${path}`, init), env);
};

const start = async (env, deviceId = "tv-newcustomer-0001") => {
  const r = await req(env, "/v1/qr/start", { method: "POST", body: { device_id: deviceId, device_name: "Bedroom TV", device_model: "onn 4K", platform: "tv", app_version: "1.1.14" } });
  assert.equal(r.status, 200);
  return r.json();
};
const poll = async (env, q) => (await req(env, "/v1/qr/poll", { method: "POST", body: { code: q.code, poll_token: q.poll_token } })).json();
const linkPath = (q) => new URL(q.url).pathname;

async function ownerCookie(env, q) {
  const r = await req(env, linkPath(q), { method: "POST", form: { step: "owner-login", password: OWNER_PW } });
  assert.equal(r.status, 303);
  return r.headers.get("Set-Cookie").split(";")[0];
}

async function approveAs(env, q, cookie, account = CUSTOMER, decision = "approve") {
  const page = await (await req(env, linkPath(q), { headers: { Cookie: cookie } })).text();
  assert.match(page, /Approve this device\?/);
  assert.match(page, /Bedroom TV/);
  const token = /name="token" value="([0-9a-f]+)"/.exec(page)[1];
  return req(env, linkPath(q), { method: "POST", headers: { Cookie: cookie }, form: { step: "approve", token, account, decision } });
}

test("QR start gives a link and a poll token; it waits until approved", async () => {
  const env = setup();
  const q = await start(env);
  assert.match(q.code, /^[A-HJ-NP-Z2-9]{8}$/);
  assert.equal(q.url, `https://kk-backup.test/link/${q.code}`);
  assert.equal((await poll(env, q)).status, "waiting");
  assert.equal((await poll(env, { ...q, poll_token: "wrong" })).error, "Unknown QR code");
});

test("owner scans, signs in once, approves; device gets a working key exactly once", async () => {
  const env = setup();
  const q = await start(env);
  const first = await (await req(env, linkPath(q))).text();
  assert.match(first, /Owner password/);

  const bad = await (await req(env, linkPath(q), { method: "POST", form: { step: "owner-login", password: "nope" } })).text();
  assert.match(bad, /That password isn/);

  const cookie = await ownerCookie(env, q);
  const done = await (await approveAs(env, q, cookie)).text();
  assert.match(done, /Device approved/);

  const result = await poll(env, q);
  assert.equal(result.status, "approved");
  assert.equal(result.account_email, CUSTOMER);
  assert.equal(result.auto_approved, false);
  assert.equal((await poll(env, q)).status, "used");
  assert.equal(env.DB.sqlite.prepare("SELECT device_key FROM qr_logins").get().device_key, null);

  const ping = await req(env, "/v1/ping", { method: "POST", body: {}, headers: { Authorization: `Bearer ${result.device_key}`, "X-KK-Device-Id": "tv-newcustomer-0001" } });
  assert.equal(ping.status, 200);
});

test("the owner's phone stays signed in for the next QR", async () => {
  const env = setup();
  const cookie = await ownerCookie(env, await start(env, "tv-first-device-01"));
  const q2 = await start(env, "tv-second-device-1");
  const page = await (await req(env, linkPath(q2), { headers: { Cookie: cookie } })).text();
  assert.match(page, /Approve this device\?/);
  assert.match(page, /value="kablekillers6@gmail.com" checked/);
  // A forged cookie doesn't work, and changing the password signs phones out.
  assert.match(await (await req(env, linkPath(q2), { headers: { Cookie: "kk_owner=1.abc" } })).text(), /Owner password/);
  assert.match(await (await req({ ...env, ADMIN_PASSWORD: "another-pass-9" }, linkPath(q2), { headers: { Cookie: cookie } })).text(), /Owner password/);
});

test("owner can pick the other login, or refuse", async () => {
  const env = setup();
  const q = await start(env, "tv-owner-livingrm");
  const cookie = await ownerCookie(env, q);
  await approveAs(env, q, cookie, OWNER);
  assert.equal((await poll(env, q)).account_email, OWNER);

  const q2 = await start(env, "tv-unknown-device");
  assert.match(await (await approveAs(env, q2, cookie, CUSTOMER, "deny")).text(), /Not approved/);
  assert.equal((await poll(env, q2)).status, "denied");
});

test("approve once: after signing out, the device's new QR goes straight through", async () => {
  const env = setup();
  const q = await start(env);
  await approveAs(env, q, await ownerCookie(env, q));
  const firstKey = (await poll(env, q)).device_key;

  const again = await start(env); // same device, signed out, new QR
  const page = await (await req(env, linkPath(again))).text(); // anyone opens it, no owner sign-in
  assert.match(page, /Device approved/);
  const result = await poll(env, again);
  assert.equal(result.status, "approved");
  assert.equal(result.auto_approved, true);
  assert.notEqual(result.device_key, firstKey);
});

test("devices already signed in through Nuvio count as approved", async () => {
  const env = setup();
  const sync = await req(env, "/v1/sync", { method: "POST", headers: { Authorization: "Bearer token-customer-aaaaaaaaaaaaaaaaa" }, body: { device_id: "tv-existing-cust1", platform: "tv" } });
  assert.equal(sync.status, 200);
  const q = await start(env, "tv-existing-cust1");
  assert.match(await (await req(env, linkPath(q))).text(), /Device approved/);
  assert.equal((await poll(env, q)).account_email, CUSTOMER);
});

test("blocked devices can't get a QR or be approved", async () => {
  const env = setup();
  const q = await start(env);
  await approveAs(env, q, await ownerCookie(env, q));
  await poll(env, q);
  env.DB.sqlite.prepare("UPDATE devices SET revoked_at = 1").run();
  const r = await req(env, "/v1/qr/start", { method: "POST", body: { device_id: "tv-newcustomer-0001" } });
  assert.equal(r.status, 403);
});

test("expired codes stop working", async () => {
  const env = setup();
  const q = await start(env);
  env.DB.sqlite.prepare("UPDATE qr_logins SET expires_at = 1").run();
  assert.equal((await poll(env, q)).status, "expired");
  assert.match(await (await req(env, linkPath(q))).text(), /expired/);
});

test("owner password tries are limited", async () => {
  const env = setup();
  const q = await start(env);
  for (let i = 0; i < 5; i++) await req(env, linkPath(q), { method: "POST", form: { step: "owner-login", password: "wrong" } });
  const locked = await (await req(env, linkPath(q), { method: "POST", form: { step: "owner-login", password: OWNER_PW } })).text();
  assert.match(locked, /Too many tries/);
});

test("approve form needs the owner cookie and its token", async () => {
  const env = setup();
  const q = await start(env);
  const r = await req(env, linkPath(q), { method: "POST", form: { step: "approve", token: "x", account: CUSTOMER, decision: "approve" } });
  assert.match(await r.text(), /Owner password/);
  assert.equal((await poll(env, q)).status, "waiting");
});
