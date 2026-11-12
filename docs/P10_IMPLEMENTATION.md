# P10 — Implementasi Hybrid Remote Provider Engine

## 1. Komponen yang Diimplementasikan

### A. TemplateEvaluator & WorkflowExecutor (Fase 1)
- Resolusi properti `HttpResponse` (`body`, `statusCode`, `headers`) di `TemplateEvaluator`.
- Streaming response limit enforcement menggunakan buffer reader berukuran maksimum sesuai policy.
- Perbaikan request counter yang hanya bertambah pada HTTP network steps.
- `MapStep` mendukung iterasi `JsonArray`.
- `extractOutput` mempertahankan struktur objek/list (tidak flatten ke String).
- Dukungan parsing Base64 lintas versi Android (`Base64Codec` kompatibel minSdk 22).

### B. Keamanan Soket: PolicyEnforcingDns (Fase 2)
- Intersepsi DNS OkHttp di connection-time untuk menolak alamat loopback (`127.0.0.0/8`, `::1`) dan private IP yang tidak diizinkan dalam `RemoteSourceTrust`.
- Sanitasi header sensitif (`Authorization`, `Cookie`, `X-Auth-Token`, `Proxy-Authorization`) saat cross-origin redirect.
- Penolakan downgrade HTTPS → HTTP pada redirect.
- Trust scoping presisi `scheme + host + port` di `RemoteSourceTrust.isEndpointOriginTrusted()`.

### C. Abstraksi Runtime & Model Mapping (Fase 3)
- `RemoteProviderRuntime` diubah menjadi interface publik.
- `BasicRemoteProviderRuntime` dan `WorkflowRemoteProviderRuntime` mengimplementasikan interface.
- Parser domain model lengkap: `parseSearchResults`, `parseEpisodesResult`, `parseSourcesResult`, `parseServers`.
- `pipeId` sintetis format `remote/{providerId}/{anilistId}/...`.
- Penentuan rute playback otomatis:
  - Stream direct (`.m3u8`, `.mp4`) → native `PlayerSurface` (ExoPlayer).
  - Stream embed/iframe (`type = "embed"`, `playsInWebView = true`) → `EmbedWebView`.

### D. Title & Season Matching (Fase 4)
- `AnimeTitleMatcher`: Token Jaccard + Levenshtein, ekstraksi season, diskualifikasi instan (skor 0.0) bila season mismatch.
- `RemoteSlugStore`: Thread-safe LRU cache 512 entri untuk memetakan `(providerId, anilistId)` ke URL/slug situs provider.
- `invalidateMapping()` untuk recovery saat 404/stale data.

### E. Integrasi Pipeline Aplikasi (Fase 6 — Parsial)
- Unifikasi instance `UnifiedProviderRegistry` di `AppGraph.kt` (satu instance shared).
- `MiruroRepository.episodes()` mengakses `providerRegistry.getWorkflowRuntimes()`.
- `MiruroRepository.sources()` meneruskan ke remote runtime jika `isRemoteProvider()`.

### F. Asset Fixture Configs (Fase 7)
- `app/src/main/assets/remote-provider-configs/anichin.json`: workflow search, episodes, sources.
- `app/src/main/assets/remote-provider-configs/kuramanime.json`: workflow search, episodes.
- Schema-validated oleh `RemoteProviderConfigValidator`.

### G. Multi-Source Lifecycle & Source-Aware Trust (Fase 5)
- `RemoteProviderConfigStore` beroperasi multi-source: `manifest.json` indeks source, `sources/{sourceId}/{config,lkg,trust}.json`, dan `revoked-origins.json` tombstone.
- `updateSource`, `removeSource`, `rollbackSource`, `setSourceEnabled`, `revokeSourceTrust`, `isOriginRevoked`, `getInstalledSources`, `getSourceConfig`, `getAllCurrentConfigs`, `getAllTrusts`.
- Legacy API (`updateConfig`, `rollbackToLastKnownGood`, `clearConfig`, `revokeTrust`) delegasi ke `sourceId = "default"` untuk kompatibilitas mundur.
- Migrasi mulus legacy `current.json` → `sources/default/`.
- Deteksi tabrakan Provider ID lintas source saat instalasi (`Provider ID collision: '<id>' already registered`).
- Rollback anti-tombstone: origin yang dicabut tidak dapat hidup kembali lewat LKG.
- `RemoteProviderManager.addCustomSource`, `removeCustomSource`, `updateSource`, `setSourceEnabled`, `getInstalledSources`.
- `UnifiedProviderRegistry.refreshFromStore()` baca semua current config + trust lintas source dan gabungkan provider.
- Compose-safe cached accessor: `cachedRemoteProviderIds()`, `cachedLabel()`, `cachedIsFast()` (non-suspend).

