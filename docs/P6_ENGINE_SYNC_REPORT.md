# P6 — Engine Sync Report

Tanggal: 2026-11-13. Belum commit, belum deploy. Repo Android dan Worker tidak diubah.

**Status kompatibilitas: `OFFLINE_PASSED` (maksimal). BUKAN `ANILILI_READY`** — tidak ada bukti playback perangkat/emulator Android.

## 1. Ringkasan

| Area | Hasil |
|---|---|
| Sinkronisasi kontrak engine P5 (b755e47) | SELESAI |
| Blok `security` kompatibel decoder Kotlin | SELESAI |
| Peringatan data quality nomor episode | SELESAI |
| Gerbang kualitas (ESLint, Vitest, Playwright, build) | Semua hijau |

## 2. Perubahan kontrak engine P5 vs P4 (8c62306)

Commit referensi baru: **`b755e47eb2ca1a5daf0b51f1310a684cb9613999`**

### Perbaikan pipeId/slug di P5 (tidak berlaku lagi sebagai blocker)

`WorkflowRemoteProviderRuntime.parseRemotePipeId` di P5 menggunakan `split("/", limit=5).getOrNull(4)` — `{href}` di slot index 4 kini boleh berisi `/` (URL absolut maupun path bertingkat). Dua jalur:

- **Href langsung (format baru):** `raw` adalah path/URL, dipakai langsung tanpa lookup cache.
- **Bare number (format lama / backward compat):** angka integer → lookup di `RemoteSlugStore`.

Resolve href: `base.resolve(raw.trim())` via OkHttp terhadap `entry.endpoint`. Host berbeda → ditolak. Variabel yang diisi:
- `{{url}}` = `{{episodeUrl}}` = URL absolut hasil resolve.
- `{{episodeSlug}}` = `encodedPath.trim('/')` dari URL absolut.

### Dua blocker lama dihapus dari `CRITICAL_BLOCKERS`

| Blocker | Status sebelum P5 | Status P6 |
|---|---|---|
| `episode-slug-cache-stores-pipeid` | CRITICAL | **Diperbaiki di P5 — dihapus** |
| `sources-url-prefers-anime-slug` | CRITICAL | **Diperbaiki di P5 — dihapus** |
| `no-per-item-base64-decode` | CRITICAL | Tetap berlaku |
| `android-playback-untested` | CRITICAL | Tetap berlaku |

`CRITICAL_BLOCKERS.length` = 2 (dari 4 di P4).

### Template variable — tidak ada perubahan diperlukan

`WORKFLOW_INJECTED_VARIABLES` di Generator sudah mencerminkan variabel P5 (`url`, `episodeUrl`, `episodeSlug` di `conditional` untuk `sources`). Workflow `episodes` dan `sources` dengan `url: '{{url}}'` sudah benar untuk engine P5 — tidak diperlukan workaround slug telanjang.

## 3. Blok `security` kompatibel Kotlin

### `SecurityConfig` dari `RemoteRequestPolicy.kt`

```kotlin
@Serializable
data class SecurityConfig(
    val allowedDomains: List<String> = emptyList(),
    val maxResponseBytes: Long = 1024 * 1024,
    val maxRequestsPerRun: Int = 20,
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 15000,
    val totalTimeoutMs: Int = 30000,
    val allowRedirects: Boolean = true,
    val maxRedirects: Int = 5,
)
```

8 field, semua camelCase, semua optional (ada default). `maxResponseBytes` adalah `Long` → harus number literal di JSON, bukan string. `DEFAULT_SECURITY` di Generator sudah identik.

### Perubahan Generator (P6)

**`generateProviderJson`** — blok `security` sekarang selalu disertakan (tidak `null`). `allowedDomains` di-derive secara konservatif dari hostname `project.endpoint`. User bisa override via `project.security.allowedDomains`.

```js
// Sebelum P6: security hanya ada bila project.security diset manual.
// Sesudah P6: security selalu ada, allowedDomains = [hostname endpoint] secara default.
const derivedAllowedDomains = (() => {
  try { return [new URL(project.endpoint).hostname] } catch { return [] }
})()
const security = { ...DEFAULT_SECURITY, allowedDomains: derivedAllowedDomains, ...(project.security || {}) }
```

**Contoh output untuk Anichin (`endpoint: https://anichin.moe`):**
```json
{
  "security": {
    "allowedDomains": ["anichin.moe"],
    "maxResponseBytes": 1048576,
    "maxRequestsPerRun": 20,
    "connectTimeoutMs": 10000,
    "readTimeoutMs": 15000,
    "totalTimeoutMs": 30000,
    "allowRedirects": true,
    "maxRedirects": 5
  }
}
```

