import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import worker from "../src/index.js";

globalThis.crypto ??= webcrypto;

const request = (path, method = "GET") => new Request("https://example.test" + path, { method });

test("health endpoint is available without a database", async () => {
  const response = await worker.fetch(request("/health"), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok" });
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("activation endpoints fail closed by default, even if a database is bound", async () => {
  const env = { DB: { prepare() { throw Error("should not touch DB"); } } };
  for (const path of ["/v1/activations", "/v1/admin/activations/pending", "/v1/admin/devices"]) {
    const response = await worker.fetch(request(path, path === "/v1/activations" ? "POST" : "GET"), env);
    assert.equal(response.status, 503, path);
    assert.deepEqual(await response.json(), { error: "Activation service is not enabled" });
  }
});

test("non-true activation flag cannot accidentally enable API", async () => {
  for (const flag of ["false", "TRUE", "1", "", undefined]) {
    const response = await worker.fetch(request("/v1/admin/devices"), { ACTIVATION_API_ENABLED: flag });
    assert.equal(response.status, 503);
  }
});

test("enabled API with no D1 binding returns unavailable", async () => {
  const response = await worker.fetch(request("/v1/admin/devices"), { ACTIVATION_API_ENABLED: "true" });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "Database unavailable" });
});
