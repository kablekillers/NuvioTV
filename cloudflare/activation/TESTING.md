# Activation testing plan

## Run the first unit tests

From `cloudflare/activation`:

```sh
npm test
```

These tests use Node's built-in test runner and a mock D1 API. They do not contact Cloudflare, modify production devices, or require credentials.

## Real D1 integration tests still required

1. Create a dedicated disposable test D1 database and apply `schema.sql`.
2. Create a pending challenge; confirm it cannot issue a token.
3. Approve the challenge using a validated owner identity and server-selected household.
4. Poll with the correct secret; confirm exactly one device and credential are inserted.
5. Poll again; confirm a second token cannot be issued.
6. Send simultaneous polls and confirm exactly one token is issued.
7. Poll with wrong/expired secrets; confirm no records are inserted.
8. Revoke the device; confirm bearer authentication fails immediately.
9. Confirm household IDs cannot be selected by a customer.
10. Simulate the first successful token response being lost; establish a safe recovery process.
11. Verify JWT authentication failures, MFA policy, abuse throttling, and admin CSRF protections.

**Status:** Unit tests committed, but not executed in GitHub Actions or against D1. Keep `ACTIVATION_API_ENABLED` unset and do not deploy for customer use.
