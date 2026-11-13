# Anilili Provider JSON Generator

Generator `provider.json` untuk Anilili — wizard langkah demi langkah untuk membuat, memvalidasi, menguji, dan mengekspor provider JSON. Termasuk **Live Test Worker** di Cloudflare Workers yang **nonaktif secara default** dan hanya bisa diaktifkan dengan konfigurasi keamanan yang benar.

## Stack

- **Frontend:** React 18 + Vite 5 + Tailwind CSS 3
- **State/persistensi:** IndexedDB (auto-save proyek)
- **Testing:** Vitest (unit + integrasi), Playwright (E2E Chromium/mobile/tablet)
- **Edge (opsional):** Cloudflare Worker — Live Test proxy

## Perintah

```bash
npm install          # pasang dependency
npm run dev          # jalankan dev server (http://localhost:5173)
npm run build        # build produksi ke dist/
npm run preview      # preview hasil build
npm test             # jalankan semua unit/integration tests
npm run lint         # ESLint
npx playwright test  # E2E Chromium (butuh build + preview)
```

## Live Test Worker — Status Keamanan

Worker (`worker/index.js`) adalah proxy HTTP sisi server untuk "Live Test" provider endpoint. **Tidak ada shared secret di frontend. Tidak menjadi open proxy publik.**

**Default: DISABLED.** Jika `SECRET_ALLOWED_DOMAINS` kosong, semua request ditolak dengan `503`.

Untuk mengaktifkan, Anda harus menyelesaikan konfigurasi di bawah. **Jangan aktifkan Live Test publik sebelum seluruh langkah keamanan dan security tests selesai.**

## Konfigurasi Cloudflare (Wajib sebelum Live Test)

### 1. Environment variables (`wrangler.toml` / secrets)

| Variabel | Deskripsi | Default |
|----------|-----------|---------|
| `SECRET_ALLOWED_DOMAINS` | Domain yang diizinkan sebagai target proxy, dipisahkan koma (contoh: `example.com,anilili.site`). Kosong = Worker nonaktif. | kosong |
| `CORS_ALLOWED_ORIGIN` | Origin frontend yang dikenal untuk CORS (contoh: `https://anilili.pages.dev`). Jangan `*`. | kosong |
| `ACCESS_JWT_ENABLED` | Jika `"true"`, Worker wajib memverifikasi JWT Access dari header `Cf-Access-Jwt-Assertion` (fallback bila Access policy tidak dipasang di route). | `"false"` |
| `ACCESS_ISSUER` | `issuer` yang diterima pada JWT Access (opsional; jika kosong, check issuer dilewati). | kosong |
| `ACCESS_AUDIENCE` | `audience` yang diterima pada JWT Access (disarankan = nama Worker). | kosong |

Set via `wrangler secret put` untuk nilai rahasia, atau di `[vars]` untuk nilai non-rahasia.

### 2. Cloudflare Access (disarankan)

Pasang **Cloudflare Access policy** pada route Worker di dashboard Cloudflare. Dengan Access aktif, request yang tidak terautentikasi ditolak di edge sebelum mencapai Worker, dan header `cf-access-authenticated-identity/email` yang diterima Worker dapat dipercaya.

**Fallback JWT (opsional):** Jika Access policy tidak dipasang, set `ACCESS_JWT_ENABLED = "true"` di vars. Worker akan memverifikasi `Cf-Access-Jwt-Assertion` secara manual (format, `exp`, `issuer`, `audience`). Verifikasi signature penuh terhadap JWKS publik Cloudflare **tidak** dilakukan di Worker — hanya check struktural/klaim. Karena itu, jalur yang paling aman tetap **Cloudflare Access di edge**.

### 3. Rate Limiting (Cloudflare)

- **Jangan** mengandalkan rate limiting in-memory per isolat (tidak shared antar edge isolat).
- Gunakan **Cloudflare Rate Limiting Binding** di `wrangler.toml`:
  ```toml
  [[rate_limit]]
  binding = "RATE_LIMIT"
  namespace_id = "<namespace-id-dari-dashboard>"
  period = 60
  limit = 30
  ```
- Jika binding belum dikonfigurasi, Live Test **fail-closed** (mengembalikan 429 saat rate limit, dan menolak saat binding tidak tersedia).
- Key rate limit = identitas pengguna Access + hostname target.
- Tidak diklaim strict global; tidak perlu Durable Objects kecuali kebutuhan benar-benar terbukti.

### 4. Deployment (tidak dilakukan oleh alat ini)

```bash
wrangler secret put SECRET_ALLOWED_DOMAINS
wrangler secret put CORS_ALLOWED_ORIGIN
wrangler deploy
```

Verifikasi `wrangler.toml` untuk target Workers Static Assets:
- Frontend build ke `dist/` (`vite build`)
- Worker entrypoint `worker/index.js`
- Static assets via `[assets]` dengan `directory = "dist"` dan `binding = "ASSETS"`
- Route `/api/*` diarahkan ke Worker API, **bukan** SPA fallback

## Dokumen Keamanan

- `docs/SECURITY_AUDIT.md` — hasil audit keamanan Worker + temuan tetap (residual risks) + langkah verifikasi yang hanya bisa dilakukan di Cloudflare production.
- `docs/COMPATIBILITY_REPORT.md` — laporan kompatibilitas terhadap engine Anilili.
- `docs/Anilili_Provider_Generator_Audit_2026-11-13.md` — audit komprehensif proyek.

## Struktur

```
src/
  components/   RuleEditor, WorkflowBuilder, dll.
  pages/        CreateProject, UploadSamples, Analyze, ReviewRules, GenerateJson, Export, TestCenter
  lib/
    generator/  providerGenerator (generateProviderJson)
    contract/   engineContract (validateProviderJson, getBlockerStatus)
    tester/     offlineTester (executeWorkflow)
    parsers/    harParser, mhtmlParser (dengan sanitasi data sensitif)
    analyzer/   ruleInference
  workers/      htmlWorker, harWorker, mhtmlWorker (Web Workers)
worker/
  index.js      Cloudflare Worker Live Test proxy (disabled by default)
tests/
  unit/         unit tests (incl. security.test.js)
  integration/  flow tests
  e2e/          Playwright full flow
```

## Lisensi

Proyek pribadi. Tidak ada lisensi publik yang ditetapkan.
