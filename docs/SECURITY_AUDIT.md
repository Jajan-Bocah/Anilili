# Audit Keamanan — Cloudflare Worker (`worker/index.js`)

Tanggal: 2026-11-13 · Cakupan: Worker proxy Live Test, `wrangler.toml`, test keamanan.
Status keseluruhan: **P0 BELUM dapat dinyatakan selesai.** Kode dan test sudah ada dan lulus secara lokal,
tetapi beberapa kontrol bergantung pada konfigurasi Cloudflare yang **belum pernah diverifikasi pada deployment nyata**
(bagian 4). **Live Test tetap nonaktif** sampai bagian 4 divalidasi. Tidak ada commit dan tidak ada deploy.

## 1. Cara membaca dokumen ini

Setiap klaim diberi tingkat bukti. Jangan menyamakannya.

| Tingkat | Arti | Seberapa kuat |
|---|---|---|
| **A. Unit/mock (Vitest)** | Kode Worker dijalankan di Node dengan `fetch` di-stub dan binding palsu. JWT memakai keypair sungguhan, tetapi JWKS di-stub. | Membuktikan logika. Tidak membuktikan perilaku Cloudflare. |
| **B. Runtime Worker lokal** | `wrangler dev` (workerd) dengan `wrangler.toml` asli. | Membuktikan Worker + konfigurasi dapat dimuat dan berjalan di workerd. Binding rate limit berstatus `local`. |
| **C. Perlu verifikasi pasca-deploy** | Bergantung pada Cloudflare Access, JWKS asli, rate limit produksi, jaringan keluar. | **Belum terbukti.** |

## 2. Temuan yang ditemukan dan diperbaiki selama pekerjaan ini

Dicantumkan agar jelas bahwa klaim "sudah aman" sebelumnya tidak sepenuhnya benar.

| # | Temuan | Dampak | Status |
|---|---|---|---|
| 1 | `wrangler.toml` tidak valid (skema `ratelimits`, `pages`, `main`). Klaim sebelumnya bahwa Wrangler sudah terverifikasi **salah**. | Deploy gagal / rate limit tidak terpasang. | Diperbaiki; `wrangler deploy --dry-run` lolos dan mencantumkan binding `RATE_LIMIT`, `ASSETS`, `ACCESS_*`. |
| 2 | Tidak ada test verifikasi signature JWT; test lama hanya memeriksa keberadaan header. | Forgery tidak akan terdeteksi. | `tests/unit/accessJwt.test.js` (25 test, keypair sungguhan, mutation-checked). |
| 3 | `isPrivateIp` **lolos** untuk `https://[::1]/`, `[::ffff:127.0.0.1]`, `[fe80::1]`, `[fd00::1]`, `0.0.0.0`, `100.64/10`, `198.18/15`, `localhost.`, `*.localhost/.internal/.local`. Hostname IPv6 dari `URL.hostname` berkurung siku sehingga regex lama tidak cocok. | SSRF ke loopback/metadata via IPv6 bila host ada di allowlist. | Ditulis ulang. 25 dari 55 test baru **gagal pada kode lama**, lulus pada kode baru. |
| 4 | Body response dibaca penuh (`arrayBuffer()`) dan timer dimatikan sebelum body dibaca. | Response besar tanpa `Content-Length` menghabiskan memori; body yang menetes lambat menggantung tanpa batas. | Diganti `readBodyCapped` (baca bertahap, berhenti di 1 MB, `cancel()` stream) + timeout 30 dtk aktif selama membaca. Pada kode lama test streaming menggantung ±20 dtk. |
| 5 | Batas redirect off-by-one: 5 redirect lalu 200 ditolak 429. | Redirect sah ditolak. | Ditolak hanya bila setelah 5 hop masih 3xx. |
| 6 | Kunci rate limit diambil dari header `cf-access-authenticated-*` buatan klien dan ditambah `:hostname`. | Pengguna terautentikasi dapat mengganti header untuk bucket baru; kuota berlipat per host allowlist. | Kunci kini dari klaim JWT terverifikasi (`sub`, lalu `email`); mode `disabled` memakai IP. Test mutation-checked. |
| 7 | Batas ukuran **request** hanya dicek dari header `Content-Length`; POST chunked tanpa header langsung dibaca penuh oleh `request.json()`. JSON `null` juga membuat destructuring crash. | Body besar tak terbatas menghabiskan memori; klaim "batas ukuran request" terlalu kuat. | Body kini dibaca bertahap dengan `readBodyCapped` (maks 1 MB → 413); body kosong/`null`/bukan objek → 400. Pada kode lama: body chunked 2 MB diteruskan ke fetch dan `null` gagal (2 dari 6 test baru gagal). |

