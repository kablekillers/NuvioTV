import test from "node:test";
import assert from "node:assert/strict";
import { verifyOwnerAccess } from "../src/owner_auth.js";

test("owner authentication fails closed without an Access JWT", async () => {
  const request = new Request("https://example.test/v1/admin/devices");
  const result = await verifyOwnerAccess(request, {
    ACCESS_TEAM_DOMAIN: "https://team.cloudflareaccess.com",
    ACCESS_AUD: "expected-audience",
    OWNER_EMAIL: "owner@example.test"
  });
  assert.equal(result, null);
});

test("owner authentication rejects malformed JWT without fetching keys", async () => {
  const request = new Request("https://example.test/v1/admin/devices", {
    headers: { "Cf-Access-Jwt-Assertion": "not-a-valid-token" }
  });
  const result = await verifyOwnerAccess(request, {
    ACCESS_TEAM_DOMAIN: "https://team.cloudflareaccess.com",
    ACCESS_AUD: "expected-audience",
    OWNER_EMAIL: "owner@example.test"
  });
  assert.equal(result, null);
});

test("owner authentication rejects missing configuration", async () => {
  const request = new Request("https://example.test/v1/admin/devices", {
    headers: { "Cf-Access-Jwt-Assertion": "a.b.c" }
  });
  assert.equal(await verifyOwnerAccess(request, {}), null);
});
