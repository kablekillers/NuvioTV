# Kable Killers TV: device-only household profiles (v1.1.2 development specification)

Status: design only. No runtime implementation or migration has been completed. Do not publish until tested.

## Requirements
- Preserve the existing shared main profile and current account sign-in behavior. Main profile remains server-backed.
- **Global shared configuration:** addons, plugins, addon/plugin configuration, enabled catalogs, and related source settings are managed on the main profile and synchronized to every device and every local profile. Personal profiles must consume the same global configuration; they must not fork or override it.
- Addon/plugin changes made through the main profile should propagate using the existing supported account synchronization mechanism, including removals. Avoid copying the addon list into local profile records. If the existing service only syncs per-profile, implementation must explicitly bridge or move shared configuration to account scope rather than assuming it already syncs.
- Other profiles are **device-local**, scoped by an app-installation identifier and opaque local profile ID. Never upload profile metadata, favorites, history, or Continue Watching to the shared account.
- Each local profile has its own Continue Watching, playback progress, watch history, and favorites. Every read and write must be scoped to active profile ID, including background workers, autoplay, resume, detail pages, search, and playback callbacks.
- New installs display the main profile plus local profiles created on that device only. Remote profiles from the shared account must not be listed as local profiles. Do not delete or mutate remote profiles on the server as part of hiding them.
- Use private app storage (Room/DataStore as appropriate); local data is not a server backup. Android backup/restore behavior must be explicitly defined and tested to avoid cross-household leakage.
- Switching profiles must invalidate profile-specific cached UI state and pending writes; use stable IDs rather than names as keys.
- Local profile deletion deletes only that local profile's data; never deletes the shared main account or another profile.
- Shared-account Trakt, cloud progress, favorites, and viewing-history synchronization calls must be disabled or isolated for local profiles. **Do not disable account-level addon/plugin configuration synchronization**; local profiles need read access to the shared configuration. Ensure background sync does not replay locally collected events to the main account.
- Preserve app package name, signing lineage, current user settings, Watch Party, catalogs, plugin configuration, and update path.
- Existing remote profiles must not be silently migrated into local profiles or exposed across households. Introduce an explicit migration/visibility policy after source audit.

## Main administrator profile and PIN protection
- Main profile is administrator-only; prompt for an administrator PIN before opening it and before any shared addon/plugin management operation, including add, remove, enable/disable, settings edits, and import/export that changes shared configuration.
- Customer profiles are read-only consumers of shared addons/plugins; they can manage only their own local viewing data and optional personal profile PINs.
- Enforce administrator authorization on the server for all shared-configuration mutations. A local UI PIN check alone is not sufficient because the shared login may otherwise allow direct account/API writes.
- Never hard-code a universal PIN into the APK or publish it in GitHub. Store only a securely hashed verifier server-side, with rate limiting and a safe recovery/rotation mechanism. Keep administrator authorization distinct from the common viewing login.
- Ensure no background process, deep link, profile switch, or alternate settings route bypasses these permissions. Changes by the administrator continue to sync to all profiles and devices.
- Before implementing, verify whether the current Nuvio cloud backend supports distinct administrator credentials/roles. If not, a separate backend authorization component is required; do not claim the PIN is secure without it.

## Implementation gates
1. Locate profile models, profile switcher, cloud synchronization, Continue Watching, favorites, history, playback progress, and Trakt integration in the current branch.
2. Document every persistence path and sync endpoint; identify which operations are shared-account (especially addon/plugin installation and settings) and which are local. Verify addon/plugin changes made on the main profile actually reach all devices and profiles.
3. Add local profile persistence and scoped repositories; enforce isolation at the data layer, not just the UI.
4. Add tests for two devices sharing one login, two profiles on one device, switch during playback, background synchronization, app restart, profile deletion, upgrade from v1.1.1, and propagation of main-profile addon/plugin add, remove, and configuration changes.
5. Test unauthorized attempts to modify addons/plugins from customer profiles, wrong PINs, deep links, direct API calls, and authorized propagation across households.
6. Build both ARMv7 and ARM64 test APKs; test production-signed install-over and verify settings/logins remain intact.
7. Only publish with user approval after CI and device tests pass.

## Privacy invariant
For any two distinct devices A and B sharing the same main account, creating, viewing, or changing local profile P on A must not cause any profile P data to appear on B or in server account state.
