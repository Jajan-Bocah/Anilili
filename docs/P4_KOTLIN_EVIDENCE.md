# P4 - Bukti Kontrak Kotlin (Anilili) vs output Generator

Dibuat read-only terhadap repo Android. Status: BUKTI KONTRAK, bukan ANILILI_READY (playback di perangkat Android TIDAK diuji).

## 1. SHA dan status repo Android

| Item | Nilai |
|---|---|
| Repo | `D:\Mizan\Project\Anilili` |
| Branch | `feature/hybrid-remote-provider-engine` |
| HEAD | `8c6230665699978963aa3e77dd791ceaced94219` (disingkat `8c62306`) |
| `git status --short` SEBELUM | `?? docs/PONYTAIL_AUDIT.md` |
| `git status --short` SESUDAH | `?? docs/PONYTAIL_AUDIT.md` (identik; diperiksa ulang di akhir pekerjaan) |
| Cara baca source | `git show 8c62306:<path>` / Read. Eksekusi Gradle hanya di salinan `git archive 8c62306` di `D:\Mizan\_p4_android_copy` (di luar kedua repo) |

Semua path Kotlin di bawah relatif ke `app/src/main/java/com/anilili/data/provider/remote/`. Nomor baris dari blob `8c62306`.

Provenans fixture: `tests/fixtures/real/MANIFEST.json` mencatat `anililiCommit 8c62306`; `app/src/main/assets/remote-provider-configs/anichin.json` identik dengan `tests/fixtures/real/provider-configs/anichin.json` (setelah buang CR). Fixture generator = versi tersanitasi (script dibuang); fixture Android (`app/src/test/resources/fixtures/anichin/{search,series,watch}.htm`) = aslinya. Eksekusi Kotlin di bawah memakai fixture ASLI Android.

## 2. Tabel kontrak JS (generator) vs Kotlin (runtime)

