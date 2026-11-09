# Device-only profiles: source audit (2026-10-27)

This is an implementation audit, **not a completed privacy feature**. The current v1.1.2 branch must not be distributed as device-local profiles yet.

## Verified source paths
- `core/profile/ProfileManager.kt`: primary profile ID is 1, up to six profiles, local DataStore list, per-profile cleanup.
- `data/local/ProfileDataStore.kt`: `profile_settings` DataStore stores `profiles_json`, active profile ID, and selection preferences.
- `data/local/ProfileDataStoreFactory.kt`: feature preferences use `_p<id>` suffix for secondary profiles. This is already a local per-profile storage primitive.
- `core/sync/ProfileSyncService.kt`: **currently uploads every profile** through `sync_push_profiles`; remote pull exists. This would leak local profile names and metadata to every device using the shared account.
- `ui/screens/profile/ProfileSelectionViewModel.kt`: create, update, and delete call `pushToRemote()`; deletion also calls remote `deleteProfileData(id)`. Its create/copy flow invokes remote `copyProfileSetup`.
- `data/local/PluginDataStore.kt`: plugin data currently uses effective profile ID. `usesPrimaryPlugins` can redirect reads to profile 1, but only when set; secondary profiles can otherwise have separate plugin data. Shared addon/plugin settings are **not yet universally enforced**.
- `core/sync/ProfileSettingsSyncService.kt`: syncs profile settings with cloud and must be scoped before local profile enablement.
- `ui/screens/home/HomeViewModelContinueWatching.kt`: Continue Watching display/read integration requires audit before local profile rollout.

## Important finding
Changing only the profile selector or creating a local policy class does **not** provide privacy. Current profile creation still uploads profile metadata, and profile settings sync can leak viewing preferences. Existing shared account profiles must not be blindly pulled into each customer's local profile list.

## Safe integration sequence
1. Add explicit primary-vs-local profile type and migration state, with stable local IDs. Existing remote secondary profiles must be quarantined from local profile creation until a safe migration/visibility policy is defined.
2. Stop remote push, pull, and delete for **local profiles** at the service/repository boundary, including background sync and profile setup copying. Keep main profile sync working.
3. Force addon/plugin configuration reads to shared primary/account scope for all profiles. Audit addon store separately from PluginDataStore; avoid copying plugin credentials or other household secrets.
4. Scope favorites, Continue Watching, history, and playback progress by local profile ID; block remote writes for those profiles, including Trakt and asynchronous jobs.
5. Test two devices with one shared login: local profile A on device 1 never appears on device 2, even after restart and sync. Test add/remove plugin propagation from primary profile and ensure personal viewing data never syncs.
6. Test upgrade from v1.1.1 and signing compatibility. Do not publish or claim privacy until tests and device checks pass.

PIN feature remains deferred.
