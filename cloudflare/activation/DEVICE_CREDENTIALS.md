# Device credentials: integration checklist

Implemented in `src/device_credentials.js` (not connected to HTTP routes).

- `issueDeviceCredential` attempts a single-use transition of an approved, unexpired activation into a device record and hashed bearer credential, using a D1 batch. It returns a token only if all three statements report one affected row.
- `authenticateDevice` accepts only an unexpired, unrevoked token associated with an unrevoked device and returns its household ID.
- `revokeDevice` marks both device and token revoked.
- `schema.sql` defines `device_credentials`.

**Not production-ready:** no API integration, no tests against real D1, no token rotation or refresh, no per-device/household owner assignment policy, no rate limiting, no audit trail, no MFA dashboard. Token validity is currently 30 days and expiry would require reactivation. Household ID must be allocated by the trusted server, never accepted unverified from a TV. Do not expose `issueDeviceCredential` to unauthenticated callers without verifying the private poll secret.

The D1 batch must be tested for rollback/partial-update behavior and concurrency. The service must fail closed on ambiguous outcomes. `ACTIVATION_API_ENABLED` must remain unset. No customer migration is authorized.