| Aspek | Kotlin (bukti) | Generator JS | Status |
|---|---|---|---|
| Decode config | `config/RemoteProviderManager.kt:152-181` (`fetchConfig`) memakai `Json.decodeFromString` DEFAULT (strict: key/enum tak dikenal atau key nullable tanpa default yang hilang = gagal). `previewConfig` lenient. | `engineContract.js` (whitelist key, catatan strict) | OK: output Anichin lolos decode strict (dieksekusi, bagian 6b) |
| Validator | `config/RemoteProviderConfigValidator.kt:28` hanya cek schemaVersion, metadata, providers, id duplikat/sistem, security. TIDAK memvalidasi workflows/steps. | `validateProviderJson` | Dieksekusi: `isValid=true errors=[] warnings=[]`. Validasi workflow hanya ada di sisi generator. |
| Op yang didukung | `runtime/RemoteProviderRuntimeFactory.kt:66-77`: http, parse_html, select, parse_json, regex, map, normalize, assert, filter. Op lain `throw`; catch per workflow (`:44-56`) lalu seluruh workflow dibuang diam-diam. | `SUPPORTED_OPS` | Sama |
| Key step | `WorkflowStepConfig(op, id, params, saveAs)`; `id`, `saveAs` nullable TANPA default (key harus ada). | generator selalu mengisi | OK |
| `params` | `Map<String,String>`; object/array di-string-kan (`WorkflowParamsSerializer`) lalu didecode ulang. `query` = JSON string objek. | `query: {"s":"{{query}}"}` | OK (dieksekusi: request `https://anichin.moe/?s=quanzhi`) |
| Mode select | `SelectMode` FIRST/ALL/TEXT/HTML/ATTRIBUTE (`engine/workflow/WorkflowStep.kt`), `valueOf(uppercase())`; ALL = `List<String>`: nilai `attribute` bila ada, selain itu `.text()`. | `SELECT_MODES`, mode `ALL` + `attribute` | Sama |
| Template | Regex `\{\{(\s*[a-zA-Z_][a-zA-Z0-9_.-]*\s*)\}\}` (`engine/workflow/TemplateEvaluator.kt`); variabel tak terselesaikan menjadi string kosong; tanpa filter/pipe. Hanya http.url/headers/query/body dan url_resolve.baseUrl dievaluasi; selector/input literal. | `TEMPLATE_REGEX` | Sama |
| Query param | `URLEncoder`; query yang sudah ada di URL DIBUANG bila `query` diisi (`WorkflowExecutor`). | generator tidak menaruh query di URL | OK |
| Output mapping | `extractOutput`: `mapping.mapValues { variables[varName] ?: getString(varName) }`; `OutputType.valueOf(type.uppercase())`, default RAW. | `SUPPORTED_OUTPUT_TYPES` | Sama |
| SEARCH | `runtime/WorkflowRemoteProviderRuntime.kt:216-287` (`parseSearchResults`): list paralel `titles`/`urls`/`images` menjadi `title`/`slug`/`image` (`:235-237`); `slugOrUrl = urls[i]`. | `runtimeParsers.js` | OK (dieksekusi: 2 hasil) |
| EPISODES | `:289-384` (`parseEpisodesResult`): `episodeUrls`/`episode_urls`/`urls`, `episodeNumbers`/..., `episodeTitles`/...; nomor `replace(Regex("[^0-9.]"),"").toDoubleOrNull() ?: return@mapNotNull null` (`:334`, `:352`); `sortedBy{number}` (`:370`); `pipeId = "remote/${entry.id}/$anilistId/sub/$url"` (`:340`, `:358`). | `parseFloat` | Beda kecil: JS `parseFloat("1.2.3")=1.2`, Kotlin `toDoubleOrNull`=null (tidak muncul di Anichin) |
| SOURCES | `:386-495` (`parseSourcesResult`): `streams` + opsional `servers`; item String: `isIframe = isMirrorDropdown \|\| url.contains("embed")` (`:466`), `.m3u8` = hls, selain itu `mp4`. Tanpa validasi URL, tanpa decode base64, tanpa resolve URL relatif. `isMirrorDropdown` true hanya bila ada `streams` DAN `servers` (`:393`). | `runtimeParsers.js` | OK untuk `streams`; generator tidak mengeluarkan `servers` |
| SERVERS | `:496-` (`parseServers`): hanya item Map (name/title/server); String dibuang. | - | Server hasil `select ... ALL` (String) tidak terpakai |
| Selector | Jsoup 1.18.3 | jsdom | Hasil sama pada fixture Anichin (bagian 6, dieksekusi di Jsoup nyata): `div.eplister > ul > li > a`=168, `.eplister li a`=168, search=2, iframe=1, option=12 |
| Security default | tanpa blok `security` memakai `SecurityConfig()` (`engine/security/RemoteRequestPolicy.kt:173`): `maxResponseBytes 1 MiB`, allowedDomains kosong (tanpa batas), request/run 20, redirect 5. Config resmi: 2 MiB / 12 / 3. | generator tidak mengeluarkan blok `security` | RISIKO (R3) |
| `supportsSearch` | dipetakan ke `ResolvedCapabilities` (`runtime/RemoteProviderRuntime.kt:119`); tidak ada pemakai lain di main; registry membaca `requiresWebView` (`registry/UnifiedProviderRegistry.kt:188,197,342`). Generator: `supportsSearch:false`, `requiresWebView:false`; resmi `true`/`true`. | | RISIKO kecil (R5) |

## 3. 168 anchor menjadi 166 episode

Selector generator `div.eplister > ul > li > a` = 168 anchor (dieksekusi di Jsoup 1.18.3 pada `series.htm` ASLI Android: 168; selector resmi `.eplister li a` = 168 juga; jsdom di generator = 168). `nums=168 titles=168`.

Dua anchor yang gugur (indeks 0-based pada daftar 168; ditemukan dengan menjalankan logika `parseEpisodesResult` di Jsoup):

1. Indeks 11, `li data-index="11"`:
   `<a href="/btth-hiatus-sampai-6-september-2026/"><div class="epl-num">HIATUS!</div><div class="epl-title">HIATUS!</div><div class="epl-sub"><span class="status Sub">Sub</span></div><div class="epl-date">July 19, 2026</div></a>`
2. Indeks 119, `li data-index="119"`:
   `<a href="/btth-libur-dua-minggu-digantikan-dragon-prince-yuanzun/"><div class="epl-num">LIBUR!</div><div class="epl-title">BTTH LIBUR DUA MINGGU, DIGANTIKAN DRAGON PRINCE YUANZUN</div><div class="epl-sub">...</div><div class="epl-date">June 15, 2024</div></a>`

