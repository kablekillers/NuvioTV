import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { createD1 } from "./d1-shim.mjs";

const OWNER = "millertime42085@gmail.com";
const CONF = `[Interface]
PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=
Address = 10.0.0.2/32
DNS = 198.18.0.1

[Peer]
PublicKey = d29ybGQtd29ybGQtd29ybGQtd29ybGQtd29ybGQtMTI=
AllowedIPs = 0.0.0.0/0
Endpoint = chi-a01.wg.ipvanish.com:51820
`;

function setup() {
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    const ok = (b) => new Response(JSON.stringify(b), { status: 200 });
    if (url.pathname === "/auth/v1/user") return ok({ id: "user-owner", email: OWNER });
    if (url.pathname === "/rest/v1/rpc/get_sync_owner") return ok("user-owner");
    if (url.pathname === "/rest/v1/addons") return ok([]);
    if (url.pathname.startsWith("/rest/v1/rpc/sync_pull_")) return ok([]);
    return new Response("{}", { status: 404 });
  };
  return {
    DB: createD1(), SUPABASE_URL: "https://nuvio.example.co", SUPABASE_ANON_KEY: "anon-key",
    ADMIN_PASSWORD: "owner-password-1", RESTORE_PIN: "482913",
  };
}

const call = (env, path, { method = "GET", body, headers = {} } = {}) => worker.fetch(new Request(`https://kk-backup.test${path}`, {
  method, headers: { "Content-Type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
}), env);

test("owner sends an IPVanish WireGuard file to one TV; the TV collects it once", async () => {
  const env = setup();
  const synced = await (await call(env, "/v1/sync", {
    method: "POST", headers: { Authorization: "Bearer token-owner-aaaaaaaaaaaaaaaaaaaa" },
    body: { device_id: "tv-livingroom-0001", device_name: "Living room", platform: "tv", app_version: "1.1.15" },
  })).json();
  const device = { "X-KK-Device-Id": "tv-livingroom-0001", Authorization: `Bearer ${synced.device_key}` };
  const auth = { Authorization: `Basic ${btoa("owner:owner-password-1")}` };
  const html = await (await call(env, "/admin", { headers: auth })).text();
  assert.match(html, /Send VPN to this TV/);
  const csrf = /name="csrf" value="([a-f0-9]+)"/.exec(html)[1];
  const post = (fields) => worker.fetch(new Request("https://kk-backup.test/admin/action", {
    method: "POST", headers: auth, body: new URLSearchParams(fields),
  }), env);

  assert.equal((await post({ action: "vpn_send", device: "tv-livingroom-0001", conf: "not a config", csrf })).status, 400);
  assert.equal((await post({ action: "vpn_send", device: "tv-livingroom-0001", conf: CONF, csrf })).status, 303);
  const stored = env.DB.sqlite.prepare("SELECT payload FROM vpn_pushes").get().payload;
  assert.ok(!stored.includes("PrivateKey"), "the file is encrypted while it waits");

  const first = await (await call(env, "/v1/vpn", { method: "POST", headers: device, body: { vpn_profile: false } })).json();
  assert.equal(first.action, "set");
  assert.equal(first.conf, CONF.trim());
  const second = await (await call(env, "/v1/vpn", { method: "POST", headers: device, body: { vpn_profile: true, vpn_on: true } })).json();
  assert.equal(second.action, undefined, "collected once, then deleted");
  assert.equal(env.DB.sqlite.prepare("SELECT COUNT(*) AS n FROM vpn_pushes").get().n, 0);
  assert.match(await (await call(env, "/admin", { headers: auth })).text(), /pill ok">On</);

  assert.equal((await post({ action: "vpn_remove", device: "tv-livingroom-0001", csrf })).status, 303);
  const removal = await (await call(env, "/v1/vpn", { method: "POST", headers: device, body: {} })).json();
  assert.equal(removal.action, "remove");

  assert.equal((await call(env, "/v1/vpn", { method: "POST", headers: { "X-KK-Device-Id": "tv-livingroom-0001", Authorization: "Bearer wrong" }, body: {} })).status, 401);
});
