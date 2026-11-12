# Owner dashboard API contract (development only)

All endpoints below require a valid Cloudflare Access JWT for the configured owner. All endpoints except `GET /health` remain disabled unless `ACTIVATION_API_ENABLED=true`; **do not enable this setting yet**.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /v1/admin/activations/pending | List up to 100 unexpired pending pairing codes, newest first |
| POST | /v1/admin/activations/approve | Body: `{"pairing_code":"123456"}`; approves one pending unexpired challenge |
| GET | /v1/admin/devices | List up to 100 registered devices with household and revocation status |
| POST | /v1/admin/devices/revoke | Body: `{"device_id":"UUID"}`; revoke device and credential |
| POST | /v1/activations | Create short-lived challenge (prototype) |
| POST | /v1/activations/:id | Poll challenge with private poll secret (prototype) |

**Important:** This is not a customer-ready service. The current approval operation does not create device credentials. Credential helpers are not connected to the poll endpoint. No administrator web interface exists. The device list is not paginated and there is no audit log, request throttling, token refresh or completed household allocation policy.

Before activation:
1. Add per-IP and per-device challenge creation throttling and abuse limits.
2. Enforce Cloudflare Access owner-only MFA policies at the edge; test JWT signature and policy behavior.
3. Add owner confirmation with QR pairing details; never approve based solely on a code copied from an untrusted request.
4. Connect approved challenges to single-use credential issuance, with trusted server-assigned household identity.
5. Test D1 atomicity and concurrency; prevent duplicate issuance.
6. Add token refresh and revocation tests, device-level and household-level isolation.
7. Add an audit trail and CSRF-safe admin UI; verify all state-changing endpoints.
8. Keep production Nuvio login unchanged until test devices pass end-to-end tests.
