# Kable Killers owner-approved QR activation: implementation contract

Status: design contract only. No independent authentication service has been deployed.
The current Nuvio login remains untouched.

## Confirmed current implementation
`app/src/main/java/com/nuvio/tv/core/auth/AuthManager.kt` references:
- `start_device_login_session`
- `start_tv_login_session`
- `poll_tv_login_session`
- `/functions/v1/tv-logins-exchange`

These are Nuvio-specific server calls. Do **not** assume the existing Nuvio account, device list, sessions, or QR approvals can be transferred to a Kable Killers server.

## Owner-approved workflow

1. TV requests a one-time pending activation challenge from the Kable Killers backend.
2. TV displays a QR URL and a short human-readable code. The customer sends the QR photo to the owner; customers cannot approve it.
3. Owner signs in to the private admin dashboard with MFA and opens the QR URL or enters the short code. Dashboard displays a device fingerprint label and the pending request; owner explicitly approves.
4. TV polls with its private challenge secret. After approval, server issues **device-scoped** credentials bound to a newly generated device record and household. The TV never receives the owner's admin access or refresh tokens.
5. Device retrieves only authorized shared master configuration plus its own household state. Other households' profiles, viewing history, and sessions are never returned.

## Backend minimum

- `POST /v1/activations`: rate-limited unauthenticated request; returns opaque challenge ID, private poll secret, expiry, verification URL/code. Never include an admin credential.
- `GET /v1/activations/{id}`: poll requires private secret; return pending/approved/expired/denied. Single-use exchange for device credentials.
- `POST /v1/admin/activations/{id}/approve`: requires owner admin session, MFA, CSRF protection and explicit approval. Records approver and timestamp.
- `POST /v1/admin/activations/{id}/deny`: owner only.
- `GET /v1/admin/devices`: owner-only paginated list; device name, activation date, last seen, household, status.
- `POST /v1/admin/devices/{id}/revoke`: owner only; immediately invalidates device refresh credentials and subsequent API access.
- `GET /v1/device/config`: device-scoped authorization; returns versioned approved master snapshot; never includes owner tokens or other households' state.

## Security and consistency invariants

- Pending challenge expires quickly (e.g., 5 minutes), single use, cryptographically random. Approval requires the owner to confirm a matching code/device; QR possession alone is **not** authorization.
- Store hashed challenge secrets; use HTTPS, server-side expiry and replay protection. Rate limit requests and polling.
- Never store or transmit the owner's password to the TV; separate owner identity from device identities.
- Per-device credentials are revocable and rotated. Server-side device and household IDs are immutable and distinct from Nuvio IDs.
- The master config may be shared read-only to authorized devices, but **profiles, progress, history and preferences remain household scoped**. Verify authorization on every query.
- Log activation/revocation metadata, not QR secrets, passwords, access tokens, private addon URLs, or watch history.
- Device revocation cannot be bypassed by a cached config or long-lived bearer token. Define bounded offline behavior explicitly before release.
- Master config updates must be atomic, versioned, rollback-capable and independent of household state.
- Never expose an owner-only API or secret on public GitHub Pages. The public website can link to a secure separate backend, not replace it.

## Migration and rollout gates

1. Preserve Nuvio login and all production behavior during development.
2. Build/test private backend and dashboard; test QR expiry, replay, unauthorized approvals, revocation and household isolation.
3. Pilot on a separate test TV and test household. No automatic migration of Nuvio's historical device list is assumed.
4. Add an explicit opt-in cutover with recovery path, then migrate customer devices in controlled batches.
5. Do not ship a release until owner approves tested recovery and revocation flows.
