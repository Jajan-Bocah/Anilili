# Anilili P9: Trusted HTTP & Self-Hosted Sources — Implementation Documentation

## 1. Overview & Objective
Amandemen P9 memperluas **Hybrid Remote Provider Engine** pada Anilili untuk mendukung URL konfigurasi provider HTTP publik dan self-hosted/LAN pribadi (misalnya `http://192.168.1.10:8080/provider.json`, `http://nas.local:8080/provider.json`) tanpa mengorbankan atau mengubah fungsionalitas P0–P8 yang telah terkunci.

HTTPS tetap menjadi default absolut yang aman. Setiap koneksi HTTP atau akses ke IP/host jaringan lokal membutuhkan persetujuan eksplisit, terperinci, dan persisten dari pengguna (*explicit per-source consent*).

---

## 2. Arsitektur & Model Keamanan Trust

### 2.1 Struktur Model Data: `RemoteSourceTrust`
Terletak di `com.anilili.data.provider.remote.config.RemoteSourceTrust.kt`:
```kotlin
@Serializable
data class RemoteSourceTrust(
    val configUrl: String,
    val configOrigin: String,
    val allowHttpConfig: Boolean = false,
    val allowPrivateIpConfig: Boolean = false,
    val trustedEndpointOrigins: Set<String> = emptySet(),
    val allowHttpEndpoints: Boolean = false,
    val allowPrivateIpEndpoints: Boolean = false,
    val isLocalNetwork: Boolean = false,
    val trustedAt: Long = System.currentTimeMillis(),
)
```

### 2.2 Granular Privilege Separation
Pemisahan hak akses diterapkan secara ketat antara dua ranah operasi:
1. **Config Fetching Permissions** (`allowHttpConfig`, `allowPrivateIpConfig`): Hanya mengizinkan pengunduhan dan pembaruan file konfigurasi JSON metadata provider dari origin yang disetujui.
2. **Runtime Endpoint Permissions** (`trustedEndpointOrigins`, `allowHttpEndpoints`, `allowPrivateIpEndpoints`): Mengontrol apakah alur kerja provider (*workflow engine*) diizinkan melakukan request HTTP atau menjangkau host jaringan lokal pada saat mengeksekusi pencarian (*search*), resolusi stream (*stream resolution*), atau pemanggilan API.

### 2.3 Origin Scoping Berbasis `scheme + host + port`
Izin trust terikat secara presisi pada origin target lengkap (`http://192.168.1.10:8080`).
- Memberikan izin pada port `8080` **tidak** memberikan akses ke port `80`, `443`, `22` (SSH), atau port internal lainnya pada IP yang sama. Hal ini memitigasi serangan internal port scanning / lateral movement oleh provider jahat.

### 2.4 Loopback Immunity Permanen
Alamat loopback (`127.0.0.1`, `localhost`, `::1`, `0.0.0.0`) **selalu ditolak secara permanen** pada semua lapisan (validasi konfigurasi, request fetcher, dan eksekusi request policy), bahkan jika pengguna mencoba memberikan izin. Ini melindungi service lokal pada perangkat Android.

