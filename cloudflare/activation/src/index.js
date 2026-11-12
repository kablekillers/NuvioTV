import { verifyOwnerAccess } from "./owner_auth.js";
import { revokeDevice, issueDeviceCredential } from "./device_credentials.js";
// Minimal public activation API. Owner approval is deliberately unavailable
// until an MFA-protected admin identity and authorization layer is implemented.
const encoder = new TextEncoder();
const hex = (bytes) => [...bytes].map(x => x.toString(16).padStart(2, "0")).join("");
const random = (n) => { const b = new Uint8Array(n); crypto.getRandomValues(b); return hex(b); };
async function sha256(s) { return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(s)))); }
const reply = (body, status = 200) => Response.json(body, {
  status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" }
});
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") return reply({ status: "ok" });
    // Fail closed until owner MFA, rate limits and revocation are tested.
    if (env.ACTIVATION_API_ENABLED !== "true")
      return reply({ error: "Activation service is not enabled" }, 503);
    if (!env.DB) return reply({ error: "Database unavailable" }, 503);
    if (url.pathname === "/v1/admin/activations/pending" && request.method === "GET") {
      const owner = await verifyOwnerAccess(request, env);
      if (!owner) return reply({ error: "Forbidden" }, 403);
      const now = Math.floor(Date.now() / 1000);
      const rows = await env.DB.prepare(
        "SELECT id,pairing_code,created_at,expires_at FROM activation_challenges WHERE state='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 100"
      ).bind(now).all();
      return reply({ activations: rows.results || [] });
    }
    if (url.pathname === "/v1/admin/devices" && request.method === "GET") {
      const owner = await verifyOwnerAccess(request, env);
      if (!owner) return reply({ error: "Forbidden" }, 403);
      const rows = await env.DB.prepare(
        "SELECT id,household_id,display_name,created_at,last_seen_at,revoked_at FROM registered_devices ORDER BY created_at DESC LIMIT 100"
      ).all();
      return reply({ devices: rows.results || [] });
    }
    if (url.pathname === "/v1/admin/devices/revoke" && request.method === "POST") {
      const owner = await verifyOwnerAccess(request, env);
      if (!owner) return reply({ error: "Forbidden" }, 403);
      let body;
      try { body = await request.json(); } catch { return reply({ error: "Invalid JSON" }, 400); }
      if (typeof body?.device_id !== "string" || !/^[0-9a-f-]{36}$/.test(body.device_id))
        return reply({ error: "Invalid device ID" }, 400);
      const revoked = await revokeDevice(env.DB, body.device_id);
      return reply({ revoked }, revoked ? 200 : 404);
    }
    if (url.pathname === "/v1/admin/activations/approve" && request.method === "POST") {
      const owner = await verifyOwnerAccess(request, env);
      if (!owner) return reply({ error: "Forbidden" }, 403);
      let body;
      try { body = await request.json(); } catch { return reply({ error: "Invalid JSON" }, 400); }
      if (typeof body?.pairing_code !== "string" || !/^\d{6}$/.test(body.pairing_code))
        return reply({ error: "Invalid pairing code" }, 400);
      if (typeof body?.household_id !== "string" || !/^[a-zA-Z0-9_-]{1,80}$/.test(body.household_id))
        return reply({ error: "Invalid household ID" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await env.DB.prepare(
        "UPDATE activation_challenges SET state='approved',approved_at=?,approved_household_id=? WHERE pairing_code=? AND state='pending' AND expires_at>?"
      ).bind(now, body.household_id, body.pairing_code, now).run();
      return reply({ approved: result.meta.changes === 1 }, result.meta.changes === 1 ? 200 : 409);
    }
    if (url.pathname === "/v1/activations" && request.method === "POST") {
      const id = random(24);
      const secret = random(32);
      const hash = await sha256(secret);
      const now = Math.floor(Date.now() / 1000);
      // A six-digit code is a lookup hint only, never an authorization token.
      const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
      try {
        await env.DB.prepare("INSERT INTO activation_challenges (id,poll_secret_hash,pairing_code,state,created_at,expires_at) VALUES (?,?,?,'pending',?,?)")
          .bind(id, hash, code, now, now + 300).run();
      } catch {
        return reply({ error: "Unable to create activation" }, 503);
      }
      return reply({ id, poll_secret: secret, pairing_code: code, expires_at: now + 300,
        verification_url: new URL("/activate?code=" + code, url.origin).toString() }, 201);
    }
    const match = /^\/v1\/activations\/([a-f0-9]{48})$/.exec(url.pathname);
    if (match && request.method === "POST") {
      let body;
      try { body = await request.json(); } catch { return reply({ error: "Invalid JSON" }, 400); }
      if (typeof body?.poll_secret !== "string" || !/^[a-f0-9]{64}$/.test(body.poll_secret))
        return reply({ error: "Invalid credentials" }, 401);
      const row = await env.DB.prepare("SELECT state,expires_at,poll_secret_hash FROM activation_challenges WHERE id=?")
        .bind(match[1]).first();
      if (!row || row.poll_secret_hash !== await sha256(body.poll_secret))
        return reply({ error: "Invalid credentials" }, 401);
      if (row.expires_at <= Math.floor(Date.now() / 1000)) return reply({ state: "expired" });
      if (row.state === "approved") {
        const credential = await issueDeviceCredential(env.DB, {
          challengeId: match[1], challengeSecretHash: await sha256(body.poll_secret)
        });
        return credential ? reply({ state: "activated", credential }) : reply({ state: "unavailable" }, 409);
      }
      return reply({ state: row.state });
    }
    return reply({ error: "Not found" }, 404);
  }
};