Penyebab: ini pengumuman (hiatus/libur), bukan episode. Teks `.epl-num` ("HIATUS!", "LIBUR!") tidak mengandung digit, jadi `replace(Regex("[^0-9.]"), "")` menghasilkan string kosong, `toDoubleOrNull()` = `null`, dan `?: return@mapNotNull null` membuang item (`WorkflowRemoteProviderRuntime.kt:334`). Kedua href valid (relatif, bukan kosong, bukan `#`, tidak duplikat), jadi yang menjatuhkan adalah NOMOR, bukan URL.

Kotlin berperilaku sama dengan JS: dieksekusi (bagian 6b) `EPISODES n=166` dari runtime Kotlin nyata; di Jsoup `kept=166 dropped=2`. Ini perilaku engine, bukan bug generator. Efek ke pengguna: pengumuman tidak tampil sebagai episode (kemungkinan memang diinginkan).

### Risiko terkait pada data yang sama (R6)

4 anchor terakhir (indeks 164-167) bernomor `"04 [56]"`, `"03 [55]"`, `"02 [54]"`, `"01 [53]"`. `replace(/[^0-9.]/g,'')` membuang spasi dan kurung sehingga menjadi `0456`, `0355`, `0254`, `0153` = 456, 355, 254, 153. Dieksekusi di Kotlin nyata:
- `EPISODES max=456.0`; 3 terakhir = `254.0, 355.0, 456.0`.
- `dupNumbers=[(153.0, [.../btth-season-5-episode-153-subtitle-indonesia/, .../oyen-pertempuran-akhir-sekte-misty-cloud-episode-01-53-sub-indo/])]`: nomor 153 ganda.
- Cache slug episode berkunci nomor (`RemoteSlugStore`; `putEpisodeSlug` di `:93`/`:96`), jadi entri terakhir menimpa episode 153 yang asli.

## 4. Slug, identifier episode, pipeId

Alur dan bukti:

1. SEARCH: `slugOrUrl` = `urls[i]` apa adanya (`:236`). Anichin: `/quanzhi-fashi-season-7/` (relatif, berawalan `/`). Dieksekusi: `all=[(Quanzhi Fashi Season 7, /quanzhi-fashi-season-7/), (Quanzhi Fashi Season 6, /quanzhi-fashi-season-6/)]`; `anilistId=0` pada hasil search.
2. EPISODES: `animeSlug` dari `RemoteSlugStore` (LRU 512 slug anime, 2048 slug episode; kunci `provider:anilistId[:episodeNumber]`) atau dari pencocokan judul `AnimeTitleMatcher` (ambang 0.75, `:55`). Variabel yang disuntik: `animeSlug`/`animeUrl`/`slug`, lalu `url = entry.endpoint.trimEnd('/') + animeSlug` (`:78`). Dieksekusi: request `https://anichin.moe/?s=Quanzhi+Fashi+Season+7` lalu `https://anichin.moe/quanzhi-fashi-season-7/`.
3. Relatif vs absolut: HANYA slug relatif berawalan `/` yang benar. Slug absolut menghasilkan `https://anichin.moehttps://...` (konkatenasi `:78`/`:128`). Tidak ada normalisasi absolut/relatif di Kotlin. Anichin memberi href relatif pada semua 168 anchor (0 absolut), jadi cocok; situs dengan href absolut akan rusak (R3b).
4. pipeId: bukan ID kebijakan/config. Ini string `remote/{providerId}/{anilistId}/sub/{url}` (`:340`), `{url}` = href episode mentah. Karena href berawalan `/`, hasilnya memuat `//`: `remote/anichin/123/sub//oyen-season-5-episode-57-subtitle-indonesia/` (dieksekusi). `data/remote/PipeEnginePolicy.kt` TIDAK terkait (hanya `canHostPipeEngine(isTv,isLowRamDevice)` untuk mesin pipe Miruro).
5. Cache episode: `slugStore.putEpisodeSlug(entry.id, anilistId, ep.number, ep.pipeId)` (`:93`,`:96`) menyimpan pipeId, bukan URL episode.
6. SOURCES jalur `getSources` (`:106-143`): `episodeUrl`/`episodeSlug` = pipeId dari cache; `url = endpoint + (animeSlug jika ada, selain itu episodeUrl)` (`:128`). Setelah episodes dijalankan, `{{url}}` = HALAMAN SERI. Dieksekusi: request `https://anichin.moe/quanzhi-fashi-season-7/` (halaman seri), `streams=null` karena `div#pembed > iframe` tidak ada di halaman seri.
7. SOURCES jalur produksi `getSourcesForPipeId` (`:145-188`; dipanggil dari `data/MiruroRepository.kt:2445-2467`): memecah pipeId dengan `/` dan memakai `parts[4]` (`:147-148`). Pada `sub//x/`, `parts[4]` = `""`, jadi `epNumber=1.0` dan `effectiveEpisodeUrl=""` (`:149-157`); variabel `url` TIDAK diset sama sekali. Workflow `http GET {{url}}` mendapat URL kosong (variabel tak terselesaikan menjadi string kosong di TemplateEvaluator); dari pembacaan source, `RemoteRequestPolicy.validateRequest` menolak dengan "Invalid URL format" (`engine/security/RemoteRequestPolicy.kt:37`) - pesan ini TIDAK teramati di run, karena tes hanya mencetak hasil. Teramati: `GETSOURCESFORPIPEID ... streams=null`, `REQ after getSourcesForPipeId=[]` (tidak ada request HTTP sama sekali).
8. Eksperimen pembanding (dieksekusi): bila `animeSlug` di-seed dengan path episode `/btth-season-5-episode-153-subtitle-indonesia/`, `getSources` meminta `https://anichin.moe/btth-season-5-episode-153-subtitle-indonesia/` (halaman watch) dan menghasilkan `streams=[(https://ok.ru/videoembed/16438442330802, embed, null)]`. Artinya format output workflow SOURCES diterima parser; yang rusak adalah cara runtime memasok `{{url}}`.

