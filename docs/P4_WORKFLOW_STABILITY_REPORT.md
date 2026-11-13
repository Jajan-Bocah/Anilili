# P4 — Workflow Stability & Android Compatibility Verification

Tanggal: 2026-11-13. Lanjutan P3. Belum commit, belum deploy. Repo Android dan Worker tidak diubah.

**Status kompatibilitas Anilili: `OFFLINE_PASSED` (maksimal). BUKAN `ANILILI_READY`** — tidak ada bukti perangkat/emulator Android. Lihat bagian 3–4.

## 1. Ringkasan

| Area | Hasil |
|---|---|
| P0 Edit manual workflow tidak tertimpa | SELESAI, terbukti E2E + unit |
| P1 Verifikasi kontrak Kotlin | SELESAI untuk JVM; ditemukan blocker Sources pada jalur produksi Android |
| P2 Tes regresi | 9 unit + 3 E2E (×3 viewport) ditambahkan |
| P3 Gerbang kualitas | Semua hijau |

## 2. P0 — Perubahan

**Akar masalah (audit):** `WorkflowBuilder` memegang salinan state `data` lokal, sementara `App` menimpa `project.workflows` setiap inferensi berubah. Edit manual di Builder hilang saat Rule Editor berubah / navigasi, dan tidak ada pembeda workflow otomatis vs manual.

**Model:** `project.workflowOrigin[nama] = 'auto' | 'manual'`.
- Hanya workflow `auto` (atau yang belum ada) yang diperbarui/dihapus oleh inferensi.
- Workflow tanpa catatan origin (proyek lama, hasil impor provider.json) dianggap `manual`; bila isinya identik dengan inferensi dinormalisasi ke `auto`.
- Satu-satunya jalan menimpa edit manual: tombol "Regenerasi `<nama>` dari aturan" → konfirmasi inline (`role="alert"`) → "Ya, timpa" / "Batal".
- Tanpa dependency baru dan tanpa state manager baru: fungsi murni + satu field di project. Autosave, ekspor bundel (`...project`), dan impor ikut otomatis.

**Berkas:**

| Berkas | Perubahan |
|---|---|
| `src/lib/generator/workflowSync.js` (baru) | `applyInference`, `markManual`, `regenerateWorkflow`, `manualConflicts`, `originOf`, `sanitizeWorkflowOrigin` |
| `src/components/WorkflowBuilder.jsx` | Terkontrol (`workflows`, `origin`, `onEdit`); badge "otomatis"/"diedit manual"; `data-testid` kartu |
| `src/pages/ReviewRules.jsx` | Kartu peringatan konflik + konfirmasi regenerasi |
| `src/App.jsx` | Tiga callback stabil; `project.workflows`+`workflowOrigin` jadi sumber tunggal |
| `src/lib/import/importProject.js` | Sanitasi `workflowOrigin` dari bundel tak tepercaya |
| `src/lib/projectPropTypes.js` | PropType `workflowOrigin` |

Perbaikan sampingan: impor provider.json tanpa rule tidak lagi menghapus workflow-nya.
`RuleEditor.jsx` dan `workflowInference.js` tidak diubah.

## 3. P1 — Kompatibilitas Kotlin (ringkas; detail di `docs/P4_KOTLIN_EVIDENCE.md`)

Repo Android: branch `feature/hybrid-remote-provider-engine`, **HEAD `8c6230665699978963aa3e77dd791ceaced94219`**. `git status` sebelum dan sesudah identik (`?? docs/PONYTAIL_AUDIT.md`, bukan dari kami).

**Dieksekusi di engine Kotlin nyata (JVM, HTTP disimulasikan fixture asli Android, tanpa perangkat/jaringan):**
- Provider.json generator lolos decode strict dan `RemoteProviderConfigValidator` (`isValid=true`).
- Search = 2 item, Episodes = 166 item. `ProviderFixtureOfflineTest` bawaan Android 5/5 lulus.
- Selector Jsoup 1.18.3 vs jsdom sama: 168 anchor / search 2 / iframe 1 / option 12.

**168 anchor → 166 episode:** dua anchor adalah pengumuman (`HIATUS!` indeks 11, `LIBUR!` indeks 119). Teks `epl-num` tanpa digit → `toDoubleOrNull()` null → dibuang `mapNotNull` (`WorkflowRemoteProviderRuntime.kt:334`). JS dan Kotlin berperilaku sama; ini perilaku engine, bukan bug generator.

**Format slug / pipeId:** `urls` Search harus berawalan `/` (dirangkai `endpoint + slug`), tetapi URL episode tidak boleh berawalan `/` karena pipeId `remote/{id}/{anilistId}/sub/{url}` di-split `/` dan memakai `parts[4]`. Dengan href mentah pipeId menjadi `sub//slug/` → `parts[4]` kosong.

**Sources — iframe:** `watch.htm` punya 1 iframe `https://ok.ru/videoembed/16438442330802`; parser Kotlin mengklasifikasikannya `embed`. Ini hanya bukti format, **bukan klaim playable**.

