// Cloudflare Access owner authentication for private administrative routes.
// Never trust client-provided email headers without validating the Access JWT.
const utf8 = new TextEncoder();
const decode = (s) => {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  const padded = b64.padEnd(Math.ceil(b64.length / 4) * 4, "=");
  return Uint8Array.from(atob(padded), c => c.charCodeAt(0));
};
const parse = (s) => JSON.parse(new TextDecoder().decode(decode(s)));

export async function verifyOwnerAccess(request, env, now = Math.floor(Date.now() / 1000)) {
  const token = request.headers.get("Cf-Access-Jwt-Assertion");
  const team = env.ACCESS_TEAM_DOMAIN;
  const audience = env.ACCESS_AUD;
  const owner = env.OWNER_EMAIL;
  if (!token || !team || !audience || !owner) return null;
  if (!/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(team)) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = parse(parts[0]);
    const claims = parse(parts[1]);
    if (header.alg !== "RS256" || typeof header.kid !== "string") return null;
    if (claims.iss !== team || !Array.isArray(claims.aud) && claims.aud !== audience) return null;
    if (Array.isArray(claims.aud) && !claims.aud.includes(audience)) return null;
    if (!Number.isInteger(claims.exp) || claims.exp <= now) return null;
    if (!Number.isInteger(claims.nbf) || claims.nbf > now) return null;
    if (typeof claims.email !== "string" || claims.email.toLowerCase() !== owner.toLowerCase()) return null;
    const response = await fetch(team + "/cdn-cgi/access/certs", { cf: { cacheTtl: 300, cacheEverything: true } });
    if (!response.ok) return null;
    const keys = (await response.json()).keys;
    const jwk = keys?.find(k => k.kid === header.kid && k.kty === "RSA" && (!k.alg || k.alg === "RS256"));
    if (!jwk) return null;
    const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    const signature = decode(parts[2]);
    const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, utf8.encode(parts[0] + "." + parts[1]));
    return valid ? { email: claims.email, subject: claims.sub } : null;
  } catch {
    return null;
  }
}
