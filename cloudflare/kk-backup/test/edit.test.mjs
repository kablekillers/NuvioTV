import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { createD1 } from "./d1-shim.mjs";

const CUSTOMER = "kablekillers6@gmail.com";
const OWNER = "millertime42085@gmail.com";
const TOKENS = {
  "token-customer-aaaaaaaaaaaaaaaaa": { id: "user-customer", email: CUSTOMER },
  "token-owner-aaaaaaaaaaaaaaaaaaaa": { id: "user-owner", email: OWNER },
};

function setup() {
  const state = { down: false, addons: [{ url: "https://a/manifest.json", sort_order: 0 }], pushedToNuvio: false };
  globalThis.fetch = async (input, init = {}) => {
    if (state.down) throw new TypeError("fetch failed");
    const url = new URL(typeof input === "string" ? input : input.url);
    const user = TOKENS[(init.headers?.Authorization || "").replace("Bearer ", "")];
    if (!user) return new Response("{}", { status: 401 });
    const reply = (b) => new Response(JSON.stringify(b), { status: 200 });
    switch (url.pathname) {
      case "/auth/v1/user": return reply({ id: user.id, email: user.email });
      case "/rest/v1/rpc/get_sync_owner": return reply(user.id);
      case "/rest/v1/addons": return reply(state.addons);
      case "/rest/v1/rpc/sync_pull_home_catalog_settings": return reply([{ profile_id: 1, settings_json: { items: [{ addon_id: "a", order: 0 }] } }]);
      case "/rest/v1/rpc/sync_pull_profiles": return reply([{ profile_index: 1, name: "Main" }]);
      default: return reply([]);
    }
  };
  const env = { DB: createD1(), SUPABASE_URL: "https://nuvio.example.co", SUPABASE_ANON_KEY: "anon-key", ADMIN_PASSWORD: "owner-password-1", RESTORE_PIN: "482913" };
  return { env, state };
}

