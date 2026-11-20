import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { createD1 } from "./d1-shim.mjs";

const SUPABASE = "https://nuvio.example.co";
const OWNER = "millertime42085@gmail.com";
const CUSTOMER = "kablekillers6@gmail.com";
const TOKENS = {
  "token-owner-aaaaaaaaaaaaaaaaaaaa": { id: "user-owner", email: OWNER },
  "token-customer-aaaaaaaaaaaaaaaaa": { id: "user-customer", email: CUSTOMER },
  "token-stranger-aaaaaaaaaaaaaaaaa": { id: "user-x", email: "someone@else.com" },
};

/** Fake Nuvio cloud. `state.addons` etc. can be changed between calls. */
function fakeNuvio(state) {
  state.calls = 0;
  return async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    state.calls++;
    if (state.down) throw new TypeError("fetch failed");
    const token = (init.headers?.Authorization || "").replace("Bearer ", "");
    const user = TOKENS[token];
    if (!user || init.headers?.apikey !== "anon-key") return new Response("{}", { status: 401 });
    const reply = (body) => new Response(JSON.stringify(body), { status: 200 });
    const body = init.body ? JSON.parse(init.body) : {};
    switch (url.pathname) {
      case "/auth/v1/user": return reply({ id: user.id, email: user.email.toUpperCase() });
      case "/rest/v1/rpc/get_sync_owner": return reply(user.id);
      case "/rest/v1/addons":
        assert.equal(url.searchParams.get("profile_id"), "eq.1");
        assert.equal(url.searchParams.get("user_id"), `eq.${user.id}`);
        return reply(state.addons);
      case "/rest/v1/plugins": return new Response("{}", { status: 404 });
      case "/rest/v1/rpc/sync_pull_collections": return reply([{ profile_id: 1, collections_json: [{ title: "Movies" }] }]);
      case "/rest/v1/rpc/sync_pull_home_catalog_settings":
        assert.equal(body.p_platform, "home_catalog_shared");
        return reply([{ profile_id: 1, settings_json: { items: state.rows } }]);
      case "/rest/v1/rpc/sync_pull_profile_settings_blob":
        return reply([{ profile_id: 1, settings_json: { platform: body.p_platform } }]);
      case "/rest/v1/rpc/sync_pull_profiles": return reply(state.profiles);
      default: return new Response("{}", { status: 404 });
    }
  };
}

function setup(overrides = {}) {
  const state = {
    addons: [{ url: "https://a/manifest.json", sort_order: 0, enabled: true }, { url: "https://b/manifest.json", sort_order: 1 }],
    rows: [{ addon_id: "a", type: "movie", catalog_id: "top", order: 0 }],
    profiles: [{ profile_index: 1, name: "Main" }],
  };
  globalThis.fetch = fakeNuvio(state);
  const env = {
    DB: createD1(), SUPABASE_URL: SUPABASE, SUPABASE_ANON_KEY: "anon-key",
    ADMIN_PASSWORD: "owner-password-1", RESTORE_PIN: "482913", ...overrides,
  };
  return { env, state };
}

const call = (env, path, { method = "GET", body, headers = {} } = {}) => worker.fetch(new Request(`https://kk-backup.test${path}`, {
  method, headers: { "Content-Type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
}), env);

const sync = (env, token, body = {}) => call(env, "/v1/sync", {
  method: "POST", headers: { Authorization: `Bearer ${token}` },
  body: { device_id: "tv-livingroom-0001", device_name: "Living room", device_model: "onn 4K", platform: "tv", app_version: "1.1.7", ...body },
});

const ageCurrent = (env, seconds = 600) =>
  env.DB.sqlite.prepare("UPDATE current_snapshot SET last_checked_at = last_checked_at - ?").run(seconds);

test("health works without anything configured", async () => {
  const response = await worker.fetch(new Request("https://x/health"), {});
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, "ok");
});

test("first sync saves a copy and hands the device a key", async () => {
  const { env } = setup();
  const response = await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa");
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.match(body.device_key, /^[a-f0-9]{64}$/);
  assert.equal(body.changed, true);
  assert.equal(body.summary.addons, 2);
  assert.equal(body.summary.plugins, 0, "missing plugins table is tolerated");

  const config = await call(env, "/v1/config", { headers: { "X-KK-Device-Id": "tv-livingroom-0001", Authorization: `Bearer ${body.device_key}` } });
  assert.equal(config.status, 200);
  const payload = await config.json();
  assert.equal(payload.schema, "kk-backup-v1");
  assert.equal(payload.account_email, OWNER);
  assert.equal(payload.surfaces.addons.length, 2);
  assert.equal(payload.surfaces.profile_settings_tv[0].settings_json.platform, "tv");
  assert.equal(payload.surfaces.profile_settings_mobile[0].settings_json.platform, "mobile");
  assert.equal(payload.surfaces.plugins, null);

  const stored = env.DB.sqlite.prepare("SELECT key_hash FROM devices").get();
  assert.notEqual(stored.key_hash, body.device_key, "only a hash of the key is stored");
});

