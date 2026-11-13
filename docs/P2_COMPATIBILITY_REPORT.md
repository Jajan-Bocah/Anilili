# P2 — Laporan Kompatibilitas Anilili & Validasi Dunia Nyata

> **Dokumen ini men-supersede `docs/COMPATIBILITY_REPORT.md`** (2026-11-13), yang mengklaim `ANILILI_READY`/live passed tanpa fixture nyata dan tanpa uji Android. Klaim itu **tidak berlaku**.

- **Repo referensi:** https://github.com/Jajan-Bocah/Anilili (klon read-only; repo Android **tidak diubah**)
- **Branch:** `feature/hybrid-remote-provider-engine`
- **SHA referensi (HEAD saat audit):** `8c6230665699978963aa3e77dd791ceaced94219` (`ENGINE_REFERENCE_COMMIT` di `src/lib/contract/engineContract.js`)
- **HEAD diverifikasi ulang** di akhir pekerjaan lewat `git ls-remote`: masih `8c62306…` (tidak bergeser selama P2).
- **Cakupan yang tidak diubah:** `worker/` (Worker Security) dan Live Test (tetap disabled). P1 UI/UX hanya disentuh pada empat titik fungsional (bukan visual): scaffold workflow di `RuleEditor` (+ prop `endpoint` lewat `App`→`ReviewRules`→`RuleEditor`), sampel unggahan sebagai default `TestCenter`, serta `runWorkflowOffline` (gerbang parser engine) yang dipakai `TestCenter` dan `Export`.
- **Tidak ada commit/deploy.**

Label: **PASS** = terbukti lewat tes yang dijalankan · **FAIL** · **BLOCKED** = terhalang blocker engine/data · **NOT_TESTED** = bukti belum ada.

---

## 1. Hasil audit kompatibilitas (Kotlin ↔ generator)

Diverifikasi dari source: `RemoteProviderConfig`, `RemoteProviderManager`, `WorkflowRemoteProviderRuntime`, `RuntimeFactory`, `WorkflowExecutor`, `RemoteSourceTrust`. Hanya ketidaksesuaian yang **terbukti** diperbaiki.

### Perubahan kontrak/perilaku

| Area | Sebelum | Sesudah (sesuai engine) |
|---|---|---|
| Validator workflow | `input` opsional, step bisa berdiri sendiri | Tiap step `select/regex/parse_html/parse_json/map/normalize/assert/filter` **wajib `params.input`** (engine tidak punya chaining implisit); `http` wajib `url` |
| `offlineTester.js` | Divergen dari engine (chaining implisit, template di semua param, dll.) — ditulis ulang | Port semantik `WorkflowExecutor`: template hanya di http url/headers/query/body & `normalize url_resolve.baseUrl`; variabel tak terselesaikan → `""`; default mode `select`/`regex` = FIRST; step gagal menghentikan workflow; `extractByPath` via `split(".")`; bug engine `episode_number` ditiru |
| Parser runtime | Tidak ada | `src/lib/contract/runtimeParsers.js`: port `parseSearchResults/parseEpisodesResult/parseSourcesResult/parseServers` (gerbang "dapat diproses engine") |
| Variabel injeksi | Tidak dimodelkan | `WORKFLOW_INJECTED_VARIABLES` per workflow; `SAMPLE_VARIABLES` untuk replay |
| Generator | Tetap menghasilkan JSON walau tidak valid | `generateProviderJson` memvalidasi akhir; `providerJson: null` bila tidak valid; `output.type/mapping` eksplisit |
| Status laporan | `ANILILI_READY` mudah tercapai | `JSON_INVALID/OFFLINE_FAILED/BLOCKED/ANILILI_READY/OFFLINE_PASSED/JSON_VALID`; `ANILILI_READY` **hanya** bila `testResults.android.passed === true` dan satu-satunya blocker kritis adalah `android-playback-untested` |
| Rule Editor → workflow | Menghasilkan step `select` tanpa fetch & tanpa `input` (ditolak validator baru; 7 tes E2E gagal) | `src/lib/generator/workflowScaffold.js`: `http → parse_html → select(input=dokumen)`, pola sama dengan config resmi. URL search `endpoint` + `query: {s: "{{query}}"}` adalah **tebakan** yang diedit pengguna di Workflow Builder |
| Test Center | Default HTML demo meski ada sampel unggahan | Default = sampel unggahan pertama (bila ada) |
| Status offline | `PASSED` bila tiap step lolos (walau parser engine tak mendapat data) | `runWorkflowOffline` (`offlineTester.js`): setelah semua step PASSED, output diumpankan ke port parser runtime. **0 item → `FAILED`**; `sources` tanpa satu pun URL → `BLOCKED`. Dipakai `TestCenter` dan `Export` (`engineParser` ikut di test report; badge status & `compatibility.status` di test report ikut berubah ke `OFFLINE_FAILED`). `OFFLINE_PASSED` kini berarti "diproses parser engine", bukan sekadar "selector cocok" |

