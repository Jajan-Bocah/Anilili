# P3 — Functional Workflow Generation & Inference Accuracy

Lanjutan `docs/P2_COMPATIBILITY_REPORT.md`. Status: **SELESAI lokal, belum commit/deploy.** Repo Android Anilili dan Worker Security tidak diubah.

## Ringkasan hasil

| Workflow | P2 (baseline, gerbang parser sama) | P3 otomatis | Config resmi Anichin |
|---|---|---|---|
| Search | FAILED, 0 item | PASSED, **2 item** (title+URL `href`) | 2 item |
| Episodes | FAILED, 0 item | PASSED, **166 item** (URL, nomor, judul) | 166 item |
| Sources | FAILED, 0 item | PASSED, **1 item** (iframe `src` ok.ru) | BLOCKED (mirror base64) |
| Servers | – | NEEDS_REVIEW (tidak dibuat) | – |

Hasil search/episodes identik dengan config resmi (URL dan judul sama persis). Mirror dropdown Anichin (11 opsi base64, 0 URL) tetap **BLOCKED**. Status Export yang teramati: `OFFLINE_PASSED` — bukan `ANILILI_READY` (tidak ada uji Kotlin/Android asli; blocker `android-playback-untested` tetap tampil).

## Perubahan

**Baru**
- `src/lib/analyzer/domStructure.js` — helper DOM murni (grup elemen berulang, rantai ancestor, deteksi boilerplate).
- `src/lib/generator/workflowInference.js` — susun workflow penuh (`http → parse_html → select → output.type/mapping`) dari rule terbukti; bukti kurang → `workflow: null` + alasan (NEEDS_REVIEW).
- `src/lib/samples/sampleMap.js`, `src/lib/samples/runMapped.js` — pemetaan sampel per kategori (search, episodes, sources, servers, homepage), auto-assign dari bukti + petunjuk nama, replay per workflow (tanpa sampel → `NOT_TESTED`).
- Tes: `workflowInference.test.js`, `sampleMap.test.js`, `configSourceUrl.test.js`, `tests/e2e/p3-regression.spec.js`.

**Ditulis ulang / diubah**
- `src/lib/analyzer/ruleInference.js` — analyzer berbasis struktur DOM + skor bukti; tanpa class/situs yang di-hardcode; ambang `DETECTED ≥ 0.65`, `REVIEW ≥ 0.4`; tidak memaksa DETECTED saat bukti rendah.
- `src/components/RuleEditor.jsx` — edit selector, atribut DOM, `saveAs`; panel status inferensi per workflow; manual override dipertahankan.
- `src/pages/Analyze.jsx`, `TestCenter.jsx`, `Export.jsx`, `App.jsx` — peta sampel (manual + otomatis, banyak sampel per kategori), replay per workflow, bundel menyertakan `sampleMap`.
- `src/pages/CreateProject.jsx`, `src/lib/generator/providerGenerator.js`, `src/lib/import/importProject.js`, `src/lib/projectPropTypes.js` — **Provider Endpoint** dipisah dari **Config Source URL** (`metadata.sourceUrl`, tanpa fallback/URL karangan; belum diisi → "BUTUH INPUT"); migrasi proyek lama (endpoint berakhiran `.json` → config URL); `sampleMap` tak tepercaya disanitasi saat impor.
- Spec E2E diperbarui dengan hasil nyata: `real-fixtures.spec.js` (search 2 / episodes 166 / sources 1, `OFFLINE_PASSED`), `full-flow.spec.js`, `audit.spec.js` (edit selector + atribut + `saveAs` masuk `provider.json` dan `output.mapping`). Assertion tidak dilemahkan; yang berubah hanya nilai harapan yang memang berubah (sebelumnya menegaskan kegagalan P2).

**Dihapus:** `workflowScaffold.js` (+ tesnya) — digantikan inferensi.

## Akurasi inferensi selector (fixture nyata Anichin, tanpa kode khusus provider)

- Search: himpunan elemen `href` hasil selector otomatis (`article.bs > div.bsx > a.tip`) **sama persis** dengan selector resmi; title dari atribut `title`; param query `s` (GET, action `/`) → precision = recall = 1.
- Episodes: `div.eplister > ul > li > a` — himpunan `href` sama persis dengan selector resmi (selector mencocokkan 168 anchor; parser runtime menerima 166 item, sama dengan config resmi); nomor (`div.epl-num`) dan judul (`div.epl-title`) terpisah, confidence 0.94.
- Watch: `div#pembed > iframe` atribut `src` DETECTED; dropdown mirror NEEDS_MANUAL_REVIEW (tidak dipaksa).
- Anti-overfit (Kuramanime, analyzer yang sama): search 18 judul bersih, URL `/anime/\d+/`; daftar episode **tidak** dipaksa DETECTED (0.48, sidebar komentar) → workflow episodes NEEDS_REVIEW.
- Tidak memilih `html`/`body`, nav/menu, atau widget jadwal (dites).

## Hasil tes

| Gerbang | Hasil | Baseline P2 |
|---|---|---|
| ESLint | 0 error / 0 warning | 0/0 |
| Vitest | **315 lulus** (19 file) | 293 |
| Coverage `src/lib` (agregat, metrik sama dengan P2) | **90.9%** lines (branches 73.3%, functions 96.5%); per modul: analyzer 98.0, samples 97.8, generator 92.8, tester 92.8, parsers 94.1, import 89.0, contract 85.1, db 100. Baris 0% di akar `src/lib` hanyalah `projectPropTypes.js` (deklarasi PropTypes, tanpa logika; tidak dieksekusi vitest) | 89.3% |
| Playwright (chromium, mobile-chrome, tablet-chrome) | **78/78** (69 lama + 9 regresi P3: 3 spec × 3 project) | 69/69 |
| `npm run build` | sukses | sukses |

Catatan: E2E memakai `vite preview` dari `dist/` — jalankan `npm run build` dulu agar tidak menguji build basi.

Cakupan regresi yang diminta: (1) search → parser Search 2 item; (2) episodes → parser Episodes 166; (3) watch → sources 1 item, mirror base64 BLOCKED; (4) pemilihan sampel memengaruhi replay (E2E + unit); (5) edit selector/atribut/`saveAs` memengaruhi `provider.json`; (6) autosave → reload memulihkan pemetaan di UI dan replay tetap jalan (166 item) tanpa impor; bundel ekspor/impor mempertahankan `sampleMap`, endpoint, config URL; (7) migrasi proyek lama (unit + E2E; endpoint yang dikosongkan migrasi kini memunculkan "BUTUH INPUT: Provider Endpoint" + input inline di Export, bukan "Belum ada data proyek"); (8) axe/overflow audit 375/768/1280 tetap lulus.

## Blocker / keterbatasan tersisa

1. **Playback Android NOT_TESTED** — tanpa uji Kotlin/Android asli status tidak boleh `ANILILI_READY`.
2. **Anichin mirror base64** (per item, engine tidak punya decode per item) — Sources via dropdown BLOCKED; hanya iframe langsung yang lolos.
3. **Servers tidak dibuat otomatis** — engine butuh list Map; `select` hanya menghasilkan string.
4. **Kuramanime** — butuh HAR (`need_json=true`); episode-list tidak terbukti dari HTML.
5. Pagination NOT_TESTED; live test produksi (Access/JWKS, rate limit, CORS) di luar cakupan.
6. Edit manual di WorkflowBuilder dapat tertimpa saat RuleEditor menghitung ulang workflows (perilaku lama, belum diatasi). Proyek lama dengan id rule lama hanya mendapat NEEDS_REVIEW sampai analisis ulang.
