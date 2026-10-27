# Kable Killers TV: device-only household profiles (v1.1.2 development specification)

Status: design only. No runtime implementation or migration has been completed. Do not publish until tested.

## Requirements
- Preserve the existing shared main profile and current account sign-in behavior. Main profile remains server-backed.
- Other profiles are **device-local**, scoped by an app-installation identifier and opaque local profile ID. Never upload profile metadata, favorites, history, or Continue Watching to the shared account.
- Each local profile has its own Continue Watching, playback progress, watch history, and favorites. Every read and write must be scoped to active profile ID, including background workers, autoplay, resume, detail pages, search, and playback callbacks.
- New installs display the main profile plus local profiles created on that device only. Remote profiles from the shared account must not be listed as local profiles. Do not delete or mutate remote profiles on the server as part of hiding them.
- Use private app storage (Room/DataStore as appropriate); local data is not a server backup. Android backup/restore behavior must be explicitly defined and tested to avoid cross-household leakage.
- Switching profiles must invalidate profile-specific cached UI state and pending writes; use stable IDs rather than names as keys.
- Local profile deletion deletes only that local profile's data; never deletes the shared main account or another profile.
- Shared-account Trakt, cloud progress, favorites, and synchronization calls must be disabled or isolated for local profiles. Ensure background sync does not replay locally collected events to the main account.
- Preserve app package name, signing lineage, current user settings, Watch Party, catalogs, plugin configuration, and update path.
- Existing remote profiles must not be silently migrated into local profiles or exposed across households. Introduce an explicit migration/visibility policy after source audit.

## Implementation gates
1. Locate profile models, profile switcher, cloud synchronization, Continue Watching, favorites, history, playback progress, and Trakt integration in the current branch.
2. Document every persistence path and sync endpoint; identify which operations are shared-account and which are local.
3. Add local profile persistence and scoped repositories; enforce isolation at the data layer, not just the UI.
4. Add tests for two devices sharing one login, two profiles on one device, switch during playback, background synchronization, app restart, profile deletion, and upgrade from v1.1.1.
5. Build both ARMv7 and ARM64 test APKs; test production-signed install-over and verify settings/logins remain intact.
6. Only publish with user approval after CI and device tests pass.

## Privacy invariant
For any two distinct devices A and B sharing the same main account, creating, viewing, or changing local profile P on A must not cause any profile P data to appear on B or in server account state.
