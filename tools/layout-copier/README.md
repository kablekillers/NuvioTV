# Kable Killers Layout Copier (isolated development)

Goal: copy only the Nuvio Home row order and hidden/visible row configuration between two different Nuvio accounts, for both Mobile and TV.

## Safety requirements
- Never commit passwords, access/refresh tokens, Supabase keys, or exported account data.
- Authenticate each account separately via a trusted, self-hosted service.
- Read source layout without modifying it.
- Export a timestamped backup of the destination layout before any write.
- Show a preview/diff of source vs destination; require explicit confirmation before applying.
- Transfer only Home layout row order and hidden/visible catalog identifiers; do not overwrite add-ons, collections, profiles, watch history, or continue-watching data.
- Match rows by stable catalog identity, not display name or position; report missing or ambiguous matches and skip them.
- Verify the destination layout after write and provide rollback from backup.
- Test Mobile and TV APIs independently; do not assume they share the same persisted layout.
- Never expose a public deployment before login, CSRF protection, HTTPS, session security, and rate limits are verified.

## Reference implementation
https://github.com/rburns51/nuvio-reorder-home

Its README documents temporary Nuvio login, profile selection, separate TV/Mobile layout editing, and saving to either or both. Cross-account copying is **not** an existing documented feature and must be implemented and tested.

## Status
Planning scaffold only. No copier code has been implemented, deployed, or used to access either account. Production app remains untouched.


## Experimental two-account phone preview (NOT READY FOR REAL CREDENTIALS)

`live_preview_experimental.html` is a standalone, read-only prototype. It makes
browser requests directly to Nuvio's fixed Supabase HTTPS origin using a
publishable anon key supplied at runtime. It does not include a push RPC,
transfer button, cookie/localStorage persistence, or server-side credential
collection. Tokens are held only in page memory and lost on reload.

**Do not deploy this publicly or use actual Nuvio passwords yet.** Browser CORS,
authentication response schema, profile ownership, CSP, and security review
must be verified in a trusted test environment. A real phone-facing product
needs reviewed authentication, error handling, and explicit transfer safeguards.
The existing `preview.html` remains an offline JSON-only preview. Neither
page is connected to the production Kable Killers app or store.