### H. Settings UI Picker Integration (Fase 6.4–6.5)
- `EnabledServersPicker` dan `ServerPriorityPicker` di `SettingsScreen.kt` menggabungkan `ProviderCatalog.selectableProviders()` (bawaan) + `cachedRemoteProviderIds()` (remote).
- Label/fast-status remote provider dibaca lewat `cachedLabel()` / `cachedIsFast()` dengan fallback ke `ProviderCatalog`.
- Tampilan prioritas server di baris 2444 sudah menggunakan `cachedLabel()`.

---

## 2. Komponen yang BELUM Diimplementasikan

### A. Testing Suite (Fase 8 — Sebagian)
- Belum ada MockWebServer E2E integration test (Install → Search → Match → Episodes → Servers → Sources).
- Belum ada multi-source concurrency test (dua source independen update/remove/revoke).
- Belum ada actual playback verification test (ExoPlayer STATE_READY, position > 0).
- Belum ada offline fixture parsing test dengan HTML (Anichin/Kuramanime mirror iframe).
- Belum ada streaming response size enforcement test.
- Belum ada security regression test (redirect, DNS rebinding, origin-scoped trust, rollback).
- Pengujian ini masih bersifat dokumen (`docs/P10_TEST_RESULTS.md`) dan belum diuji secara otomatis keseluruhan.

---

## 3. File yang Diubah/Ditambah

### Modified
- `app/src/main/assets/remote-provider-configs/anichin.json`
- `app/src/main/assets/remote-provider-configs/kuramanime.json`
- `app/src/main/java/com/anilili/data/AppGraph.kt`
- `app/src/main/java/com/anilili/data/MiruroRepository.kt`
- `app/src/main/java/com/anilili/data/provider/registry/UnifiedProviderRegistry.kt`
- `app/src/main/java/com/anilili/data/provider/remote/config/RemoteProviderManager.kt`
- `app/src/main/java/com/anilili/data/provider/remote/config/RemoteSourceTrust.kt`
- `app/src/main/java/com/anilili/data/provider/remote/engine/security/RemoteRequestPolicy.kt`
- `app/src/main/java/com/anilili/data/provider/remote/engine/workflow/TemplateEvaluator.kt`
- `app/src/main/java/com/anilili/data/provider/remote/engine/workflow/WorkflowExecutor.kt`
- `app/src/main/java/com/anilili/data/provider/remote/engine/workflow/WorkflowStep.kt`
- `app/src/main/java/com/anilili/data/provider/remote/runtime/RemoteProviderRuntime.kt`
- `app/src/main/java/com/anilili/data/provider/remote/runtime/RemoteProviderRuntimeFactory.kt`
- `app/src/main/java/com/anilili/data/provider/remote/runtime/WorkflowRemoteProviderRuntime.kt`
- `app/src/test/java/com/anilili/data/provider/remote/RemoteProviderEngineTest.kt`

### New
- `app/src/main/java/com/anilili/data/provider/remote/engine/security/PolicyEnforcingDns.kt`
- `app/src/main/java/com/anilili/data/provider/remote/engine/workflow/Base64Codec.kt`
- `app/src/main/java/com/anilili/data/provider/remote/runtime/AnimeTitleMatcher.kt`
- `app/src/main/java/com/anilili/data/provider/remote/runtime/RemoteSlugStore.kt`
- `app/src/test/java/com/anilili/data/provider/remote/engine/security/PolicyEnforcingDnsTest.kt`
- `app/src/test/java/com/anilili/data/provider/remote/runtime/AnimeTitleMatcherTest.kt`
- `app/src/test/java/com/anilili/data/provider/remote/runtime/RemoteSlugStoreTest.kt`
- `docs/P10_AUDIT.md`
- `docs/P10_IMPLEMENTATION.md`
- `docs/P10_RELEASE_READINESS.md`
- `docs/P10_TEST_RESULTS.md`
