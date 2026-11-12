# Owner authentication setup — NOT READY FOR PRODUCTION

Code:
- `src/owner_auth.js` validates Cloudflare Access RS256 JWT signatures against the team's JWKS, issuer, audience, expiration, not-before, and exact owner email.
- `POST /v1/admin/activations/approve` accepts `{"pairing_code":"123456"}` **only** after Access JWT verification. It atomically approves a pending unexpired challenge.
- All activation/admin routes remain disabled by default unless `ACTIVATION_API_ENABLED=true` is explicitly set. **Do not enable yet.**

Future Cloudflare Access configuration (after security review):
1. Create a Cloudflare Access application covering the Worker administrative routes. Require owner-only access and MFA in the Access policy. The JWT verifier does not itself enforce MFA.
2. Set Worker secrets/variables `ACCESS_TEAM_DOMAIN` (e.g. `https://your-team.cloudflareaccess.com`), `ACCESS_AUD` (the application audience), and `OWNER_EMAIL` (the owner's verified email). Do not put secrets or real tokens in source control.
3. Protect administrative routes at the Cloudflare edge; the JWT check is defense-in-depth, not a substitute for Access policy.
4. Test denial for missing/forged/wrong-audience/expired JWTs and wrong-owner identity.
5. Add audit logs, CSRF/origin policy, approval rate limits, device credentials, revocation and a safe owner confirmation UI before enabling activation.

**Current limitations:** No dashboard, no device credential issuance, no device registration upon approval, no rate limiting, no deployed infrastructure, and no verified tests. The approval endpoint is a development building block only.