## 3. Kontrol yang diimplementasikan

Urutan pemeriksaan di handler `/api/*` (semua fail-closed):

1. Allowlist domain kosong → **503** (Live Test nonaktif secara default).
2. Autentikasi Access wajib: `ACCESS_CERTS_URL`/`ACCESS_ISSUER`/`ACCESS_AUDIENCE` kosong → **503**; token tidak ada → **401**; token tidak valid → **401**.
   Verifikasi: signature via JWKS (cache 1 jam per isolat), `issuer`, `audience`, `exp` (toleransi 30 dtk), whitelist alg `RS256/384/512, ES256/384, PS256` (menolak `none` dan HS256), `kid` wajib ada di JWKS.
   `ACCESS_AUTH_MODE="disabled"` hanya untuk dev lokal.
3. Method selain POST → **405**; `Content-Length` > 1 MB → **413** (jalur cepat); body dibaca bertahap maks 1 MB juga bila tanpa/berbohong soal `Content-Length` → **413**; JSON rusak/kosong/bukan objek → **400**.
4. SSRF: `isPrivateIp(hostname)` → **403** (IPv4 desimal/hex, IPv4-mapped, NAT64, IPv6 loopback/ULA/link-local, `0.0.0.0`, CGNAT, `localhost`/`.internal`/`.local`, trailing dot, `nip.io`/`sslip.io`). Diperiksa **sebelum** allowlist.
5. Allowlist domain; hanya HTTPS → **403**.
6. Rate limit via binding `RATE_LIMIT` (30 permintaan/60 dtk per identitas); binding tidak ada/error → **503**; terlampaui → **429**.
7. Header sensitif request (`cookie`, `authorization`, `x-api-key`, …) dibuang; header sensitif response (`set-cookie`, …) dibuang.
8. Redirect manual, maks 5 hop; **setiap hop** divalidasi ulang (HTTPS, bukan IP private, dalam allowlist).
9. Response: cek `Content-Length`, baca bertahap maks 1 MB, timeout total 30 dtk (504), body terlalu besar → **413**.

## 4. Bukti per tingkat

### A. Unit/mock — Vitest: 315 test lulus, 12 file

| File | Test | Fokus |
|---|---|---|
| `tests/unit/accessJwt.test.js` | 25 | JWT dengan keypair RS256/ES256 sungguhan: token valid; **forgery** (claim benar, key penyerang, kid sama); signature/payload dirusak; `alg:none`; HS256 alg-confusion; kid tak dikenal/tanpa kid; expired; issuer/audience salah; malformed; JWKS gagal/non-200/kosong; cache JWKS; 401/503 di level handler; kunci rate limit dari JWT. |
| `tests/unit/ssrfAndLimits.test.js` | 61 | `isPrivateIp` untuk 29 hostname yang diblokir dan 15 yang diizinkan (hasil URL parser); integrasi dengan IP literal **sengaja dimasukkan ke allowlist** sehingga yang memblokir terbukti pemeriksaan SSRF (pesan "IP private"), `fetch` tidak pernah dipanggil; redirect ke `[::1]` diblokir; stream tak berujung dihentikan di 1 MB dan di-`cancel()`; `Content-Length` 5 MB ditolak tanpa membaca body; body tepat 1 MB diterima; **request** chunked ±2 MB tanpa `Content-Length` → 413 (fetch tidak dipanggil), body kecil chunked diterima, body kosong/`null`/angka/bukan-JSON → 400; body menetes → 504; 5 redirect lalu 200 diterima; redirect tak berujung → 429. |
| `tests/unit/security.test.js` | 44 | Allowlist, CORS, filter header, kebocoran kredensial, edge case IP. |