Config resmi `anichin.json` juga memakai `{{url}}` di workflow sources, jadi cacat jalur produksi ini ada di Android, bukan khas generator.

## 5. Sources: iframe di watch.htm

`watch.htm` punya 1 iframe di `div#pembed.player-embed`:

`<iframe src="https://ok.ru/videoembed/16438442330802" width="100%" height="100%" frameborder="0" scrolling="auto" style="position:absolute;" allowfullscreen=""></iframe>`

- Absolut `https`. Kotlin tidak mewajibkan http(s) absolut di `parseSourcesResult` (tidak ada validasi/resolve URL), tetapi URL relatif atau `//` tidak akan bisa dipakai pemutar.
- Dieksekusi: `type=embed` karena `"videoembed"` mengandung `"embed"` (`:466`). URL tanpa "embed" dan tanpa `.m3u8` akan bertipe `mp4` (salah untuk iframe). `quality=null`.
- Tidak diklaim dapat diputar. Hanya format/tipe yang dikonfirmasi; pemutaran embed di UI Android tidak diuji.
- `base64_decode`: ADA di op Kotlin (`Base64DecodeOp`, dieksekusi di `engine/workflow/WorkflowExecutor.kt:361` lalu `Base64Codec.decodeToString`), tetapi hanya lewat `normalize` pada SATU string (`resolveInputString`); tidak ada decode per item list. `select.mirror option` (12 option; option 0 "Pilih Server Video" bernilai kosong; option 1-11 = base64 HTML iframe) tidak bisa didecode per item di workflow, jadi mirror tetap blob base64. Generator benar memilih iframe tunggal `div#pembed > iframe` untuk `streams` dan tidak memetakan `servers`.

## 6. Eksekusi Kotlin nyata (BERHASIL, terbatas JVM)