**Validasi** `security` block ditambahkan ke `validateProviderJson`: cek unknown keys, `allowedDomains` wajib array, `maxResponseBytes` wajib number literal.

## 4. Peringatan data quality nomor episode

Ditambahkan di `episodeFields` (`ruleInference.js`). Muncul di `evidence` rule `episode-list` untuk ditampilkan di UI Rule Editor.

| Kondisi | Peringatan |
|---|---|
| ≥1 nomor duplikat | "N nomor episode duplikat — engine memakai indeks slug sebagai key; duplikat dapat menimpa cache" |
| Lonjakan ≥50 antar nomor berurutan | "Lonjakan nomor episode ≥50 (mis. X→Y) — verifikasi apakah nomor episode bercampur dengan nomor seri" |

Ini relevan dengan temuan P4 (R6): nomor "04 [56]" → 456, nomor 153 ganda, dsb.

## 5. File yang diubah

| File | Perubahan |
|---|---|
| `src/lib/contract/engineContract.js` | `ENGINE_REFERENCE_COMMIT` → `b755e47...`; `CRITICAL_BLOCKERS` dipangkas dari 4 ke 2; tambah `SECURITY_KEYS` + validasi security block di `validateProviderJson` |
| `src/lib/generator/providerGenerator.js` | Auto-derive `allowedDomains` dari `project.endpoint`; security selalu disertakan |
| `src/lib/analyzer/ruleInference.js` | Peringatan nomor episode duplikat dan lonjakan ≥50 |
| `tests/unit/contract.test.js` | Verifikasi `CRITICAL_BLOCKERS` terbaru; 4 tes security validation baru |
| `tests/unit/generator.test.js` | Verifikasi auto-derive `allowedDomains` dan user override |
| `tests/unit/analyzer.test.js` | 2 tes peringatan nomor duplikat/lonjakan |

**Tidak diubah:** `offlineTester.js`, `runtimeParsers.js`, `workflowInference.js`, `WorkflowBuilder.jsx`, `workflowSync.js`, repo Android, Worker Security.

## 6. Hasil gerbang kualitas

| Gerbang | P4 | P6 |
|---|---|---|
| ESLint | bersih | **bersih** |
| Vitest | 324/324 (20 file) | **332/332 (20 file; +8 tes baru)** |
| Playwright (chromium + mobile-chrome + tablet-chrome) | 87/87 | **87/87** |
| Production build | sukses | **sukses** |

Vitest dijalankan setelah seluruh perubahan; Playwright dijalankan dari `dist/` (wajib `npm run build` sebelumnya).

## 7. Blocker dan risiko tersisa

| ID | Tingkat | Temuan |
|---|---|---|
| `no-per-item-base64-decode` | **BLOCKER** | `normalize base64_decode` hanya memproses satu string; decode per-item untuk list tidak ada. Sources berbasis option base64 tetap BLOCKED. |
| `android-playback-untested` | **BLOCKER** | Playback pada perangkat/emulator Android belum diuji. Status `ANILILI_READY` tidak boleh dinyatakan. |
| `polymorphic-ops-decode-suspected` | Dugaan | Op `normalize/assert/filter` diduga gagal decode di engine (class discriminator). NOT_TESTED. |
| R6 | Risiko | Nomor episode campuran (mis. "04 [56]") tetap bisa menjadi 456 — peringatan baru membantu deteksi dini, tetapi tidak memperbaiki perilaku engine. |

**NOT_TESTED:** playback di Android, emulator/perangkat, jaringan/TLS nyata, Sources produksi dengan href dari fixture Anichin (Sources masih BLOCKED di test offline karena base64 per item di config resmi — bukan dari generator P6).

## 8. Definition of Done P6

- [x] `ENGINE_REFERENCE_COMMIT` mencerminkan commit P5 (`b755e47`).
- [x] Dua blocker pipeId/slug yang sudah diperbaiki P5 dihapus dari `CRITICAL_BLOCKERS`.
- [x] Blok `security` selalu ada di output dengan `allowedDomains` konservatif.
- [x] Validasi security block kompatibel decoder Kotlin strict.
- [x] Peringatan nomor episode duplikat dan lonjakan ditambahkan ke analyzer.
- [x] Semua gerbang kualitas hijau; tidak ada regresi P4 (workflow manual terlindungi).
- [ ] Playback Android: **tidak tercapai**, tetap menunggu uji perangkat/emulator.