### 2.5 Mitigasi SSRF & DNS Rebinding
- `RemoteSourceTrust` dan `RemoteRequestPolicy` mengklasifikasikan dan memvalidasi alamat IP privat (RFC 1918: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Link-local (`169.254.0.0/16`), serta domain lokal (`.local`, `.lan`, `.home`, `.internal`).
- Validasi dilakukan sebelum request dikirim dan setelah setiap redirect.

### 2.6 Redirect Security
- **Downgrade Redirect Blocked**: Redirect dari HTTPS ke HTTP ditolak secara otomatis untuk mencegah serangan sniffing/MITM.
- **Cross-Origin Redirect SSRF Protection**: Redirect cross-origin ke alamat IP privat atau HTTP yang tidak terdaftar dalam allowlist trust grant akan diblokir seketika.

---

## 3. Kebijakan Jaringan Platform Android (Cleartext Traffic)

### 3.1 Keterbatasan Android Platform & Solusi Soliter
Secara arsitektural di platform Android, konfigurasi `network_security_config.xml` bersifat statis pada waktu kompilasi dan tidak dapat diubah secara dinamis untuk domain atau IP arbitrer yang dimasukkan oleh pengguna saat aplikasi berjalan.

Untuk mendukung sumber self-hosted LAN / HTTP di build rilis tanpa merusak keamanan aplikasi:
1. `app/build.gradle.kts` mengonfigurasi `manifestPlaceholders["usesCleartextTraffic"] = "true"` untuk kedua build type (`debug` dan `release`).
2. Sockets cleartext diizinkan di tingkat OS Android, namun **seluruh lalu lintas HTTP dikunci dan diawasi secara ketat di tingkat aplikasi (application-level allowlisting)** melalui:
   - `RemoteRequestPolicy`
   - `RemoteConfigFetcher`
   - `RemoteProviderConfigValidator`
3. Jaringan umum Anilili (AniList, SIMKL, Mal, TMDB, OpenSubtitles, CDN streaming) tetap menggunakan HTTPS murni dengan client terisolasi.

---

## 4. UI Pengaturan (Settings UI)

Terletak di `app/src/main/java/com/anilili/ui/settings/SettingsScreen.kt` (`CustomSourcesDialog`):
- **Live Scheme & Host Analysis**: Saat pengguna mengetik URL sumber custom, sistem mendeteksi apakah URL menggunakan HTTP atau mengarah ke IP privat/LAN.
- **Insecure / Local Warning Banner**: Menampilkan peringatan visual kontras tinggi jika koneksi tidak terenkripsi (HTTP) atau berada di jaringan lokal.
- **Scope Breakdown**: Menampilkan target origin (`scheme://host:port`) dan jenis izin yang diminta.
- **Explicit Consent Checkbox**: Tombol "Fetch & Install" dinonaktifkan sampai pengguna secara sadar mencentang persetujuan: *"I trust this source origin and understand that cleartext HTTP connections can be intercepted or modified."*
- **Preview Step**: Menampilkan endpoint runtime yang diminta oleh provider, termasuk peringatan jika ada endpoint HTTP atau LAN tambahan yang belum disetujui.
- **Active Trust Badges & Revocation**: Daftar sumber yang terinstal menampilkan badge `"HTTP"` atau `"LAN/Self-Hosted"`, beserta opsi *"Revoke trust & Remove source"* yang menghapus konfigurasi dan mencabut seluruh izin trust secara atomik.

---

## 5. Konfigurasi Lifecycle (P0–P9 Integration)

| Tahap Lifecycle | Implementasi di P9 |
|---|---|
| **Install** | Meminta izin trust spesifik melalui UI, menyimpan `source-trust.json` bersama `remote-providers.json`. |
| **Validation** | Menggunakan `RemoteProviderConfigValidator` dengan parameter `trust` aktif; jika trust tidak sesuai origin, validasi gagal. |
| **Preview** | `RemoteConfigFetcher.previewConfig` membaca metadata dan mengekstraksi endpoint runtime tanpa menginstal secara permanen. |
| **Test** | Menjalankan validasi request policy dengan trust aktif sebelum provider diregistrasikan ke registry engine. |
| **Manual Update** | Mengambil versi terbaru menggunakan `source-trust.json` yang tersimpan, mempertahankan ETag dan Last-Modified. |
| **Automatic Update** | Background worker memeriksa update dengan tetap mematuhi izin origin trust yang tersimpan. |
| **Last-Known-Good Cache** | `RemoteProviderConfigStore` menyimpan `last-known-good-trust.json` bersama konfigurasi yang valid. |
| **Rollback** | Jika update gagal atau korup, store memulihkan konfigurasi dan trust model dari cache terakhir yang berfungsi. |
| **Enable/Disable** | Menonaktifkan provider tanpa menghapus izin trust yang sudah diberikan. |
| **Remove / Revoke** | Menghapus konfigurasi, cache rollback, dan mencabut trust grant secara permanen. |

---

## 6. Verifikasi & Pengujian

### 6.1 Unit Test Suite (`RemoteProviderHttpTrustTest.kt`)
Mencakup 9 skenario pengujian komprehensif:
1. `HTTPS regression - public HTTPS works without any trust grant`: Memastikan HTTPS standar P0–P8 tetap berfungsi normal tanpa konfigurasi trust.
2. `Public HTTP - rejected by default when trust is absent`: Memastikan HTTP publik ditolak jika trust tidak ada.
3. `Public HTTP - accepted when explicit trust is granted`: Memastikan HTTP publik diizinkan setelah diberikan persetujuan eksplisit.
4. `Local LAN IP and custom port - rejected by default, accepted with LAN trust`: Memastikan IP LAN (`192.168.1.10:8080`) ditolak tanpa trust, diizinkan dengan trust, dan port lain (`:22`, `:80`) tetap diblokir.
5. `Local mDNS and dot-lan hostnames`: Memastikan domain `.local` dan `.lan` diakui sebagai jaringan lokal dan memerlukan izin LAN.
6. `Loopback protection`: Memastikan `127.0.0.1` dan `localhost` diblokir permanen bahkan jika pengguna mencoba memberi trust.
7. `Redirect security - protocol downgrade from HTTPS to HTTP is blocked`: Memastikan pencegahan downgrade HTTPS -> HTTP.
8. `Redirect security - cross-origin redirect to unapproved private IP or HTTP is blocked`: Memastikan pencegahan redirect SSRF ke IP privat yang tidak disetujui.
9. `Separate permissions - config trust alone does not grant runtime endpoint HTTP access`: Memastikan pemisahan hak izin config vs runtime endpoint.

### 6.2 Hasil Eksekusi Test Suite
```text
Task :app:testMobileDebugUnitTest
Package com.anilili.data.provider.remote:
- RemoteProviderEngineTest: 10 tests, 0 failures (100% success)
- RemoteProviderHttpTrustTest: 9 tests, 0 failures (100% success)
Total: 19 tests executed, 0 failures. BUILD SUCCESSFUL.
```

---

## 7. Batasan Keamanan & Risiko yang Diketahui (*Known Risks*)

1. **Jaringan Wi-Fi Publik / Unencrypted HTTP**:
   - Jika pengguna menyetujui provider HTTP pada jaringan publik yang tidak terpercaya (misalnya kafe / hotspot terbuka), penyerang man-in-the-middle (MITM) dapat memanipulasi respons JSON provider.
   - *Mitigasi*: Peringatan eksplisit pada UI dan persetujuan berbasis checkbox sebelum instalasi.
2. **Perubahan DHCP / IP Reallocation**:
   - Jika IP server self-hosted di LAN berubah karena DHCP lease, konfigurasi lama tidak akan dapat diakses dan tidak akan otomatis mengizinkan IP baru tanpa konfirmasi pengguna ulang.
   - *Mitigasi*: Origin scoping yang ketat menjamin perangkat lain yang mendapatkan IP lama tidak sembarangan menerima request berbahaya.
3. **Penyimpanan Kredensial**:
   - Tidak ada kredensial sensitif atau token akun Anilili (AniList/SIMKL) yang dibagikan atau dikirimkan ke endpoint remote provider.
