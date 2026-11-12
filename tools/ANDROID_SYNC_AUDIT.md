# Verified Android TV synchronization entry points

Inspected on `dev` branch. This is a source-code audit, not a production change.

## Files and confirmed behavior

- `app/src/main/java/com/nuvio/tv/NuvioApplication.kt`: injects `StartupSyncService`.
- `app/src/main/java/com/nuvio/tv/core/sync/StartupSyncService.kt`: orchestrates `AddonSyncService`, `CollectionSyncService`, and `HomeCatalogSettingsSyncService`; supports realtime surfaces `addons`, `collections`, `home_catalog_settings`, and others.
- `app/src/main/java/com/nuvio/tv/core/sync/AddonSyncService.kt`: `pushToRemote()` sends `sync_push_addons` RPC with URL, explicit `sort_order`, enabled state, optional user-set name. `getRemoteAddonUrls()` fetches rows from `addons`, sorts by `sortOrder`, and applies local enabled/name maps.
- `app/src/main/java/com/nuvio/tv/core/sync/CollectionSyncService.kt`: `pushToRemote()` sends `sync_push_collections` RPC with `CollectionsDataStore.exportCurrentProfileJson()`. `pullFromRemote()` uses `sync_pull_collections`, imports JSON, and protects nonempty local collections from an empty remote response.
- `app/src/main/java/com/nuvio/tv/core/sync/HomeCatalogSettingsSyncService.kt`: `SyncCatalogItem` contains `addon_id`, `type`, `catalog_id`, `enabled`, `order`, `custom_title`, `is_collection`, `collection_id`. `SyncHomeCatalogPayload` includes `hide_unreleased_content` and `items`. This is an additional surface required to preserve exact home row ordering; it is NOT one of the five sections in the supplied NuvioSync export.

## Safe integration design

1. Implement a private local **read-only** snapshot collector for master profile only, using `AddonPreferences`, `CollectionsDataStore`, and `LayoutPreferenceDataStore`.
2. Capture the three surfaces together after successful remote sync, with a consistent profile/account identity check before and after reads. Never collect credentials, watch history, or child-profile data.
3. Persist an atomic versioned snapshot in app-private storage. Treat it as a backup, **not** an authority to overwrite Nuvio or customer settings during the pilot.
4. Validate full row ordering against `SyncHomeCatalogPayload.items[].order` and test add-on and collection round trips.
5. Only then implement authenticated independent distribution, conflict handling, and rollback. Do not place private add-on URLs in public GitHub Pages, releases, or logs.

## Cautions

- Nuvio account synchronization uses authenticated Supabase APIs. Avoid adding speculative server endpoints or changing existing RPC calls.
- `StartupSyncService` has multiple startup, realtime, and manual paths; hooking only app startup misses later edits.
- Customer devices and master account may use different profile IDs. Do not copy account identifiers between households.
- This audit alone does not implement dual-sync, restoration, or customer updates.
