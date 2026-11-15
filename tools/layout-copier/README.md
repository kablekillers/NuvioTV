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
