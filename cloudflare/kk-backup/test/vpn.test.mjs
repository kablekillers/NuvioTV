import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { registerDevice } from "../src/store.js";
import { endpointHost, parseConfigs } from "../src/vpn.js";
import { createD1 } from "./d1-shim.mjs";

const conf = (n) => `[Interface]
PrivateKey = privkey${n}aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=
Address = 10.0.0.${n}/32
DNS = 198.18.0.1

[Peer]
PublicKey = pubkeyaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=
AllowedIPs = 0.0.0.0/0
Endpoint = atl-a0${n}.wg.ipvanish.com:51820`;

const AUTH = { Authorization: `Basic ${btoa("owner:owner-password-1")}` };

function setup() {
  return { DB: createD1(), ADMIN_PASSWORD: "owner-password-1", ALLOWED_ACCOUNTS: "kablekillers6@gmail.com" };
}

async function device(env, id) {
  const { key } = await registerDevice(env.DB, {
    deviceId: id, presentedKey: null, email: "kablekillers6@gmail.com",
    info: { name: id, model: "onn 4K", platform: "mobile", appVersion: "0.9.6" }, country: "US",
  });
  return { id, key };
}

const claim = (env, d) => worker.fetch(new Request("https://kk-backup.test/v1/vpn/claim", {
  method: "POST", headers: { "X-KK-Device-Id": d.id, Authorization: `Bearer ${d.key}`, "Content-Type": "application/json" }, body: "{}",
}), env);

async function csrfFor(env) {
  const html = await (await worker.fetch(new Request("https://kk-backup.test/admin", { headers: AUTH }), env)).text();
  return /name="csrf" value="([a-f0-9]+)"/.exec(html)[1];
}

async function upload(env, text) {
  const form = new FormData();
  form.set("csrf", await csrfFor(env));
  form.set("text", text);
  return worker.fetch(new Request("https://kk-backup.test/admin/vpn-upload", { method: "POST", headers: AUTH, body: form }), env);
}

const action = async (env, fields) => worker.fetch(new Request("https://kk-backup.test/admin/action", {
  method: "POST", headers: AUTH, body: new URLSearchParams({ ...fields, csrf: await csrfFor(env) }),
}), env);

test("pasted text with several configs is split and checked", () => {
  const { configs, rejected } = parseConfigs(`${conf(1)}\n\n${conf(2)}\n\n[Interface]\nnot a config`);
  assert.equal(configs.length, 2);
  assert.equal(rejected, 1);
  assert.equal(endpointHost(conf(1)), "atl-a01.wg.ipvanish.com");
});

test("each device gets its own config and keeps it", async () => {
  const env = setup();
  const page = await (await upload(env, `${conf(1)}\n${conf(2)}\n${conf(1)}`)).text();
  assert.match(page, /2 configs added · 1 already in the pool/);

  const a = await device(env, "phone-aaaaaaaa");
  const b = await device(env, "phone-bbbbbbbb");
  const c = await device(env, "phone-cccccccc");
  const first = await (await claim(env, a)).json();
  const again = await (await claim(env, a)).json();
  const second = await (await claim(env, b)).json();
  assert.equal(first.config_id, again.config_id, "same device keeps the same config");
  assert.notEqual(first.config_id, second.config_id, "different devices never share a key");
  assert.equal(first.label, "atl-a01.wg.ipvanish.com");

  const empty = await claim(env, c);
  assert.equal(empty.status, 404);
  assert.equal((await empty.json()).code, "pool_empty");
});

test("unknown devices get nothing", async () => {
  const env = setup();
  await upload(env, conf(1));
  const response = await claim(env, { id: "phone-zzzzzzzz", key: "nope" });
  assert.equal(response.status, 401);
});

test("taking a config back or blocking a device frees it for someone else", async () => {
  const env = setup();
  await upload(env, conf(1));
  const a = await device(env, "phone-aaaaaaaa");
  const b = await device(env, "phone-bbbbbbbb");
  const { config_id } = await (await claim(env, a)).json();
  assert.equal((await claim(env, b)).status, 404);

  assert.equal((await action(env, { action: "vpn-release", id: config_id })).status, 303);
  assert.equal((await (await claim(env, b)).json()).config_id, config_id);
  assert.equal((await claim(env, a)).status, 404, "old device no longer has it");

  assert.equal((await action(env, { action: "block", device: b.id })).status, 303);
  assert.equal((await (await claim(env, a)).json()).config_id, config_id);

  const html = await (await worker.fetch(new Request("https://kk-backup.test/admin", { headers: AUTH }), env)).text();
  assert.match(html, /Built-in VPN/);
  assert.doesNotMatch(html, /privkey1/, "private keys are never shown on the owner page");
});
