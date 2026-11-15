# Nuvio protocol compatibility review (source-code only)

**Status:** Verified against upstream `rburns51/nuvio-reorder-home/sync_worker.py` source, **not against live Nuvio accounts**. Do not deploy the experimental phone page or enter real passwords until browser authentication, CORS, and security are reviewed.

## Confirmed upstream request contracts

- Profile discovery: POST `/rest/v1/rpc/sync_pull_profiles` with JSON `{}`, authenticated session headers; response must be a list.
- Home layout read: POST `/rest/v1/rpc/sync_pull_home_catalog_settings` with JSON `{"p_profile_id": <integer>, "p_platform": "tv" | "mobile"}`. Upstream extracts `settings_json` from RPC response and expects `items` as an array.
- Home layout write (offline adapter only; **not available from experimental phone page**): POST `/rest/v1/rpc/sync_push_home_catalog_settings` with `{"p_profile_id": <integer>, "p_platform": "tv" | "mobile", "p_settings_json": {"items": [...]}}`.
- Catalog row key: `catalog::<addon_id>::<type>::<catalog_id>`. Collection row key: `collection::<collection_id>`. Collection IDs can differ across accounts; equal names are not proof of equal content.

## Compatibility caveats

- Upstream `resolve_profile_rows` considers `profile_id` and `profile_index` and resolves 0-based/1-based indices. Our experimental browser and local CLI currently accept only positive numeric `profile_id` or `id` and therefore **fail closed** if the API returns profile-index-only records. Do not guess IDs or use positional fallback.
- Upstream normalizes Home row fields and converts `enabled` with Python truthiness; our planner preserves destination row fields, so byte-for-byte readback may differ from server-normalized responses. Verify canonical readback against a disposable test account before live writes.
- Supabase password grant and the project URL are inferred from the upstream app and existing code. This review does **not** confirm browser CORS, security policy, the current publishable key, or actual account permissions.
- The browser prototype intentionally has no push endpoint. Do not put real credentials in the experimental HTML until security review is complete.

## Before live use

1. Confirm the Nuvio auth and profile response formats using a controlled test account and inspect *redacted schema only*; never record tokens/passwords.
2. Check that profile IDs are correctly resolved, that TV/Mobile settings are independent, and that collection IDs are mapped only after verification.
3. Compare server normalization and readback on a disposable destination profile. Confirm backup and rollback behavior before enabling any live copy.
4. Keep the source account read-only and the production Kable Killers app unchanged.