Lingkungan: JDK 17 Temurin, Gradle 8.13 offline, SDK `D:/BGSOFT/Android/SDK`. Disk `C:` penuh (0 byte), sehingga salinan ada di `D:\Mizan\_p4_android_copy` dan `GRADLE_USER_HOME=D:\Mizan\_p4_gradlehome` (cache modul + distribusi Gradle disalin dari `~/.gradle` yang tidak diubah; `gradle.properties` pengguna tidak disalin). Salinan = `git archive 8c62306 | tar -x`. `local.properties` ditulis HANYA di salinan. Salinan dan `_p4_gradlehome` sudah DIHAPUS setelah run (dapat dibangun ulang dengan perintah di bawah); yang tersisa di luar kedua repo hanya `D:\Mizan\_p4_jsoup` (`gen.json`, `gen.mjs`, `J.java`). Waktu: konfigurasi + kompilasi pertama sekitar 6 menit, run berikutnya <1 menit.

Perintah:
```
cd D:\Mizan\_p4_android_copy
set GRADLE_USER_HOME=D:\Mizan\_p4_gradlehome
gradlew --offline --no-daemon :app:testMobileDebugUnitTest --tests "com.anilili.data.provider.remote.ProviderFixtureOfflineTest"
gradlew --offline --no-daemon --no-configuration-cache :app:testMobileDebugUnitTest --tests "com.anilili.data.provider.remote.GeneratorEvidenceTest"
```
Catatan: task `:app:testDebugUnitTest` ambigu (`testMobileDebugUnitTest` / `testTvDebugUnitTest`). Run pertama berakhir "BUILD FAILED" hanya karena langkah configuration-cache (`:app:syncParityCorpus` tidak bisa diserialisasi) setelah tes selesai; XML hasil: `tests=5 failures=0 errors=0`.

### 6a. Tes yang sudah ada di Android: `ProviderFixtureOfflineTest`
5 tes lulus. Memakai selector config RESMI (`.eplister li a`, `select.mirror option`) pada fixture asli; mencetak `Anichin series: urls=168 numbers=168 titles=168`. Hanya Jsoup murni; tidak menjalankan workflow engine atau parser output. Tes lain yang ada (tidak dijalankan): `RemoteProviderIntegrationTest` (MockWebServer; mock mengabaikan path sehingga bug `{{url}}` tersembunyi), `ResponseSizeLimitTest`, `SecurityRegressionTest`, `RemoteSlugStoreTest`, `AnimeTitleMatcherTest`. Tidak ada `WorkflowExecutorTest` atau `RemoteProviderConfigValidatorTest` pada `8c62306`.

### 6b. Tes tambahan di SALINAN (bukan di repo Android): `GeneratorEvidenceTest`
Memuat `provider.json` hasil generator (pipeline `tests/unit/realGenerator.test.js`, endpoint `https://anichin.moe`, jsdom sebagai `DOMParser`), lalu: decode strict, validator, `RemoteProviderRuntimeFactory.createRuntime`, `search`/`getEpisodes`/`getSources`/`getSourcesForPipeId`, dengan `OkHttpClient` + interceptor yang menyajikan fixture ASLI Android (tanpa jaringan). Sumber tes ada di Lampiran A. Keluaran nyata (`EVID`):

```
STRICT_DECODE_OK providers=1 workflows=[search, episodes, sources]
VALIDATOR isValid=true errors=[] warnings=[]
RUNTIME_CREATED=true
SEARCH n=2 first=SearchResult(anilistId=0, title=Quanzhi Fashi Season 7, image=/wp-content/uploads/2026/05/Quanzhi-Fashi-Season-7-Subtitle-Indonesia.jpg, year=null, format=null, slugOrUrl=/quanzhi-fashi-season-7/)
REQ after search=[https://anichin.moe/?s=quanzhi]
EPISODES n=166 first3=[(57.0, remote/anichin/123/sub//oyen-season-5-episode-57-subtitle-indonesia/), (58.0, ...), (59.0, ...)]
EPISODES last3=[(254.0, .../oyen-pertempuran-akhir-sekte-misty-cloud-episode-02-54-sub-indo/), (355.0, ...), (456.0, ...)]
EPISODES dupNumbers=[(153.0, [.../btth-season-5-episode-153-subtitle-indonesia/, .../oyen-pertempuran-akhir-sekte-misty-cloud-episode-01-53-sub-indo/])]
EPISODES max=456.0
REQ after episodes=[https://anichin.moe/?s=Quanzhi+Fashi+Season+7, https://anichin.moe/quanzhi-fashi-season-7/]
GETSOURCES streams=null
REQ after getSources=[https://anichin.moe/quanzhi-fashi-season-7/]
GETSOURCESFORPIPEID pipe=remote/anichin/123/sub//oyen-season-5-episode-57-subtitle-indonesia/ streams=null
REQ after getSourcesForPipeId=[]
SEEDED getSources streams=[(https://ok.ru/videoembed/16438442330802, embed, null)]
REQ seeded=[https://anichin.moe/btth-season-5-episode-153-subtitle-indonesia/]
```