test("a quick second sync is skipped and keeps the same key", async () => {
  const { env, state } = setup();
  const first = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  const callsAfterFirst = state.calls;
  const second = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_key: first.device_key })).json();
  assert.equal(second.skipped, true);
  assert.equal(second.device_key, null, "existing key stays valid");
  assert.equal(state.calls - callsAfterFirst, 1, "only the sign-in check hit Nuvio");
});

test("changes in Nuvio become the copy in use; unchanged data doesn't pile up", async () => {
  const { env, state } = setup();
  const first = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  ageCurrent(env);
  const same = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_key: first.device_key })).json();
  assert.equal(same.changed, false);

  state.addons = [...state.addons, { url: "https://c/manifest.json", sort_order: 2 }];
  ageCurrent(env);
  const changed = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_key: first.device_key })).json();
  assert.equal(changed.changed, true);
  assert.equal(changed.held_back, false);
  assert.equal(env.DB.sqlite.prepare("SELECT COUNT(*) AS n FROM snapshots").get().n, 2);
  const current = env.DB.sqlite.prepare("SELECT summary FROM snapshots s JOIN current_snapshot c ON c.snapshot_id = s.id").get();
  assert.equal(JSON.parse(current.summary).addons, 3);
});

test("a wiped account is saved but never put in use", async () => {
  const { env, state } = setup();
  const first = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  state.addons = [];
  state.rows = [];
  ageCurrent(env);
  const wiped = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_key: first.device_key })).json();
  assert.equal(wiped.changed, true);
  assert.equal(wiped.held_back, true);
  const config = await (await call(env, "/v1/config", { headers: { "X-KK-Device-Id": "tv-livingroom-0001", Authorization: `Bearer ${first.device_key}` } })).json();
  assert.equal(config.surfaces.addons.length, 2, "devices still get the good copy");
});

test("a locked copy stays in use", async () => {
  const { env, state } = setup();
  const first = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  env.DB.sqlite.prepare("UPDATE current_snapshot SET locked = 1").run();
  state.addons = [{ url: "https://only/manifest.json", sort_order: 0 }];
  ageCurrent(env);
  const next = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_key: first.device_key })).json();
  assert.equal(next.held_back, true);
});

test("the two logins keep separate copies", async () => {
  const { env } = setup();
  const owner = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  const customer = await (await sync(env, "token-customer-aaaaaaaaaaaaaaaaa", { device_id: "customer-tv-0001" })).json();
  const config = await (await call(env, "/v1/config", { headers: { "X-KK-Device-Id": "customer-tv-0001", Authorization: `Bearer ${customer.device_key}` } })).json();
  assert.equal(config.account_email, CUSTOMER);
  const wrongKey = await call(env, "/v1/config", { headers: { "X-KK-Device-Id": "customer-tv-0001", Authorization: `Bearer ${owner.device_key}` } });
  assert.equal(wrongKey.status, 401);
});

test("bad sign-ins and other accounts are refused", async () => {
  const { env } = setup();
  assert.equal((await sync(env, "not-a-real-token-aaaaaaaaaaaaaa")).status, 401);
  assert.equal((await sync(env, "token-stranger-aaaaaaaaaaaaaaaaa")).status, 403);
  assert.equal((await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_id: "bad id!" })).status, 400);
  assert.equal(env.DB.sqlite.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 0);
});

test("a Nuvio outage stores nothing and reports 502", async () => {
  const { env, state } = setup();
  state.down = true;
  const response = await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa");
  assert.equal(response.status, 502);
  assert.equal(env.DB.sqlite.prepare("SELECT COUNT(*) AS n FROM snapshots").get().n, 0);
});

test("ping keeps the device list fresh while Nuvio is down", async () => {
  const { env, state } = setup();
  const { device_key } = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  env.DB.sqlite.prepare("UPDATE devices SET last_seen_at = 1").run();
  state.down = true;
  const response = await call(env, "/v1/ping", { method: "POST", headers: { "X-KK-Device-Id": "tv-livingroom-0001", Authorization: `Bearer ${device_key}` }, body: { app_version: "1.1.8" } });
  assert.equal(response.status, 200);
  const row = env.DB.sqlite.prepare("SELECT last_seen_at, app_version FROM devices").get();
  assert.ok(row.last_seen_at > 1);
  assert.equal(row.app_version, "1.1.8");
});

