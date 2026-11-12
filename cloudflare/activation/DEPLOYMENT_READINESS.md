# Cloudflare activation deployment readiness

This folder is an **incomplete prototype**, not a production device-login service.
Current safe default: `/health` returns `{"status":"ok"}`; every other route returns HTTP 503 unless the explicit `ACTIVATION_API_ENABLED=true` environment variable is configured. **Do not enable it** until security gates below pass.

## Repository and branch

- GitHub repository: `kablekillers/NuvioTV`
- Isolated branch: `feature/independent-config-backup`
- Worker project name: `kable-killers-activation`
- Worker source: `cloudflare/activation/src/index.js`
- Wrangler config: `cloudflare/activation/wrangler.toml`
- D1 schema: `cloudflare/activation/schema.sql`

Cloudflare's Git-connected initial setup may select the repository default branch, which **does not contain these files**. Do not deploy the default branch with the activation command. Verify branch selection after connecting the project, or use a separate deployment workflow that explicitly checks out the feature branch.

## Prerequisites before any deployment

1. Create a new D1 database called `kable-killers-activation`; never reuse the existing sports Worker database.
2. Replace `REPLACE_WITH_YOUR_PRIVATE_D1_DATABASE_ID` in `wrangler.toml` with the new D1 database ID. This ID is an identifier, not a password.
3. Apply `schema.sql` to **that new database only**.
4. Ensure Git deployment is restricted to the intended branch and the project uses the correct config path.
5. Keep `ACTIVATION_API_ENABLED` unset. Do not paste API tokens, passwords, or private addon URLs into GitHub or chat.
6. Confirm `/health` works and `/v1/activations` returns HTTP 503. Do not onboard any customers.

## Security gates before activation can be enabled

- Owner-only MFA-protected approval and denial endpoints
- Rate limiting for challenge creation and polling, plus abuse controls
- Cryptographically strong single-use device credentials and refresh rotation
- Device/household authorization boundaries and revocation checks
- Verified QR/short-code matching, replay/expiry tests, admin audit trail
- Private versioned master configuration distribution with rollback
- End-to-end tests on an isolated test TV, including revoked/expired sessions
- Explicit owner approval before production cutover

## Important

`verification_url` in the prototype points to `/activate`, but no activation UI exists yet. The current code does **not** implement device sign-in. Cloudflare Workers deployment alone does not replace Nuvio's login or cloud services.