Bukti kekuatan test (mutation check): test baru dijalankan terhadap salinan Worker sebelum perbaikan → 25/55 gagal; kunci rate limit lama → 1 test gagal; tanpa batas request → 2 test gagal; token berkey penyerang ditolak.

Coverage `worker/index.js`: statements 93,58%, branches 86,53%, functions 100% (sebagian besar cabang error langka, mis. sekitar baris 482–484 dan 514–515).

Lint: 0 error, 133 warning (tidak ada di `worker/`; sisanya kode frontend lama). Build produksi: sukses.

**Keterbatasan A:** JWKS, DNS, dan jaringan keluar di-stub. Test tidak membuktikan bahwa Cloudflare mengirim JWT yang sesuai, atau bahwa DNS target tidak mengarah ke IP private.

### B. Runtime Worker lokal — `wrangler dev` (workerd), kode terbaru

`wrangler deploy --dry-run`: lolos; binding `env.RATE_LIMIT (30 requests/60s)`, `env.ASSETS`, `ACCESS_*`.
Skenario dijalankan terhadap workerd sungguhan (`ACCESS_AUTH_MODE=disabled` + allowlist berisi IP literal):

| Permintaan | Hasil |
|---|---|
| `GET /` | 200 `text/html` (static assets dilayani; Worker hanya dijalankan untuk `/api/*`) |
| `POST` ke `https://[::1]/`, `https://127.0.0.1/`, `https://[::ffff:127.0.0.1]/`, `https://169.254.169.254/...` | **403** "IP private (SSRF protection)" |
| `POST` ke `http://example.com/` | 403 "Hanya HTTPS" |
| `POST` ke domain di luar allowlist | 403 |
| `GET /api/proxy` | 405 |

Verifikasi runtime lanjutan (kode final, tiga instance workerd dengan konfigurasi berbeda):

| Konfigurasi | Permintaan | Hasil |
|---|---|---|
| Default (tanpa var apa pun) | POST `/api/proxy` | **503** "Live Test tidak dikonfigurasi" (fail-closed) |
| Access dikonfigurasi (nilai dummy) + allowlist | tanpa token | **401** |
| | token sampah `aaa.bbb.ccc` | **401** |
| | token `alg:none` | **401** |
| | hanya header `cf-access-authenticated-user-email` palsu | **401** (header identitas tidak memberi akses) |
| Auth `disabled` (dev) + allowlist `example.com` | POST chunked ±2 MB tanpa `Content-Length` | **413** |
| | body `null` / body kosong | **400** (bukan crash) |
| | 35 request berurutan ke target yang lolos semua pemeriksaan | 30 pertama **200** (fetch keluar sungguhan ke target), lalu **429** untuk 5 sisanya |

Catatan: hasil 429 menunjukkan binding rate limit lokal (`local`) bekerja sesuai konfigurasi 30/60 dtk; ini **bukan** bukti perilaku limiter produksi (lihat C). Skenario Access memakai nilai dummy, jadi hanya membuktikan penolakan, bukan penerimaan JWT asli.

**Keterbatasan B:** binding rate limit berstatus `local` (bukan limiter Cloudflare sungguhan); redirect lintas host dan perilaku body besar dari server nyata tidak diuji end-to-end; tidak ada JWT/JWKS Cloudflare Access nyata.

### C. NOT_VERIFIED — wajib diverifikasi setelah deployment

