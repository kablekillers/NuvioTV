# Owner dashboard API contract (development only)

All endpoints below require a valid Cloudflare Access JWT for the configured owner. All endpoints except `GET /health` remain disabled unless `ACTIVATION_API_ENABLED=true`; **do not enable this setting yet**.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /v1/admin/activations/pending | List up to 100 unexpired pending pairing codes, newest first |
| POST | /v1/admin/activations/approve | Body: `{"pairing_code":"123456","household_id":"trusted_owner_selected_id"}`; approves one pending unexpired challenge |
| GET | /v1/admin/devices | List up to 100 registered devices with household and revocation status |
| POST | /v1/admin/devices/revoke | Body: `{"device_id":"UUID"}`; revoke device and credential |
| POST | /v1/activations | Create short-lived challenge (prototype) |
| POST | /v1/activations/:id | Poll challenge with private poll secret (prototype) |

**Important:** This is not a customer-ready service. The approved challenge is now connected to single-use device credential issuance on polling. This flow is not tested and has no retry/recovery path if the first token response is lost. No administrator web interface exists. The device list is not paginated and there is no audit log, request throttling, token refresh or completed household allocation policy.

Before activation:
1. Add per-IP and per-device challenge creation throttling and abuse limits.
2. Enforce Cloudflare Access owner-only MFA policies at the edge; test JWT signature and policy behavior.
3. Add owner confirmation with QR pairing details; never approve based solely on a code copied from an untrusted request.
4. Connect approved challenges to single-use credential issuance, with trusted server-assigned household identity.
5. Test D1 atomicity and concurrency; prevent duplicate issuance.
6. Add token refresh and revocation tests, device-level and household-level isolation.
7. Add an audit trail and CSRF-safe admin UI; verify all state-changing endpoints.
8. Keep production Nuvio login unchanged until test devices pass end-to-end tests.

## Activation implementation warning

The database schema now includes `activation_challenges.approved_household_id`. New databases should apply the updated schema; existing test databases would require a migration. Only the authenticated owner approval endpoint can set this value. A customer must never supply it. The polling endpoint returns a one-time token on approved activation; if the network drops the response after consumption, the user must restart activation until a secure recovery protocol is implemented. Do not turn on `ACTIVATION_API_ENABLED` or deploy to customers: this code has not passed runtime, D1 concurrency, abuse-prevention, and end-to-end tests.
