# P10 — Laporan Hasil Pengujian (Test Results)

## 1. Unit & Security Tests — Remote Provider Engine

| Kategori Tes | Jumlah | Hasil | Detail |
|---|---|---|---|
| `RemoteProviderEngineTest` | 10 | **PASS** | HTTPS enforcement, SSRF protection, domain allowlist, rate limit, template variable resolution, schema version mismatch, non-HTTPS rejection, duplicate provider ID detection, anichin.json parsing, kuramanime.json parsing |
| `PolicyEnforcingDnsTest` | 4 | **PASS** | Blokade loopback (`127.0.0.1`), blokade private IP tanpa izin, izinkan private IP terotorisasi, izinkan public IP |
| `AnimeTitleMatcherTest` | 5 | **PASS** | Exact match, season mismatch rejection (skor 0.0), same season match, similar titles, different titles |
| `RemoteSlugStoreTest` | 4 | **PASS** | Simpan/ambil anime slug, episode slug, invalidasi mapping, LRU eviction |
| `RemoteProviderHttpTrustTest` | 9 | **PASS** | Redirect downgrade HTTPS→HTTP diblokir, LAN IP + port custom, loopback permanent diblokir, config trust tidak memberikan runtime endpoint HTTP |
| `ResponseSizeLimitTest` | 4 | **PASS** | Declared Content-Length > limit, chunked streaming > limit, within limit, exactly at limit |
| `ProviderFixtureOfflineTest` | 4 | **PASS** | Anichin search/episodes/watch, Kuramanime search — metadata extraction valid |
| `RemoteProviderIntegrationTest` | 6 | **PASS** | E2E search→match→episodes→sources, title matcher season rejection, engine search, episodes, sources, security regression |

**Total remote provider tests: 46 PASS / 0 FAIL**

## 2. Regression Tests — Provider Bawaan

| Test Suite | Hasil | Keterangan |
|---|---|---|
| `ProviderAttemptOrderTest` | **PASS** | Urutan fallback provider bawaan utuh |
| `LanguageGatedAttemptOrderTest` | **PASS** | Filter bahasa dan sub/dub normal |
| `HindiProviderCatalogTest` | **PASS** | Katalog regional berjalan normal |
| Seluruh test suite lainnya | **PASS** | 2608/2610 total PASS |

## 3. Pre-Existing Failures (Bukan Regresi P10)

| Test | Hasil | Keterangan |
|---|---|---|
| `AnnouncementTest > a genuinely signed update manifest is not an announcement` | **FAIL** | Test Nostr — sudah gagal di baseline `a5828fb` sebelum perubahan P10 |
| `NostrManifestVerificationTest > the published manifest event passes full NIP-01 verification` | **FAIL** | Test Nostr — sudah gagal di baseline `a5828fb` sebelum perubahan P10 |

## 4. Build & Kompilasi

| Target | Hasil | Keterangan |
|---|---|---|
| `assembleMobileDebug` | **PASS** | APK Mobile Debug berhasil dibangun |
| `assembleTvDebug` | **PASS** | APK TV Debug berhasil dibangun |
| `assembleMobileRelease` | **PASS** | APK Mobile Release berhasil dibangun |
| Kotlin warnings | 8 warnings | Non-fatal: exhaustive `when`/`else`, nullable receiver, unchecked cast — bukan error |

## 5. Tests yang BELUM Dijalankan

| Kategori | Alasan |
|---|---|
| MockWebServer E2E integration (Search→Match→Episodes→Sources) | **PASS** — `RemoteProviderIntegrationTest.e2e full pipeline runtime search match episodes sources` lolos |
| Multi-source concurrency | Fase 5 storage sudah diimplementasikan, tetapi tes konkursi itu sendiri (dua source independen update/remove/revoke) belum ditulis sebagai kode uji |
| Actual playback verification (ExoPlayer STATE_READY) | Membutuhkan instrumen test dengan media server lokal |
| Offline fixture parsing (HTML Anichin/Kuramanime) | **PASS** — `ProviderFixtureOfflineTest` lolos (Anichin search/episodes/watch + Kuramanime search) |
| Real-site stream resolution | Membutuhkan jaringan live ke situs sumber |
| Real-site playback verification | Membutuhkan perangkat fisik/emulator dengan video player |

## 6. Credential & Data Audit

- ✅ Tidak ada token, cookie, session ID, API key, atau HAR data dalam git diff
- ✅ File HAR tetap di luar repository (`C:\Users\...\Downloads\DL-Mizan\provider-html-har\`)
- ✅ Asset JSON (`anichin.json`, `kuramanime.json`) bersih dari data sensitif
