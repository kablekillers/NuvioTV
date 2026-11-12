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
    // Fail closed: public activation routes remain disabled until owner MFA,\n    // request throttling, credential issuance and revocation are implemented.\n    if (env.ACTIVATION_API_ENABLED !== "true")\n      return reply({ error: "Activation service is not enabled" }, 503);\n    if (!env.DB) return reply({ error: "Database unavailable" }, 503);
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
      return reply({ state: row.state });
    }
    return reply({ error: "Not found" }, 404);
  }
};