## 4. Blocker dan risiko tersisa

| ID | Tingkat | Temuan |
|---|---|---|
| B1/B2 | **BLOCKER** | Jalur produksi `getSourcesForPipeId` tidak mengisi `{{url}}` → tidak ada request HTTP (`REQ=[]`, `streams=null`). `getSources` mengarah ke halaman seri. Config resmi `anichin.json` punya cacat yang sama (cacat di Android, bukan khas generator). |
| B1b | Kontrak | Asimetri slug (di atas). Mitigasi kandidat (regex ALL, slug telanjang) hanya dari pembacaan source, **NOT_TESTED**. |
| R3 | Risiko | Tanpa blok `security`: batas respons default 1 MiB, tanpa allowlist domain. |
| R6 | Risiko | Nomor `"04 [56]"` digabung jadi 456 (juga 355/254/153); nomor 153 ganda, cache slug tertimpa. |
| R4 | Risiko | `servers` tidak dihasilkan; mirror base64 tidak bisa didecode per item. |
| R5 | Risiko | `supportsSearch/requiresWebView` berbeda dari config resmi; dampak tidak diuji. |
| R7 | Dugaan | Diskriminator polimorfik `normalize/assert/filter` mungkin gagal decode; NOT_TESTED, jangan keluarkan op itu tanpa bukti. |

**NOT_TESTED:** pemutaran di Android, emulator/perangkat, jaringan/TLS/redirect nyata, `MiruroRepository` end-to-end, R7, mitigasi slug telanjang. Brief P4 hanya meminta verifikasi dan pelaporan, sehingga temuan kontrak **tidak** diperbaiki di generator.

## 5. Hasil tes (P3 → P4)

| Gerbang | P3 | P4 |
|---|---|---|
| ESLint | bersih | bersih |
| Vitest | 315 | **324/324** (20 berkas; +9 `workflowSync.test.js`) |
| Playwright (chromium + mobile-chrome + tablet-chrome) | 78 | **87/87** (+3 tes × 3 viewport: `p4-workflow-stability.spec.js`) |
| Production build | sukses | sukses |
| Coverage `src/lib` | lines 90,9% | ≈91,1% statements; `workflowSync.js` 100% statements / 86,4% branch. (Metrik berbeda; "All files" 50,8% memuat UI yang tidak dicakup Vitest) |

Seluruh gerbang dijalankan ulang **setelah** uji mutasi dipulihkan dan setelah penguatan tes di bawah: ESLint bersih, Vitest 324/324, build sukses, Playwright 87/87.

**Penguatan tes (hasil review):**
- Cek `ANILILI_READY` di E2E kini punya jangkar positif (`badges.length > 0` dan `toContain('OFFLINE_PASSED')`), jadi tidak lulus kosong bila locator rusak.
- `tests/unit/realGenerator.test.js` kini menegaskan URL iframe Sources persis `https://ok.ru/videoembed/16438442330802` (sebelumnya hanya jumlah item). Ini bukti format, bukan klaim playable.

**Cakupan regresi P2:**
- Edit manual tidak tertimpa saat rule berubah dan navigasi bolak-balik; Batal tidak mengubah apa pun; "Ya, timpa" meregenerasi sesuai rule baru.
- Edit manual bertahan: autosave → IndexedDB, reload, ekspor provider.json dan bundel (`workflowOrigin` memuat `episodes:'manual'`), hapus DB lalu impor bundel memulihkannya.
- Alur parser tetap nyata setelah edit manual: Search 2, Episodes 166, Sources 1.
- Badge Export bukan `ANILILI_READY` tanpa bukti Android.
- Workflow impor provider.json tanpa origin dianggap manual dan tidak dihapus.

**Uji mutasi:** proteksi manual di `applyInference` dinonaktifkan → spec P4 gagal 3/3; setelah dipulihkan, hijau kembali.

Catatan: E2E memakai `npm run preview` dari `dist/`, jadi wajib `npm run build` sebelum Playwright.

## 6. Definition of Done

- [x] Edit manual workflow aman dari overwrite tidak disengaja (navigasi, autosave/reload, ekspor/impor).
- [x] Alur generator tetap lulus pengujian nyata (2 / 166 / 1); tanpa regresi.
- [x] Status berdasarkan bukti aktual: `OFFLINE_PASSED`, tidak ada klaim `ANILILI_READY`.
- [ ] Kompatibilitas Android penuh: **tidak tercapai**, tertahan blocker B1/B2 di sisi engine Android.

## 7. Langkah berikutnya (butuh keputusan)

1. Tentukan: perbaiki `{{url}}`/pipeId di engine Android (B1/B2), atau terapkan mitigasi slug telanjang di generator (uji dulu lewat `GeneratorEvidenceTest` di salinan).
2. Tambah blok `security` dan peringatan nomor ganda/lonjakan di generator (R3, R6).
3. Uji di emulator/perangkat sebelum status apa pun dinaikkan.