| Item | Yang harus dibuktikan |
|---|---|
| Cloudflare Access | Aplikasi Access melindungi route Worker; nilai `ACCESS_CERTS_URL`, `ACCESS_ISSUER`, `ACCESS_AUDIENCE` (AUD tag) benar; JWT nyata diterima, tanpa/dengan JWT palsu ditolak 401. Pastikan akses langsung tanpa JWT (mis. lewat `*.workers.dev`) mendapat 401 di produksi, atau nonaktifkan `workers.dev`/`preview_urls` untuk Worker ini. |
| JWKS | Fetch ke endpoint certs sukses dari Worker produksi; perilaku saat rotasi key (cache 1 jam per isolat → token yang ditandatangani key baru bisa ditolak sampai cache kedaluwarsa; kegagalan fetch = ditolak/fail-closed). |
| Rate limit | Binding `RATE_LIMIT` aktif di akun (`namespace_id` unik). Hitungan **per lokasi Cloudflare** dan bersifat permisif/eventually consistent — **bukan pembatas global yang ketat**; hanya pembatas penyalahgunaan. |
| `run_worker_first = ["/api/*"]` | `/api/*` benar-benar masuk Worker dan aset lain dilayani statis pada deployment. |
| CORS | `CORS_ALLOWED_ORIGIN` diisi origin produksi yang benar (bukan `*`). |
| Jaringan keluar | Redirect end-to-end ke target nyata; perilaku streaming/limit 1 MB dan timeout pada runtime produksi. |
| Secret | `SECRET_ALLOWED_DOMAINS` diset via `wrangler secret put` dan hanya berisi domain tepercaya. |

## 5. Risiko residual (diketahui, belum ditutup)

1. **DNS rebinding / DNS ke IP private tidak terjamin dicegah.** Pemeriksaan SSRF bekerja pada hostname, bukan IP hasil resolusi; Workers tidak menyediakan hook resolusi DNS. Mitigasi: allowlist domain ketat berisi domain yang Anda kendalikan/percaya. Domain di allowlist yang A-record-nya diarahkan ke IP internal tetap berisiko.
2. **Rate limit bukan global ketat** (lihat C).
3. **Header kustom dari klien ikut diteruskan** ke host allowlist lain saat terjadi redirect (header sensitif standar sudah dibuang). Risiko rendah karena host harus ada di allowlist.
4. **`ACCESS_AUTH_MODE="disabled"`** mematikan autentikasi. Hanya untuk dev lokal; jangan ada di environment produksi.
5. **Cache JWKS per isolat** (lihat C).
6. Frontend `TestCenter` mengirim ke URL Worker yang diisi pengguna dan tidak memiliki URL bawaan. Pelindung sesungguhnya adalah Worker (fail-closed). Apakah browser mengirim kredensial Access ke Worker (same-origin vs lintas-origin) **belum diverifikasi**.

## 6. Pengujian UI (Playwright) — bukan bagian keamanan Worker

Hasil: **39 lulus, 9 gagal** dari 48 (3 project: chromium, mobile-chrome, tablet-chrome).
Kegagalan: 8 di `mobile-chrome` (klik terhalang `div.flex-1.p-4.overflow-y-auto` "intercepts pointer events" — tumpang tindih layout/sidebar pada viewport Pixel 5) dan 1 di `chromium` ("Loading state during parsing", menunggu teks "Memparsing").
Bukti keterkaitan: spec e2e tidak memanggil `/api/*` atau Worker. Transkrip sesi kerja ini dipindai: **0** panggilan Edit/Write ke `src/`, dan mtime terbaru `src/` (13:21) mendahului tool-edit pertama sesi (13:26). **Baseline tidak dapat dibuktikan lewat git** karena `src/` belum pernah di-commit, jadi kegagalan ini dicatat sebagai *tidak terkait Worker menurut bukti di atas*, bukan "terbukti sudah ada sebelumnya". Perlu diperbaiki terpisah sebelum rilis UI.

## 7. Syarat sebelum P0 dapat dinyatakan selesai

- [ ] Verifikasi seluruh item bagian 4.C pada deployment staging.
- [ ] Putuskan penanganan risiko residual 1 (DNS rebinding) — terima secara tertulis atau tambah kontrol.
- [ ] Perbaiki 9 kegagalan Playwright (terpisah dari keamanan).
- [ ] Baru setelah itu aktifkan Live Test (isi `SECRET_ALLOWED_DOMAINS`).