const call = (env, path, { method = "GET", body, headers = {} } = {}) => worker.fetch(new Request(`https://kk.test${path}`, {
  method, headers: { "Content-Type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
}), env);

async function connect(env, token, deviceId, extra = {}) {
  const r = await call(env, "/v1/sync", { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: { device_id: deviceId, platform: "tv", force: true, ...extra } });
  return { status: r.status, json: await r.json() };
}
const asDevice = (deviceId, key) => ({ Authorization: `Bearer ${key}`, "X-KK-Device-Id": deviceId });
const config = async (env, id, key) => (await call(env, "/v1/config", { headers: asDevice(id, key) })).json();
const NEW_ADDONS = [{ url: "https://a/manifest.json", sort_order: 0 }, { url: "https://new/manifest.json", sort_order: 1, name: "New" }];

test("customer login: changing Profile 1 needs the restore PIN", async () => {
  const { env } = setup();
  const { json: c } = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-owner-custlogin1");
  const key = c.device_key;
  const denied = await call(env, "/v1/push", { method: "POST", headers: asDevice("tv-owner-custlogin1", key), body: { surfaces: { addons: NEW_ADDONS } } });
  assert.equal(denied.status, 403);

  const wrong = await call(env, "/v1/unlock", { method: "POST", headers: asDevice("tv-owner-custlogin1", key), body: { pin: "000000" } });
  assert.equal(wrong.status, 401);
  const unlock = await (await call(env, "/v1/unlock", { method: "POST", headers: asDevice("tv-owner-custlogin1", key), body: { pin: "482913" } })).json();
  assert.ok(unlock.edit_token);

  // A token from one device doesn't work on another.
  const { json: other } = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-customer-house22");
  const stolen = await call(env, "/v1/push", { method: "POST", headers: asDevice("tv-customer-house22", other.device_key), body: { edit_token: unlock.edit_token, surfaces: { addons: NEW_ADDONS } } });
  assert.equal(stolen.status, 403);

  const pushed = await (await call(env, "/v1/push", { method: "POST", headers: asDevice("tv-owner-custlogin1", key), body: { edit_token: unlock.edit_token, surfaces: { addons: NEW_ADDONS, profiles: [] } } })).json();
  assert.equal(pushed.changed, true);
  assert.equal(pushed.held_back, false);

  // Every device on the login now gets the new copy; untouched parts (rows, profiles) stay.
  const copy = await config(env, "tv-customer-house22", other.device_key);
  assert.equal(copy.origin, "device");
  assert.equal(copy.surfaces.addons.length, 2);
  assert.equal(copy.surfaces.home_catalog_settings.length, 1);
  assert.equal(copy.surfaces.profiles.length, 1);
  const ping = await (await call(env, "/v1/ping", { method: "POST", headers: asDevice("tv-customer-house22", other.device_key), body: {} })).json();
  assert.equal(ping.snapshot_id, pushed.snapshot_id);
});

test("owner's personal login can change its Profile 1 without the PIN", async () => {
  const { env } = setup();
  const { json: c } = await connect(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", "tv-owner-personal1");
  const r = await call(env, "/v1/push", { method: "POST", headers: asDevice("tv-owner-personal1", c.device_key), body: { surfaces: { addons: NEW_ADDONS } } });
  assert.equal(r.status, 200);
});

test("a push that wipes everything is kept aside, not used", async () => {
  const { env } = setup();
  const { json: c } = await connect(env, "token-owner-aaaaaaaaaaaaaaaaaaaa", "tv-owner-personal1");
  const r = await (await call(env, "/v1/push", { method: "POST", headers: asDevice("tv-owner-personal1", c.device_key), body: { surfaces: { addons: [] } } })).json();
  assert.equal(r.held_back, true);
});

test("after an outage, Nuvio's old copy waits until a device sends the changes up to Nuvio", async () => {
  const { env, state } = setup();
  const { json: owner } = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-owner-custlogin1");
  const { json: cust } = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-customer-house22");
  state.down = true;
  const unlock = await (await call(env, "/v1/unlock", { method: "POST", headers: asDevice("tv-owner-custlogin1", owner.device_key), body: { pin: "482913" } })).json();
  const pushed = await (await call(env, "/v1/push", { method: "POST", headers: asDevice("tv-owner-custlogin1", owner.device_key), body: { edit_token: unlock.edit_token, surfaces: { addons: NEW_ADDONS } } })).json();
  state.down = false; // Nuvio is back, still with the old single add-on

  // A customer device (not on Profile 1) syncs first: Nuvio's old copy is kept aside.
  const first = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-customer-house22", { device_key: cust.device_key, can_upload: false });
  assert.equal(first.json.held_back, true);
  assert.deepEqual(first.json.outage_changes, { snapshot_id: pushed.snapshot_id, upload: false });
  assert.equal((await config(env, "tv-customer-house22", cust.device_key)).surfaces.addons.length, 2);

  // The owner's device on Profile 1, which has the changes, gets the upload job (only one device at a time).
  const job = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-owner-custlogin1", { device_key: owner.device_key, can_upload: true, applied_snapshot_id: pushed.snapshot_id });
  assert.equal(job.json.outage_changes.upload, true);
  const second = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-customer-house22", { device_key: cust.device_key, can_upload: true, applied_snapshot_id: pushed.snapshot_id });
  assert.equal(second.json.outage_changes.upload, false);

  // It sends them to Nuvio, then reports back: Nuvio's copy is in use again.
  state.addons = NEW_ADDONS;
  const done = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-owner-custlogin1", { device_key: owner.device_key, uploaded_snapshot_id: pushed.snapshot_id });
  assert.equal(done.json.outage_changes, null);
  const copy = await config(env, "tv-customer-house22", cust.device_key);
  assert.equal(copy.origin, "nuvio");
  assert.equal(copy.surfaces.addons.length, 2);
});

test("unlock tries are limited", async () => {
  const { env } = setup();
  const { json: c } = await connect(env, "token-customer-aaaaaaaaaaaaaaaaa", "tv-owner-custlogin1");
  for (let i = 0; i < 5; i++) await call(env, "/v1/unlock", { method: "POST", headers: asDevice("tv-owner-custlogin1", c.device_key), body: { pin: "111111" } });
  const r = await call(env, "/v1/unlock", { method: "POST", headers: asDevice("tv-owner-custlogin1", c.device_key), body: { pin: "482913" } });
  assert.equal(r.status, 429);
});