Jsoup 1.18.3 langsung (Java single-file, `java -cp jsoup-1.18.3.jar J.java`) menegaskan: 168/168 anchor, `kept=166 dropped=2`, search 2, iframe 1, option 12; sama dengan jsdom.

Yang dieksekusi: engine Kotlin + Jsoup + OkHttp pada JVM, HTTP disimulasikan fixture. TIDAK diuji: perangkat/emulator Android, jaringan nyata, DNS-pinning/TLS/redirect, UI, pemutaran, `MiruroRepository` end-to-end, dan dugaan diskriminator polimorfik (R7).

## 7. Ketidakcocokan generator (BLOCKER / RISIKO)

BLOCKER = alur pengguna nyata (episode ke sumber) tidak bisa jalan; RISIKO = bisa salah pada kondisi lain atau tidak terukur.

| ID | Tingkat | Temuan | Bukti | Tindakan generator |
|---|---|---|---|---|
| B1 | BLOCKER | `sources` memakai `{{url}}`; pada jalur produksi `getSourcesForPipeId` variabel `url` tidak pernah diset. Selain itu pipeId dari generator (`sub//slug/`) membuat `parts[4]=""`, sehingga `episodeUrl`/`episodeSlug` juga kosong. Tidak ada request HTTP yang dikirim (teramati). | `WorkflowRemoteProviderRuntime.kt:145-188`; eksekusi: `REQ=[]`, `streams=null` | Dengan config seperti sekarang sources tidak jalan; status tetap NEEDS_REVIEW/BLOCKED. Ada mitigasi kandidat dari sisi generator (bagian 8, NOT_TESTED). Config resmi punya cacat yang sama. |
| B1b | KONTRAK | Asimetri slug: `urls` hasil SEARCH harus berawalan `/` (`getEpisodes` menyambung `endpoint + slug`, `:78`), sedangkan URL episode TIDAK boleh berawalan `/` (pipeId di-split dengan `/` dan memakai `parts[4]`, `:147-148`). Satu href mentah tidak bisa memenuhi keduanya. | `:78`, `:147-157`, `:340` | Lihat bagian 8. |
| B2 | BLOCKER | Pada `getSources`, `{{url}}` = halaman seri (animeSlug diprioritaskan), bukan halaman episode; `episodeUrl` di sana = pipeId utuh. Berpengaruh kecil karena `getSourcesForPipeId` adalah satu-satunya pemanggil produksi (`MiruroRepository.kt:2455`). | `:128`; eksekusi: request ke `/quanzhi-fashi-season-7/`, `streams=null` | Catat di laporan validasi; tidak bisa diperbaiki dari generator. |
| R3 | RISIKO | Tanpa blok `security`: batas respons 1 MiB, tanpa allowlist domain. Halaman fixture 206-268 KB, tetapi halaman live bisa >1 MiB. Resmi: 2 MiB, domain `anichin.moe`/`ok.ru`/`mega.nz`. | `RemoteRequestPolicy.kt:173`; config resmi | Keluarkan blok `security` (maxResponseBytes, allowedDomains dari endpoint + host iframe). |
| R3b | RISIKO | `{{url}} = endpoint + slug` hanya benar untuk href relatif berawalan `/`; href absolut menjadi `https://host https://...`. | `:78`, `:128` | Peringatkan/normalisasi saat href sampel absolut (Anichin: relatif, aman). |
| R4 | RISIKO | Output `servers` tidak dihasilkan; `parseServers` hanya menerima Map, daftar server (String) tidak terpakai. `streams` = satu iframe saja. | `:496-`; `watch.htm` 12 option base64 | Biarkan `servers` NEEDS_REVIEW (sudah). |
| R5 | RISIKO | `supportsSearch:false`/`requiresWebView:false` berbeda dari config resmi (`true`/`true`); dampak pada pemutaran embed tidak diuji. | `UnifiedProviderRegistry.kt:188,197,342` | Verifikasi di perangkat; jangan klaim playable. |
| R6 | RISIKO | Nomor episode campuran ("04 [56]") digabung digitnya (456, 355, 254, 153); 153 bentrok dengan episode asli; entri akhir menimpa cache slug. Pengumuman (HIATUS!/LIBUR!) dibuang. | eksekusi: `max=456.0`, `dupNumbers 153.0`; `:334` | Tambahkan peringatan nomor ganda/lonjakan, nomor tanpa digit, dan selisih jumlah anchor vs episode (168 vs 166). |
| R7 | RISIKO (DUGAAN, NOT_TESTED) | `NormalizeOperation`/`AssertCondition`/`FilterPredicate` adalah sealed interface dengan properti `type` yang sama dengan diskriminator polimorfik; decode `{"type":"trim"}` diduga gagal sehingga seluruh workflow dibuang. Config resmi tidak memakai normalize/assert/filter, jadi tidak pernah dieksekusi. | `engine/workflow/WorkflowStep.kt:91-112`; `SUSPECTED_BLOCKERS` di `engineContract.js` | Jangan keluarkan op normalize/assert/filter tanpa bukti Android. |
| R8 | RISIKO | Workflow yang gagal diparse dibuang diam-diam; validator Kotlin tidak melihat workflows. | `RemoteProviderRuntimeFactory.kt:44-56`; `RemoteProviderConfigValidator.kt:28` | Validasi workflow di sisi generator (sudah). |
| R9 | RISIKO | `fetchConfig` strict vs `previewConfig` lenient: preview bisa sukses sementara instal gagal. Output Anichin lolos strict. | `RemoteProviderManager.kt:152-181` | Pertahankan whitelist key; ulangi uji strict-decode seperti 6b. |
| R10 | RISIKO | Selector Jsoup vs jsdom hanya dibandingkan pada 3 fixture Anichin; sintaks lain (pseudo-class, atribut khusus) bisa berbeda. | bagian 6 | Batasi selector ke subset CSS yang aman untuk keduanya. |