### Daftar blocker engine (dari source, ditampilkan di UI & laporan validasi)

**Kritis (P2/P4):** `episode-slug-cache-stores-pipeid`, `sources-url-prefers-anime-slug`, `no-per-item-base64-decode`, `android-playback-untested`.
**P6 (engine b755e47):** dua blocker pipeId/slug pertama diperbaiki di engine P5 dan **dihapus** dari `CRITICAL_BLOCKERS`. Yang tersisa: `no-per-item-base64-decode`, `android-playback-untested`. Lihat `docs/P6_ENGINE_SYNC_REPORT.md`.
**Dugaan:** `polymorphic-ops-decode-suspected`, `kuramanime-search-parse-json-lists`.
**Batasan operasional (13):** termasuk `episode-number-op-strips-ep-from-episode`, `no-implicit-input-chaining`, `params-not-template-evaluated`, `workflow-dropped-on-parse-error`, `base64-decode-normalize-only`, dst. (lihat `OPERATIONAL_LIMITATIONS`).

Tes paritas: `tests/unit/contract.test.js` (+6), `tests/unit/offlineTester.test.js` (27, menggantikan suite lama 1621 baris yang **mengkodekan perilaku yang divergen dari engine** — bukan sekadar refactor), `tests/unit/runtimeParsers.test.js`, `tests/unit/workflowScaffold.test.js`.

---

## 2. Fixture dunia nyata

Sumber: fixture resmi di repo Anilili pada SHA di atas (`app/src/test/resources/fixtures/anichin/`) + dua config resmi (`app/src/main/assets/remote-provider-configs/{anichin,kuramanime}.json`, salinan verbatim, sha256 cocok). Provenance + sha256 asli/sanitasi + selectorCounts sebelum/sesudah sanitasi: `tests/fixtures/real/MANIFEST.json`. Sanitasi (`scripts/sanitize-fixtures.mjs`) hanya membuang `script`/`style`/`noscript`/komentar; jumlah elemen untuk selector yang dipakai config tidak berubah (dicatat di manifest). **Tidak ada fixture karangan** dalam `tests/fixtures/real/`; data sintetis tetap terpisah di tes unit.

| Berkas | Isi |
|---|---|
| `anichin/search.htm` | Halaman pencarian (hasil: 2 item) |
| `anichin/series.htm` | Detail seri + daftar episode |
| `anichin/watch.htm` | Halaman tonton + `select.mirror` |
| `kuramanime/search.htm` | Halaman HTML (bukan respons JSON) |

Tes: `tests/unit/realFixtures.test.js` (provenance), `tests/unit/realPipeline.test.js` (9), `tests/e2e/real-fixtures.spec.js`.

---

## 3. Hasil per workflow (replay offline; **bukan** uji jaringan/Android)

