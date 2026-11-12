# Kable Killers independent configuration: integration gates

This branch currently provides **offline** export validation, master snapshot generation, export comparison, and synthetic tests. It does **not** provide Nuvio account monitoring, automatic dual-write, Android restore, or customer distribution.

## Verified from a private NuvioSync v1.0.0 export (no private data committed)

- Export sections: library, watched, progress, addons, collections.
- Add-on records include `id`, `user_id`, `url`, `name`, `enabled`, `sort_order`, timestamps and `profile_id`.
- Collection records include `id`, `title`, `folders`, `pinToTop`, `viewMode`, `community`, `showAllTab`, `focusGlowEnabled`.
- The exported collections are not proof that every home-screen row setting or account preference is represented. Verify against Android app state before claiming full fidelity.

## Required implementation order

1. **Read current source of truth:** Identify Nuvio TV's real account-sync and local add-on/collection repositories. Do not infer endpoints or introduce credentials in code.
2. **Passive capture:** Hook *after successful existing Nuvio save/sync* to update a private local snapshot. No writes back to Nuvio from the snapshot. Nuvio's existing behavior must remain unchanged.
3. **Private storage:** Use app-private storage, atomic file replacement, schema validation, encryption where appropriate. Never commit a real export or log full add-on URLs, user IDs, access tokens, or household data.
4. **Restore in an isolated test build:** Validate add-ons, enabled state, explicit `sort_order`, collection/folder ordering, and profile isolation. Confirm that app-local profile identifiers are mapped safely rather than copied across households.
5. **Independent distribution:** Choose an authenticated server/API for distributing only approved master config versions. Require TLS, integrity checks, versioning, rollback, and access control. Do not host secret-bearing snapshots in public GitHub Pages or public releases.
6. **Compatibility and cutover:** Dual-sync during testing. Customer update only after opt-in pilot passes and rollback is verified.

## Master vs household scope

- Master-shared: curated add-ons, collection definitions, display order, supported master settings.
- Household-private: profiles, watched items, playback progress, favorites, credentials, device-specific settings.
- Never synchronize household-private data into the master export or another household.

## Current limitations

The export change detector compares two **manual exports**; it does not poll or subscribe to Nuvio servers. A local snapshot alone will not preserve Nuvio-dependent login, watch-party, metadata, or other cloud services if Nuvio shuts down. Each dependency needs separate assessment.

## Test gate

Run `python3 -m unittest discover -s tools/tests -v` in a checkout of this branch. Do not release to customers until Android integration and end-to-end tests have passed.