Tidak ada temuan yang menyatakan ANILILI_READY. Yang boleh diklaim: JSON diterima decoder strict dan validator Kotlin; search (2) dan episodes (166) dihasilkan oleh engine Kotlin di JVM dengan fixture; sources TIDAK bisa dijalankan pada jalur produksi (B1/B2).

## 8. Mitigasi kandidat untuk B1/B1b (NOT_TESTED, hanya dari pembacaan source)

Ide: buat `episodeUrls` berupa slug TANPA `/` di depan dan di belakang, lalu workflow sources memakai `{{episodeSlug}}`.

- Mekanisme: `RegexStep` mode ALL memakai `Regex(pattern).findAll(input).mapNotNull { groupValues.getOrNull(group) }` (`engine/workflow/WorkflowExecutor.kt:315`, `:594-596`), sehingga `group` = 1 mengembalikan isi grup tangkap. Inputnya satu string (`resolveInputString` pada `HttpResponse` mengembalikan `body`, `:226-243`), jadi regex berjalan pada HTML mentah halaman seri, bukan pada hasil `select`.
- Pola yang perlu dibuktikan, misal `<li[^>]*>\s*<a href="/([^"/]+)/"` pada blok `div.eplister`, harus mengembalikan 168 entri dengan urutan SAMA seperti `epl-num`/`epl-title` (paralel per indeks, `parseEpisodesResult:309-311`). Jika tidak sejajar, nomor dan URL tertukar.
- Dengan slug telanjang, pipeId menjadi `remote/anichin/{id}/sub/{slug}/`; `parts[4]` = slug (non-numerik, `:154` memakai `epIdentifier`), dan `episodeUrl`/`episodeSlug` terisi (`:168-169`). Step sources lalu bisa `GET https://anichin.moe/{{episodeSlug}}/`.
- Syarat dan kelemahan: SEARCH tetap memakai href berawalan `/` (`select` biasa); regex di HTML mentah rapuh terhadap perubahan markup; `https://anichin.moe` perlu ditulis literal di `url` http (template hanya menyediakan variabel runtime). Belum dijalankan; jangan dinyatakan solusi sebelum diuji (Gradle copy + `GeneratorEvidenceTest` dengan workflow yang diubah).
- Tidak menyelesaikan: B2 (`getSources`), dan `base64_decode` per item (mirror).