| Provider · Workflow | Label | Bukti & catatan |
|---|---|---|
| Config resmi anichin & kuramanime vs `validateProviderJson` | **PASS** | 0 error |
| Anichin · Search (config resmi) | **PASS** | Lewat gerbang `runWorkflowOffline`: CSS selector, mapping, template `{{query}}` terbukti; parser engine = **2 item** |
| Anichin · Detail/Episodes (config resmi) | **PASS** (dengan temuan) | 168 baris terpilih → parser engine membuang 2 non-numerik (`HIATUS!`, `LIBUR!`) → **166**, terurut naik. `pipeId` = `remote/anichin/<anilistId>/sub/<url>` |
| Anichin · Servers | **NOT_TESTED** | Config resmi tidak punya workflow `servers` |
| Anichin · Sources (config resmi) | **BLOCKED** | 12 `option` → 11 mirror (placeholder kosong dibuang), tipe `embed`, namun **0 URL**: nilainya blob base64; engine tidak men-decode per item (`no-per-item-base64-decode`). Playback **NOT_TESTED** |
| **Output generator otomatis** (analyzer→scaffold) · Search / Episodes / Sources, sampel nyata Anichin | **FAIL** | JSON valid menurut kontrak dan semua step `PASSED`, tetapi parser engine mendapat **0 item** (builder belum menginfer `output.type`/`mapping` → RAW dengan `mapping {}`; href tidak dipilih). Gerbang menandai `FAILED`. Diuji di `tests/unit/realGenerator.test.js` (5 tes, termasuk pembanding config resmi lewat gerbang yang sama) |
| Kuramanime · Search | **BLOCKED** | Workflow memakai `parse_json`, fixture adalah HTML → step FAILED ("JSON parsing failed"). Perlu HAR respons `need_json=true` |
| Kuramanime · Episodes/Detail, Servers, Sources | **NOT_TESTED** | Tidak ada fixture (fixture `search.htm` tidak berisi daftar episode) |
| Pagination | **NOT_TESTED** | Tidak ada fixture multi-halaman |
| URL resolution (`url_resolve`) | **PASS (sintetis saja)** | Hanya di tes paritas dengan data sintetis; config resmi tidak memakainya, href fixture dipakai apa adanya |
| Template context (`{{url}}`, `{{query}}`, variabel injeksi) | **PASS** | Replay dengan `SAMPLE_VARIABLES`; kegagalan resolusi → `""` seperti engine |
| Playback di Android | **NOT_TESTED** | Tidak ada perangkat/uji Android |
| Live Test (jaringan) | **NOT_TESTED** | Tetap disabled sesuai instruksi |

### Temuan dari data nyata

1. **Nomor episode rusak (engine):** parser membersihkan nomor dengan `[^0-9.]`. Label `"04 [56]"` → **456** (bukan 4). Ada 4 label berpola `NN [MM]` (mis. 355, 456). Diverifikasi di `realPipeline.test.js`. Ini perilaku engine, bukan bug generator; dicatat sebagai risiko data.
2. **Sources Anichin tidak dapat diputar via engine saat ini** karena blob base64 per item (lihat blocker).
3. **Kualitas analyzer:** pada `search.htm`, heuristik memilih `div.listSchh > h2` (8 elemen — daftar jadwal) sedangkan config resmi memakai `.listupd article.bs a.tip` (2 hasil pencarian). Container "search" juga berupa `html > body…` (lolos tes tapi tidak bermakna). Aturan hasil analisis **wajib ditinjau manual**; perbaikan heuristik di luar lingkup P2.
4. **Uji offline me-replay satu HTML** (sampel unggahan pertama) untuk semua workflow; selector hasil analisis `watch.htm` tidak ada di `search.htm`. Pemetaan sampel per workflow belum ada. Step-level tetap terbukti (`div.listSchh > h2" → 8 elemen`), tetapi status workflow = `FAILED` oleh gerbang parser (temuan 5).
5. **Perbedaan hasil: "step lolos" ≠ "engine memproses".** Sebelum gerbang, output generator tampak `OFFLINE_PASSED`; setelah gerbang, 0 item di parser engine. Label `OFFLINE PASSED` di UI hanyalah nama tier (selalu tampil), bukan hasil — tes E2E lama yang hanya mengecek teks itu diperkuat (`full-flow.spec.js`).
6. **Konflasi `endpoint`:** label form "URL provider.json" vs praktik pengisian situs/`metadata.sourceUrl`; scaffold memakai `endpoint` sebagai URL search sehingga bisa keliru bila yang diisi URL config. Perlu keputusan UX (di luar P2).
7. **Sensitif:** grep `csrf|_token|nonce|sessionid|token=` + pola email pada keempat fixture: satu hit (`<meta name="csrf-token" content="[REDACTED]">` di `kuramanime/search.htm`), sudah tersanitasi; tidak ada email/token lain.
8. Status "REPLAY fixture" bukan bukti jaringan, redirect, kebijakan, maupun Android (tertulis di evidence tiap step http).

---

## 4. Validasi end-to-end generator

Alur Upload → Analyze → Review Rules → Build Workflow → Generate JSON → Offline Test → Export dijalankan di Playwright (`tests/e2e/real-fixtures.spec.js`) dengan `search/series/watch.htm` nyata:

