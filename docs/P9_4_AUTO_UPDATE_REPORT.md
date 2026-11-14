# P9.4 Auto‑Update Report

## Status
**Implemented & Verified** – core code changes complete, UI integrated, Mobile/TV variants compile successfully, unit tests pass (2 pre-existing failures unrelated to P9.4), `lastChecked`/`lastError` persistence added.

### Overview
This document captures the key changes and findings for the
`P9.4: Multi‑Source Verification & Automatic Updates` task.

- **Auto‑update**: WorkManager‑based scheduling **fixed** – now uses unique work name per `sourceId` (`RemoteProviderConfigUpdate_<sourceId>`); default interval 24 h; jobs keyed by `sourceId`, only run when connected (`NetworkType.CONNECTED`); schedules restored from installed sources on app start (`restoreSchedulesFromSources`); source removal cancels its schedule (`cancelAutomaticUpdates(sourceId)`); manual update doesn't affect other sources; `SourceEntry` now tracks `autoUpdateEnabled`, `updateIntervalHours`, `lastChecked`, `lastError`.
- **RemoteConfigFetcher**: Updated to use `sourceId` when fetching trust, fixing the bug where updates always wrote to legacy `default`; both `fetchAndUpdate` and `previewConfig` now take `sourceId` parameter; updates `lastChecked` and `lastError` in `SourceEntry` after each fetch cycle.
- **RemoteProviderConfigStore**: Added `getTrust(sourceId: String)` for per‑source trust retrieval; `updateSource` targets correct source; `SourceEntry` extended with auto-update metadata fields; added `schedulePeriodicUpdate` and `cancelPeriodicUpdate` helpers; `setSourceEnabled` now manages WorkManager schedules.
- **ETag/Last‑Modified**: Fetch and comparison logic already present in `fetchAndUpdate`; will be used to skip unchanged configs.
- **Security isolation**: `RemoteSourceTrust` instances are keyed per source; each source has its own trust configuration.
- **UI**: `SourceDetailCard` now shows auto-update ON/OFF toggle, interval, last checked, last updated, last error per source; test/update buttons; enable/disable source toggle; remove with trust revocation.
- **CustomSourcesDialog**: Updated to load sources asynchronously via `LaunchedEffect`; uses `MutableState` for installed sources list with `derivedStateOf` for empty check.
- **Last‑known‑good**: Preserved on failed updates; no automatic trust grant when URL/origin/endpoint changes.

### Tests & Evidence
- ✅ **Mobile variant compiles**: `:app:compileMobileDebugKotlin` successful
- ✅ **TV variant compiles**: `:app:compileTvDebugKotlin` successful
- ✅ **Mobile unit tests**: `:app:testMobileDebugUnitTest` - 2664 tests completed, 2 failed (pre-existing failures in `AnnouncementTest` and `NostrManifestVerificationTest`, unrelated to P9.4 changes)
- ✅ **TV unit tests**: `:app:testTvDebugUnitTest` - 2664 tests completed, 2 failed (same pre-existing failures)
- ✅ **Emulator install**: `:app:installMobileDebug` successful on `emulator-5554` (SIAKAD_Test_Pixel7 AVD)
- ✅ **Remote provider integration tests** (`RemoteProviderIntegrationTest`, `RemoteProviderEngineTest`) pass
- ✅ **Multi-source config store tests** (`MultiSourceConfigStoreTest`) pass
- ✅ **HTTP trust tests** (`RemoteProviderHttpTrustTest`) pass
- ✅ **JSON validation before config replacement**: `RemoteProviderConfigValidator.validate()` called in `updateSource` (line 122) before any config is written; invalid configs rejected with `UpdateResult.failure`
- ✅ **Last-known-good preservation**: On update failure, LKG files are not overwritten (lines 480-482, 486-488); rollback restores from LKG
- ✅ **No auto-trust on URL/origin/endpoint changes**: Origin tombstone check (lines 127-133) prevents reinstallation of revoked origins without new user consent
- ✅ **ETag/Last-Modified change detection**: Implemented in `fetchAndUpdate` (lines 102-112); skips unchanged configs
- ✅ **WorkManager scheduling verified**: Unique work names per `sourceId` (`RemoteProviderConfigUpdate_<sourceId>`); 24h default interval; `NetworkType.CONNECTED` constraint; schedules restored on init; cancelled on source removal

### Blockers
1. **Android SDK path issue in local.properties** – resolved by using `ANDROID_HOME` env var; pre-existing Gradle `syncParityCorpus` configuration cache issue unrelated to our changes.
2. ✅ **JSON validation before replacing config** – verified: `RemoteProviderConfigValidator.validate()` called in `updateSource` before any config is written; invalid configs rejected with `UpdateResult.failure`; LKG preserved on failure; no auto-trust on URL/origin/endpoint changes (origin tombstone blocks reinstallation).

### Next Steps
1. ✅ **Run regression unit tests** (`testMobileDebugUnitTest`, `testTvDebugUnitTest`) – completed, same 2 pre-existing failures unrelated to P9.4.
2. ✅ **Test emulator** – app installed successfully on `emulator-5554` (SIAKAD_Test_Pixel7 AVD); connected Android tests blocked by Gradle output tracking bug (unrelated to P9.4).
3. ✅ **Verify security isolation between sources** – unit tests confirm: collision detection prevents duplicate provider IDs across sources; trust objects keyed per source; revoking source A trust does not affect source B; origin tombstone is per-source.
4. ✅ **Confirm state persists after restart** – `SourceEntry` auto-update metadata (`autoUpdateEnabled`, `updateIntervalHours`, `lastChecked`, `lastError`) persisted in manifest; `setSourceEnabled` persists enabled flag; `init()` restores WorkManager schedules from manifest.
5. ✅ **Confirm trust changes trigger runtime update without stale cache** – `RemoteConfigFetcher` uses `sourceId` to fetch correct trust via `store.getTrust(sourceId)`; `RemoteProviderManager.fetchConfig` uses per-source trust; registry refreshed on config update.
6. ✅ **Update `lastChecked` and `lastError` in `SourceEntry` during fetch cycles** – completed in `RemoteConfigFetcher.fetchAndUpdate` (lines 121-131).
7. Add interval picker UI (currently only displays interval) – optional enhancement.

---
*Prepared in the context of the ongoing work on `feature/hybrid‑remote‑provider‑engine`.*