test("blocked devices can't restore or re-register", async () => {
  const { env } = setup();
  const { device_key } = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  env.DB.sqlite.prepare("UPDATE devices SET revoked_at = 1").run();
  assert.equal((await call(env, "/v1/config", { headers: { "X-KK-Device-Id": "tv-livingroom-0001", Authorization: `Bearer ${device_key}` } })).status, 401);
  assert.equal((await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).status, 403);
});

test("restore PIN sign-in, with brute-force lockout", async () => {
  const { env } = setup();
  await sync(env, "token-customer-aaaaaaaaaaaaaaaaa", { device_id: "customer-tv-0001" });
  const pinLogin = (pin, device = "new-stick-0001", email = CUSTOMER) =>
    call(env, "/v1/pin-login", { method: "POST", body: { email, pin, device_id: device, platform: "tv" } });

  assert.equal((await pinLogin("000000")).status, 401);
  assert.equal((await pinLogin("482913", "new-stick-0001", "someone@else.com")).status, 401);
  const ok = await pinLogin("482913");
  assert.equal(ok.status, 200);
  const { device_key } = await ok.json();
  const config = await (await call(env, "/v1/config", { headers: { "X-KK-Device-Id": "new-stick-0001", Authorization: `Bearer ${device_key}` } })).json();
  assert.equal(config.account_email, CUSTOMER);

  for (let i = 0; i < 5; i++) await pinLogin("111111", "attacker-0001");
  assert.equal((await pinLogin("482913", "attacker-0001")).status, 429, "locked out even with the right PIN");

  const off = setup({ RESTORE_PIN: "" });
  assert.equal((await call(off.env, "/v1/pin-login", { method: "POST", body: { email: CUSTOMER, pin: "", device_id: "x-device-01" } })).status, 503);
});

test("owner page needs the password and lists devices", async () => {
  const { env } = setup();
  await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa");
  assert.equal((await call(env, "/admin")).status, 401);
  const auth = { Authorization: `Basic ${btoa("owner:owner-password-1")}` };
  assert.equal((await call(env, "/admin", { headers: { Authorization: `Basic ${btoa("owner:wrong")}` } })).status, 401);
  const page = await call(env, "/admin", { headers: auth });
  assert.equal(page.status, 200);
  const html = await page.text();
  assert.match(html, /Living room/);
  assert.match(html, /2 add-ons/);
  assert.match(html, /kablekillers6@gmail\.com/);
  assert.doesNotMatch(html, /https:\/\/a\/manifest/, "add-on URLs are not shown on the page");

  const noAdmin = setup({ ADMIN_PASSWORD: undefined });
  assert.equal((await call(noAdmin.env, "/admin")).status, 503);
});

test("owner actions need the form token; blocking works", async () => {
  const { env } = setup();
  await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa");
  const auth = { Authorization: `Basic ${btoa("owner:owner-password-1")}` };
  const html = await (await call(env, "/admin", { headers: auth })).text();
  const csrf = /name="csrf" value="([a-f0-9]+)"/.exec(html)[1];
  const post = (fields, headers = {}) => worker.fetch(new Request("https://kk-backup.test/admin/action", {
    method: "POST", headers: { ...auth, ...headers }, body: new URLSearchParams(fields),
  }), env);

  assert.equal((await post({ action: "block", device: "tv-livingroom-0001", csrf: "nope" })).status, 403);
  assert.equal((await post({ action: "block", device: "tv-livingroom-0001", csrf }, { Origin: "https://evil.test" })).status, 403);
  assert.equal((await post({ action: "block", device: "tv-livingroom-0001", csrf })).status, 303);
  assert.ok(env.DB.sqlite.prepare("SELECT revoked_at FROM devices").get().revoked_at);
  assert.equal((await post({ action: "lock", account: OWNER, csrf })).status, 303);
  assert.equal(env.DB.sqlite.prepare("SELECT locked FROM current_snapshot").get().locked, 1);
});

test("old copies are pruned but the one in use is kept", async () => {
  const { env, state } = setup();
  const { device_key } = await (await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa")).json();
  env.DB.sqlite.prepare("UPDATE current_snapshot SET locked = 1").run();
  const firstId = env.DB.sqlite.prepare("SELECT snapshot_id FROM current_snapshot").get().snapshot_id;
  for (let i = 0; i < 45; i++) {
    state.addons = [{ url: `https://addon-${i}/manifest.json`, sort_order: 0 }];
    ageCurrent(env);
    env.DB.sqlite.prepare("UPDATE snapshots SET created_at = created_at - 1").run();
    await sync(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", { device_key });
  }
  const count = env.DB.sqlite.prepare("SELECT COUNT(*) AS n FROM snapshots").get().n;
  assert.ok(count <= 41, `kept ${count}`);
  assert.ok(env.DB.sqlite.prepare("SELECT id FROM snapshots WHERE id = ?").get(firstId), "locked copy survived pruning");
});