| Tahap | Label | Bukti |
|---|---|---|
| Upload & parse 3 berkas | **PASS** | "3 berhasil" |
| Analisis berasal dari berkas | **PASS** | UI menampilkan panjang karakter sama dengan isi berkas; selector `div.listSchh` ada di `search.htm` |
| Review → workflow | **PASS** | Scaffold `http → parse_html → select(input)` |
| Generate JSON patuh kontrak | **PASS** | Badge VALID; blocker `android-playback-untested` tampil; berkas unduhan `provider.json` di-parse ulang dan lolos `validateProviderJson`, tiap step non-http punya `params.input` (E2E) |
| Offline Test | **FAIL** (output generator) | Step PASSED, tetapi pesan "Parser engine (…) menghasilkan 0 item" tampil (E2E menegaskan) |
| Diproses engine Kotlin (parser port) | **FAIL** untuk output generator otomatis; **PASS** untuk config resmi (replay); **NOT_TESTED** pada engine Kotlin asli | Port JS bukan engine Kotlin sebenarnya |
| Status kompatibilitas (badge Export, teramati) | `OFFLINE_FAILED` | Hasil gerbang parser kini diteruskan ke `generateValidationReport` (`Export.jsx`: status diturunkan dari `offlineResults`; sebelumnya beku di `JSON_VALID`). E2E menegaskan nilai persis; `ANILILI_READY` tidak bisa tercapai tanpa uji Android |

---

## 5. Regresi

| Pemeriksaan | Hasil |
|---|---|
| ESLint (`src tests scripts`) | bersih (0 error, 0 warning) |
| Vitest | **293/293** lulus (17 file). Jumlah turun dari 315 (P1) karena suite lama `offlineTester.test.js` (1621 baris, mengkodekan perilaku divergen) diganti 27 tes paritas |
| Coverage `src/lib` | **89,3%** statement. Semua berkas ≥81% kecuali `projectPropTypes.js` **0%** (hanya konstanta PropTypes, tidak punya logika; tidak dites). Seluruh `src` (termasuk `pages`/`components`, dites lewat Playwright, bukan Vitest) = 47,9% — tidak diklaim |
| Playwright | **69/69** lulus (chromium, mobile-chrome, tablet-chrome × 23 tes) |
| Production build (`vite build`) | berhasil |

Sebelum perbaikan scaffold, 7 tes E2E chromium gagal karena builder UI menghasilkan workflow yang ditolak kontrak baru. Perbaikan dilakukan di builder (bukan melemahkan tes). Satu asersi `full-flow.spec.js` (`text=OFFLINE PASSED`, selalu lolos karena itu label tier) **diperkuat**, bukan dilemahkan, agar mengecek hasil gerbang parser.

---

## 6. Blocker & tindakan berikutnya

| # | Blocker/kebutuhan | Tindakan |
|---|---|---|
| 1 | Playback Android belum diuji | Uji di perangkat dengan build Anilili pada SHA ref; baru kemudian set `testResults.android.passed` |
| 2 | Sources Anichin = base64 per item | Perlu perubahan engine (decode per item) **atau** jalur lain; tidak diubah (repo Android dilarang) |
| 3 | Kuramanime Search | Sediakan HAR respons AJAX `need_json=true` (sanitasi token/cookie) |
| 4 | Kuramanime detail/episodes/servers/sources | Sediakan HTML/HAR halaman detail, daftar episode, dan AJAX server |
| 5 | Pagination | Sediakan sampel halaman ke-2 |
| 6 | Servers Anichin | Tidak ada workflow resmi; butuh sampel jika diinginkan |
| 7 | Uji offline per-workflow | Pemetaan sampel per workflow (fitur baru, belum dikerjakan) |
| 8 | Heuristik analyzer lemah | Perbaiki setelah ada korpus fixture nyata lebih banyak |
| 9 | `episode_number` & nomor `NN [MM]` | Risiko data engine; hindari op `episode_number` pada label "Episode N" |
| 10 | **Builder belum menginfer `output.type`/`mapping` dan href** → output generator otomatis = FAIL di gerbang parser | Inferensi mapping (mis. `titles/urls`) dari rule DETECTED; prioritas tertinggi agar alur otomatis menghasilkan provider yang diproses engine |
| 11 | Konflasi `endpoint` (lihat temuan 6) | Pisahkan field "URL situs" dan "URL config" |