## Lampiran A. Sumber tes tambahan (hanya di salinan, tidak ada di repo Android)

`gen.json` dihasilkan skrip scratch di luar kedua repo (pipeline sama dengan `tests/unit/realGenerator.test.js`, jsdom sebagai `DOMParser`), disimpan di `D:/Mizan/_p4_jsoup/gen.json`.

```kotlin
package com.anilili.data.provider.remote

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderConfigValidator
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntimeFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.File

class GeneratorEvidenceTest {
    private val fx = "src/test/resources/fixtures/anichin/"
    private fun p(s: String) = System.out.println("EVID " + s)

    @Test
    fun generatorOutputThroughRealKotlinRuntime() {
        val body = File("D:/Mizan/_p4_jsoup/gen.json").readText()
        // strict default Json, same as RemoteProviderManager.fetchConfig
        val cfg = Json.decodeFromString(RemoteProviderConfig.serializer(), body)
        p("STRICT_DECODE_OK providers=${cfg.providers.size} workflows=${cfg.workflows?.keys}")
        val v = RemoteProviderConfigValidator().validate(cfg, "https://cfg.example/provider.json")
        p("VALIDATOR isValid=${v.isValid} errors=${v.errors} warnings=${v.warnings}")

        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            requested += req.url.toString()
            val path = req.url.encodedPath
            val file = when {
                path == "/" -> "search.htm"
                path.startsWith("/btth-") || path.contains("episode") -> "watch.htm"
                else -> "series.htm"
            }
            val html = File(fx + file).readText()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }).build()

        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val store = com.anilili.data.provider.remote.runtime.RemoteSlugStore()
        val factory = RemoteProviderRuntimeFactory(client, json, store)
        val rt = factory.createRuntime(cfg.providers[0], cfg)
        p("RUNTIME_CREATED=${rt != null}")
        rt!!

        val sr = runBlocking { rt.search("quanzhi") }
        p("SEARCH n=${sr.size} first=${sr.firstOrNull()} all=${sr.map { it.title to it.slugOrUrl }}")
        p("REQ after search=$requested")
        requested.clear()

        val er = runBlocking { rt.getEpisodes(123, "Quanzhi Fashi Season 7") }
        val eps = er?.providers?.firstOrNull()?.sub ?: emptyList()
        p("EPISODES n=${eps.size} first3=${eps.take(3).map { it.number to it.pipeId }}")
        p("EPISODES last3=${eps.takeLast(3).map { it.number to it.pipeId }}")
        p("EPISODES dupNumbers=${eps.groupBy { it.number }.filter { it.value.size > 1 }.map { it.key to it.value.map { e -> e.pipeId } }}")
        p("EPISODES max=${eps.maxOfOrNull { it.number }}")
        p("REQ after episodes=$requested")
        requested.clear()

        val s1 = runBlocking { rt.getSources(123, 1.0, Category.SUB) }
        p("GETSOURCES streams=${s1?.streams?.map { it.url to it.type }}")
        p("REQ after getSources=$requested")
        requested.clear()

        val pipe = eps.firstOrNull()?.pipeId ?: "remote/anichin/123/sub//x/"
        val s2 = runBlocking { rt.getSourcesForPipeId(pipe, 123, SourceVariant.GENERIC_SUB) }
        p("GETSOURCESFORPIPEID pipe=$pipe streams=${s2?.streams?.map { it.url to it.type }}")
        p("REQ after getSourcesForPipeId=$requested")

        // Counter-experiment: pre-seed anime slug with an episode path so {{url}} points at the watch page
        store.putAnimeSlug("anichin", 999, "/btth-season-5-episode-153-subtitle-indonesia/")
        requested.clear()
        val s3 = runBlocking { rt.getSources(999, 153.0, Category.SUB) }
        p("SEEDED getSources streams=${s3?.streams?.map { Triple(it.url, it.type, it.quality) }}")
        p("REQ seeded=$requested")
    }
}
```
