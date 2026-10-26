# Kable Killers TV v1.1.2 — Nuvio beta feature audit (in progress)

Compared: `tv-1.1.1-release-candidate` against `nuvio-beta-2026-10-16-test`.
Do **not** merge the beta branch wholesale: the histories have diverged and the beta Gradle version is 1.1.0 (1069), while the stable release candidate is 1.1.1 (1071).

## Verified difference: cache controls missing from stable UI

- Beta `NetworkSettingsScreen.kt` has an Advanced Settings `cache_settings` section:
  - Auto-Clear Cache on Startup
  - Smart Cache Cleanup
  - Clear Cache Now
- Beta `AdvancedSettingsViewModel.kt` exposes the corresponding UI state, events and handlers.
- Stable 1.1.1 `NetworkSettingsScreen.kt` and `AdvancedSettingsViewModel.kt` lack these controls.
- Stable 1.1.1 **already contains** `CacheCleaner.kt`, `CachePreferences.kt` and calls `cacheCleaner.runStartupCleanup()` from `NuvioApplication`. Thus, the backend exists but the user-facing controls need to be ported.
- **Next action:** selectively port beta UI and ViewModel cache controls onto the stable code; preserve all unrelated stable settings.

## Verified difference: trailer performance

Stable `TrailerPlayerPool.kt` includes a `MemoryBudget` dependency, separate trailer settings and memory caps not found in the beta implementation. **Keep the stable version** pending device performance tests.

## Possible difference: Dolby Vision native bridge

The supplied beta ARMv7 APK contains `lib/armeabi-v7a/libdovi_bridge.so`; the supplied 1.1.1 universal APK does not. Both branches include conditional native build support for DOVI. Presence of a library alone does not establish a working feature. **Do not enable blindly**; test device support and native linkage first.

## Release gates

- Preserve `com.kablekillers.tv`, signing identity, Kable Killers branding, Watch Party, plugins, catalogs and player behavior.
- Ensure the existing in-app updater selects an ABI-compatible release APK (helper currently not integrated).
- Verify signed ARMv7 and ARM64 APKs, run unit tests, and install over v1.1.1 on devices.
- Keep store v1.1.1 unchanged until approved.
