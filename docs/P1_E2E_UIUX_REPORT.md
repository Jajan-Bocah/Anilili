# P1 — E2E Stability & Final UI/UX (2026-11-13)

## Hasil akhir
| Gate | Hasil |
|---|---|
| `npm run lint` | 0 error, 0 warning (sebelumnya 133 warning) |
| `npm test` (Vitest) | 315/315 lulus, 12 file |
| `npm run build` | sukses |
| `npx playwright test` (chromium + mobile-chrome + tablet-chrome) | 66/66 lulus; diulang 3× penuh + 5× chromium (22/22) tanpa flaky |
| Coverage `src/lib/*` | analyzer 81.5%, contract 90.6%, db 100%, generator 88.3%, import 85.7%, parsers 94.1%, tester 96.3% (≥80%) |
| Audit axe WCAG 2.x A/AA + overflow horizontal | 7 langkah × 375/768/1280 px: 0 pelanggaran |

Screenshot: `docs/screenshots/{375,768,1280}-{1-create … 7-export}.png` (27 berkas, `AUDIT_SHOTS=1 npx playwright test tests/e2e/audit.spec.js`).

## 9 kegagalan awal → akar masalah
| Jumlah | Klasifikasi | Akar masalah | Perbaikan |
|---|---|---|---|
| 8 (mobile-chrome) | Application bug | Step indicator meluber (dokumen 673px) sehingga tombol tertutup ("intercepts pointer events") | `<ol>` tanpa overflow, label `sr-only` di mobile, `aria-current="step"` |
| 1 (Loading state) | Test race | State transien "Memparsing" terlewat polling | `MutationObserver` merekam state sebelum klik (tanpa sleep/retry) |

## Bug aplikasi tambahan yang ditemukan audit
- Loop re-render tak berujung di Tinjau Aturan (callback inline di dependency `useEffect` WorkflowBuilder) → autosave tak pernah jalan. Fix: `handleWorkflowsChange` stabil (`useCallback`).
- Edit selector/rule hilang saat kembali/reload: `ruleValues` & `inferredRules` kini disimpan di `project` (IndexedDB).
- Form Buat Proyek kosong setelah restore/Buka/impor (state lokal hanya diinisialisasi saat mount): kini diturunkan langsung dari `project`.
- Kontras WCAG AA (slate-400/500, brand/green/yellow/red/orange-600) dinaikkan; region scroll diberi `role="region"`, label, `tabIndex`; kartu sampel Analyze kini keyboard-accessible (Enter/Spasi).

## ESLint (133 → 0)
- 107 `react/prop-types`: PropTypes nyata (`src/lib/projectPropTypes.js`, dependensi `prop-types`).
- 24 unused vars dihapus (termasuk kode mati `sanitizeHeaders`/`isSensitiveHeader` di mhtmlParser, param `baseUrl` tak terpakai di `parseHar`/`inferRules`).
- hooks deps (ref cleanup UploadSamples, dependensi berlebih Analyze), jsx-a11y click-key-events. Tidak ada rule yang dinonaktifkan.

## Tes E2E baru (`tests/e2e/audit.spec.js`)
Audit 7 langkah × 3 viewport; navigasi keyboard penuh + indikator fokus; perubahan selector memengaruhi provider.json & bertahan saat kembali; autosave IndexedDB (poll state) + restore setelah reload.

## Blocker produksi (tidak diubah, Live Test tetap nonaktif)
Cloudflare Access/JWKS, rate limiting produksi, CORS, routing, redirect belum diverifikasi; risiko DNS rebinding tetap terdokumentasi (SECURITY_AUDIT.md). Verifikasi 3 file Kotlin (RemoteProviderConfig/Manager/Runtime) sebelum lock engineContract.js belum dilakukan. Worker Security & repo Android tidak disentuh; tidak ada commit/deploy.
