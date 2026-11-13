// engineContract.js — Sumber kebenaran tunggal untuk semua constraint engine Anilili.
// Semua komponen lain (validator, generator, tester) mengimpor dari sini.
//
// Referensi: https://github.com/Jajan-Bocah/Anilili
// Branch: feature/hybrid-remote-provider-engine
// Commit SHA: b755e47eb2ca1a5daf0b51f1310a684cb9613999 (b755e47) — P5 (perbaikan pipeId/slug)
// Baseline sebelumnya: 8c6230665699978963aa3e77dd791ceaced94219 (8c62306) — P4
//
// Disinkronkan lewat pembacaan source (bukan eksekusi JVM/Android) terhadap:
// RemoteProviderConfig.kt, RemoteProviderConfigValidator.kt, RemoteSourceTrust.kt,
// RemoteProviderManager.kt, RemoteProviderRuntimeFactory.kt, WorkflowRemoteProviderRuntime.kt,
// RemoteSlugStore.kt, WorkflowStep.kt, WorkflowContext.kt, WorkflowExecutor.kt,
// TemplateEvaluator.kt, Base64Codec.kt, RemoteRequestPolicy.kt. Rincian ada di docs/P6_ENGINE_SYNC_REPORT.md.

export const ENGINE_SCHEMA_VERSION = 1
export const ENGINE_REFERENCE_COMMIT = 'b755e47eb2ca1a5daf0b51f1310a684cb9613999'

export const SUPPORTED_OPS = [
  'http',
  'parse_html',
  'select',
  'parse_json',
  'regex',
  'map',
  'normalize',
  'assert',
  'filter',
]

export const SUPPORTED_SOURCES = ['miruro', 'anivexa', 'generic_json', 'generic_html']
export const SUPPORTED_CATEGORIES = ['general', 'anime', 'donghua', 'adult', 'special']
export const SUPPORTED_OUTPUT_TYPES = ['RAW', 'EPISODES', 'SOURCES', 'SERVERS', 'SEARCH']
export const SUPPORTED_SUBTITLE_VARIANTS = ['hsub', 'ssub', 'sub', 'dub']

// Enum Kotlin di-resolve dengan valueOf(uppercase) oleh RemoteProviderRuntimeFactory.
// Nilai tidak dikenal melempar exception → parseWorkflows membuang SELURUH workflow.
export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']
export const SELECT_MODES = ['FIRST', 'ALL', 'TEXT', 'HTML', 'ATTRIBUTE']
export const REGEX_MODES = ['FIRST', 'ALL', 'REPLACE']

// Sub-tipe polymorphic (WorkflowStep.kt)
export const NORMALIZE_OP_TYPES = {
  trim: [],
  lowercase: [],
  uppercase: [],
  url_resolve: ['baseUrl'],
  episode_number: [],
  language_normalize: [],
  join: ['separator'],
  default: ['value'],
  base64_decode: [],
}
export const ASSERT_CONDITION_TYPES = {
  not_null: [],
  not_empty: [],
  equals: ['value'],
  contains: ['value'],
  matches: ['pattern'],
  size: [],
}
export const FILTER_PREDICATE_TYPES = {
  not_null: [],
  not_empty: [],
  contains: ['value'],
  matches: ['pattern'],
}

// ID provider bawaan app — RemoteProviderConfigValidator.validateDuplicateIds menolak bentrok.
export const SYSTEM_PROVIDER_IDS = [
  'bee', 'sun', 'pewe', 'hop', 'ally', 'kiwi', 'moo', 'bonk', 'knob',
  'nun', 'bun', 'cog', 'twin', 'telli',
  'anipm', 'anichan', 'zoko', 'animepahe', 'rareanimes', 'anidbapp', 'animeheaven',
  'xfdm', 'anikoto', 'kaa', 'allanime', 'animekai', 'reanime', 'anizone', 'animegg',
  'anineko', '2dhive', 'animedekho', 'toonhub', 'uniquestream', 'animekhor', 'kartoons',
  'animeav1', 'rift', 'hanime', 'hentaihaven', 'hstream', 'onepace',
]

// Template syntax: {{variable.path}} — tidak ada ${...}, tidak ada filter/pipe.
// Kotlin VARIABLE_PATTERN: \{\{(\s*[a-zA-Z_][a-zA-Z0-9_.-]*\s*)\}}
export const TEMPLATE_SYNTAX = '{{variable.path}}'
export const TEMPLATE_REGEX = /\{\{\s*([a-zA-Z_][a-zA-Z0-9_.-]*)\s*\}\}/g

export const SELECTOR_TYPES = ['css']
export const JSONPATH_NOTATION = 'dot-notation'

export const DEFAULT_SECURITY = {
  allowedDomains: [],
  maxResponseBytes: 1_048_576,
  maxRequestsPerRun: 20,
  connectTimeoutMs: 10_000,
  readTimeoutMs: 15_000,
  totalTimeoutMs: 30_000,
  allowRedirects: true,
  maxRedirects: 5,
}

export const REMOTE_SOURCE_TRUST_SCHEMA = {
  configUrl: 'string',
  configOrigin: 'string',
  allowHttpConfig: 'boolean (default false)',
  allowPrivateIpConfig: 'boolean (default false)',
  trustedEndpointOrigins: 'string[] (default [])',
  allowHttpEndpoints: 'boolean (default false)',
  allowPrivateIpEndpoints: 'boolean (default false)',
  isLocalNetwork: 'boolean',
  trustedAt: 'number (timestamp)',
}

export const DEFAULT_CAPABILITIES = {
  supportsEpisodes: true,
  supportsSources: true,
  supportsSubtitles: false,
  supportsSkipTimes: false,
  supportsDownload: false,
  supportsSearch: false,
  requiresWebView: false,
  multiAudio: false,
  shortLivedStreamUrls: false,
  countryLocked: null,
  adultOnly: false,
}

// Key yang dikenal Kotlin. RemoteProviderManager.fetchConfig (jalur install/update) memakai
// Json default (ignoreUnknownKeys=false, coerceInputValues=false) → key asing / enum asing menggagalkan decode.
const TOP_LEVEL_KEYS = ['schemaVersion', 'providers', 'metadata', 'workflows', 'security', 'update']
const PROVIDER_KEYS = [
  'id', 'name', 'endpoint', 'source', 'capabilities', 'category',
  'languages', 'subtitleVariants', 'priority', 'enabled',
]
const METADATA_KEYS = [
  'name', 'description', 'author', 'version', 'sourceUrl', 'fetchedAt',
  'checksum', 'minAppVersion', 'maxAppVersion',
]
const WORKFLOW_KEYS = ['name', 'version', 'description', 'steps', 'output']
const STEP_KEYS = ['op', 'id', 'params', 'saveAs']
const OUTPUT_KEYS = ['type', 'mapping']
const UPDATE_KEYS = ['mode', 'intervalHours']
const CAPABILITY_KEYS = Object.keys(DEFAULT_CAPABILITIES)
const SECURITY_KEYS = Object.keys(DEFAULT_SECURITY)

// Variabel yang di-inject WorkflowRemoteProviderRuntime per workflow.
// `always` selalu ada; `conditional` hanya jika cache slug terisi / jalur tertentu.
export const WORKFLOW_INJECTED_VARIABLES = {
  search: { always: ['query'], conditional: [] },
  episodes: {
    always: ['anilistId', 'title'],
    conditional: ['animeSlug', 'animeUrl', 'slug', 'url'],
  },
  sources: {
    always: ['anilistId', 'episodeNumber', 'category'],
    // pipeId/variant hanya pada getSourcesForPipeId; url tidak ada pada jalur itu.
    conditional: ['episodeUrl', 'episodeSlug', 'animeSlug', 'animeUrl', 'url', 'pipeId', 'variant'],
  },
  servers: { always: ['anilistId', 'episodeNumber'], conditional: ['episodeUrl'] },
}

// Blocker CRITICAL: perilaku engine hasil pembacaan source yang tidak bisa diperbaiki dari sisi generator
// dan mencegah klaim "ANILILI_READY" tanpa uji Android. Bukan hasil eksekusi JVM.
// P5 (b755e47) memperbaiki pipeId/slug: parseRemotePipeId kini split limit=5 sehingga href absolut
// (berisi '/') diteruskan langsung tanpa lookup cache bare-number. Dua blocker lama (episode-slug-cache-stores-pipeid
// dan sources-url-prefers-anime-slug) tidak berlaku lagi untuk engine P5.
export const CRITICAL_BLOCKERS = [
  {
    id: 'no-per-item-base64-decode',
    severity: 'critical',
    message:
      'normalize base64_decode hanya memproses satu string (resolveInputString); tidak ada decode per-item untuk list. Hasil select ALL atas option base64 (mis. select.mirror option) tetap blob base64 pada output SOURCES/SERVERS.',
    affected: ['sources', 'servers'],
  },
  {
    id: 'android-playback-untested',
    severity: 'critical',
    message:
      'Playback pada perangkat Android belum diuji. Status ANILILI_READY tidak boleh dinyatakan tanpa bukti uji Android.',
    affected: ['search', 'episodes', 'servers', 'sources'],
  },
]

// Dugaan dari pembacaan source; BELUM diverifikasi dengan eksekusi JVM. Validator hanya memberi peringatan.
export const SUSPECTED_BLOCKERS = [
  {
    id: 'polymorphic-ops-decode-suspected',
    severity: 'suspected',
    message:
      'NormalizeOperation/AssertCondition/FilterPredicate adalah sealed interface tanpa @SerialName pada subclass dan memiliki properti "type" yang sama dengan class discriminator default. Decode json {"type":"trim"} diduga gagal (nama serial = nama kelas penuh) sehingga seluruh workflow dibuang oleh parseWorkflows. anichin.json/kuramanime.json bawaan tidak memakai op normalize/assert/filter. Status: SUSPECTED / NOT_TESTED.',
    affected: ['normalize', 'assert', 'filter'],
  },
  {
    id: 'kuramanime-search-parse-json-lists',
    severity: 'suspected',
    message:
      'parse_json atas array of object menghasilkan list string JSON; parseSearchResults hanya memproses Map/JsonObject sehingga item string dibuang. Dari source: output SEARCH berbasis parse_json tanpa mapping titles/urls diduga kosong. NOT_TESTED di JVM.',
    affected: ['search'],
  },
]

export const OPERATIONAL_LIMITATIONS = [
  {
    id: 'episode-number-op-strips-ep-from-episode',
    severity: 'operational',
    message:
      'normalize episode_number menghapus Regex("(?i)^ep\\.?\\s*") sebelum "^episode\\s*", sehingga "Episode 5" menjadi "isode 5" (hanya "Ep 5"/"Ep. 5" yang benar). Hindari episode_number pada teks berawalan "Episode"; ekstrak angka dengan regex.',
  },
  {
    id: 'manager-fetchconfig-strict-json',
    severity: 'operational',
    message:
      'RemoteProviderManager.fetchConfig (addCustomSource/updateSource) mendekode dengan Json default: key tidak dikenal, enum tidak dikenal, dan key nullable tanpa default yang hilang menggagalkan install. previewConfig memakai Json lenient sehingga preview bisa sukses sementara install gagal.',
  },
  {
    id: 'workflow-dropped-on-parse-error',
    severity: 'operational',
    message:
      'parseWorkflows menangkap exception per workflow (method/mode/type tidak valid, JSON params rusak) dan membuang workflow tersebut secara diam-diam; tidak ada error ke pengguna.',
  },
  {
    id: 'params-null-becomes-string',
    severity: 'operational',
    message:
      'WorkflowParamsSerializer mengubah JsonNull menjadi string "null" dan kosong menjadi "". Hilangkan key opsional (attribute, jsonPath) alih-alih mengisi null/kosong.',
  },
  {
    id: 'params-not-template-evaluated',
    severity: 'operational',
    message:
      'Hanya http.url/headers/query/body dan normalize url_resolve.baseUrl yang dievaluasi template. selector, pattern, jsonPath, dan input diperlakukan literal.',
  },
  {
    id: 'no-implicit-input-chaining',
    severity: 'operational',
    message:
      'Engine tidak meneruskan output step sebelumnya ke step berikutnya. Setiap step wajib menyebut params.input (nama variabel) dan menyimpan hasil lewat saveAs.',
  },
  {
    id: 'update-mode-free-string',
    severity: 'operational',
    message: 'update.mode adalah free String, tidak divalidasi.',
  },
  {
    id: 'checksum-unverified',
    severity: 'operational',
    message: 'checksum tersedia tetapi tidak pernah diverifikasi oleh engine.',
  },
  {
    id: 'regex-replace-hardcoded',
    severity: 'operational',
    message: 'RegexStep mode REPLACE selalu mengganti dengan "". Tidak ada field replacement.',
  },
  {
    id: 'step-id-non-deterministic',
    severity: 'operational',
    message: 'Step ID tanpa nilai digenerate dari System.currentTimeMillis().',
  },
  {
    id: 'base64-decode-normalize-only',
    severity: 'operational',
    message:
      'base64_decode hanya tersedia sebagai NormalizeOperation; jalur ini sendiri berstatus SUSPECTED (lihat polymorphic-ops-decode-suspected).',
  },
  {
    id: 'template-var-dash-support',
    severity: 'operational',
    message: 'Nama variabel template boleh mengandung dash: [a-zA-Z0-9_.-]. Sintaks {{a[0]}} tidak cocok pola dan tetap literal.',
  },
  {
    id: 'remote-source-trust-not-in-json',
    severity: 'operational',
    message:
      'RemoteSourceTrust dikelola terpisah oleh app (grant pengguna), tidak ada di provider.json. Loopback (localhost, 127.x, ::1, 0.0.0.0) ditolak permanen walau ada trust.',
  },
]

export const BLOCKER_STATUSES = {
  JSON_VALID: 'JSON_VALID',
  OFFLINE_PASSED: 'OFFLINE_PASSED',
  LIVE_PASSED: 'LIVE_PASSED',
  ANILILI_READY: 'ANILILI_READY',
}

// ── Helper jaringan (parity RemoteSourceTrust) ───────────────────────────

/** Hostname huruf kecil tanpa port dan tanpa kurung IPv6; null jika URL tidak valid. */
export function hostnameOf(url) {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^\[|\]$/g, '')
  } catch {
    return null
  }
}

/** Parity RemoteSourceTrust.isLoopbackHost (+ seluruh 127.0.0.0/8 yang di-resolve InetAddress sebagai loopback). */
export function isLoopbackHost(host) {
  if (!host) return false
  const h = host.toLowerCase().replace(/^\[|\]$/g, '')
  return h === 'localhost' || h === '::1' || h === '0.0.0.0' || /^127\./.test(h)
}

/** Parity RemoteProviderConfigValidator.validateSecurity: host loopback ATAU string mengandung localhost/127.0.0.1/::1. */
export function isLoopbackEndpoint(url) {
  if (typeof url !== 'string') return false
  const lower = url.toLowerCase()
  return (
    isLoopbackHost(hostnameOf(url)) ||
    lower.includes('localhost') ||
    lower.includes('127.0.0.1') ||
    lower.includes('::1')
  )
}

/** Parity RemoteSourceTrust.isPrivateOrLocalHost (bagian literal; resolusi DNS tidak mungkin di browser). */
export function isPrivateOrLocalHost(host) {
  if (!host) return false
  const lower = host.toLowerCase().replace(/^\[|\]$/g, '')
  if (/\.(local|lan|internal)$/.test(lower) || lower.endsWith('.home.arpa')) return true
  if (/^10\./.test(lower) || /^192\.168\./.test(lower)) return true
  if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(lower)) return true
  if (/^169\.254\./.test(lower) || /^127\./.test(lower) || /^0\./.test(lower)) return true
  return /^(fc00|fd00|fe80):/.test(lower)
}

function extractOrigin(url) {
  try {
    const u = new URL(url)
    const host = u.hostname.toLowerCase().replace(/^\[|\]$/g, '')
    const port = u.port ? `:${u.port}` : u.protocol === 'https:' ? ':443' : ':80'
    return `${u.protocol.replace(':', '')}://${host}${port}`
  } catch {
    return null
  }
}

function isEndpointTrustedByTrust(url, trust) {
  if (!trust || !Array.isArray(trust.trustedEndpointOrigins)) return false
  const origin = extractOrigin(url)
  if (!origin) return false
  return trust.trustedEndpointOrigins.some((o) => {
    const norm = extractOrigin(o) || String(o).toLowerCase()
    return norm === origin
  })
}

function isConfigFetchAllowedByTrust(url, trust) {
  if (!trust || !trust.configOrigin) return false
  const origin = extractOrigin(url)
  if (!origin) return false
  const cfg = extractOrigin(trust.configOrigin) || String(trust.configOrigin).toLowerCase()
  return cfg === origin
}

// ── Validasi ────────────────────────────────────────────────────────────

const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const hasKey = (o, k) => Object.prototype.hasOwnProperty.call(o, k)

function checkUnknownKeys(obj, allowed, prefix, errors) {
  Object.keys(obj).forEach((k) => {
    if (!allowed.includes(k)) {
      errors.push(
        `${prefix}.${k}: key tidak dikenal engine; RemoteProviderManager.fetchConfig (Json strict) akan menolak config`
      )
    }
  })
}

function checkRequiredKeys(obj, keys, prefix, errors) {
  keys.forEach((k) => {
    if (!hasKey(obj, k)) {
      errors.push(
        `${prefix}.${k} wajib ada (boleh null jika nullable): Kotlin tanpa default → MissingFieldException`
      )
    }
  })
}

function parseMaybeJson(value) {
  if (typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return undefined
  }
}

/** Params string-map (headers/query/mappings): objek, atau string JSON objek. */
function checkStringMap(value, label, errors) {
  const parsed = parseMaybeJson(value)
  if (!isPlainObject(parsed)) {
    errors.push(`${label} harus objek JSON {string:string}`)
    return
  }
  if (Object.values(parsed).some((v) => v !== null && typeof v === 'object')) {
    errors.push(`${label} hanya boleh bernilai string/angka/boolean`)
  }
}

function checkPolymorphic(value, table, label, errors, warnings) {
  const parsed = parseMaybeJson(value)
  const items = Array.isArray(parsed) ? parsed : [parsed]
  items.forEach((item, i) => {
    if (!isPlainObject(item) || typeof item.type !== 'string' || !(item.type in table)) {
      errors.push(`${label}${Array.isArray(parsed) ? `[${i}]` : ''}.type harus salah satu dari: ${Object.keys(table).join(', ')}`)
      return
    }
    table[item.type].forEach((req) => {
      if (item[req] === undefined || item[req] === null) {
        errors.push(`${label}${Array.isArray(parsed) ? `[${i}]` : ''}.${req} wajib untuk type "${item.type}"`)
      }
    })
  })
  warnings.push(
    `${label}: decode polymorphic (sealed interface) berstatus SUSPECTED di engine; lihat blocker polymorphic-ops-decode-suspected`
  )
}

function validateStepParams(step, prefix, errors, warnings) {
  const params = step.params
  if (params === undefined) return
  if (!isPlainObject(params)) {
    errors.push(`${prefix}.params harus berupa objek`)
    return
  }
  Object.entries(params).forEach(([k, v]) => {
    if (v === null) {
      errors.push(`${prefix}.params.${k} bernilai null → engine membaca string "null"; hapus key`)
    } else if ((k === 'attribute' || k === 'jsonPath') && v === '') {
      errors.push(`${prefix}.params.${k} kosong; hapus key agar engine memakai nilai default`)
    }
  })

  const enumCheck = (key, allowed, def) => {
    if (params[key] === undefined) return
    if (typeof params[key] !== 'string' || !allowed.includes(params[key].toUpperCase())) {
      errors.push(
        `${prefix}.params.${key} harus salah satu dari: ${allowed.join(', ')} (default ${def}); nilai lain membuat engine membuang workflow`
      )
    }
  }

  switch (step.op) {
    case 'http':
      enumCheck('method', HTTP_METHODS, 'GET')
      if (!params.url) errors.push(`${prefix}.params.url wajib untuk op http`)
      if (params.headers !== undefined) checkStringMap(params.headers, `${prefix}.params.headers`, errors)
      if (params.query !== undefined) checkStringMap(params.query, `${prefix}.params.query`, errors)
      break
    case 'select':
      enumCheck('mode', SELECT_MODES, 'FIRST')
      if (!params.selector) errors.push(`${prefix}.params.selector wajib untuk op select`)
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      break
    case 'regex':
      enumCheck('mode', REGEX_MODES, 'FIRST')
      if (!params.pattern) errors.push(`${prefix}.params.pattern wajib untuk op regex`)
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      break
    case 'parse_html':
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      break
    case 'parse_json':
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      if (typeof params.jsonPath === 'string' && /[A-Za-z0-9_-]\[\d+\]/.test(params.jsonPath)) {
        warnings.push(
          `${prefix}.params.jsonPath: "a[0]" tidak didukung extractByPath (split "."); gunakan "a.[0]"`
        )
      }
      break
    case 'map':
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      if (params.mappings !== undefined) checkStringMap(params.mappings, `${prefix}.params.mappings`, errors)
      break
    case 'normalize':
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      if (params.operations !== undefined) {
        checkPolymorphic(params.operations, NORMALIZE_OP_TYPES, `${prefix}.params.operations`, errors, warnings)
      }
      break
    case 'assert':
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      if (params.condition !== undefined) {
        checkPolymorphic(params.condition, ASSERT_CONDITION_TYPES, `${prefix}.params.condition`, errors, warnings)
      }
      break
    case 'filter':
      if (!params.input) errors.push(`${prefix}.params.input wajib (tidak ada chaining implisit di engine)`)
      if (params.predicate !== undefined) {
        checkPolymorphic(params.predicate, FILTER_PREDICATE_TYPES, `${prefix}.params.predicate`, errors, warnings)
      }
      break
    default:
  }
}

function collectTemplateStrings(step) {
  const out = []
  const p = step.params
  if (!isPlainObject(p)) return out
  const pushMap = (v) => {
    const m = parseMaybeJson(v)
    if (isPlainObject(m)) Object.values(m).forEach((x) => typeof x === 'string' && out.push(x))
  }
  if (step.op === 'http') {
    if (typeof p.url === 'string') out.push(p.url)
    if (typeof p.body === 'string') out.push(p.body)
    pushMap(p.headers)
    pushMap(p.query)
  }
  if (step.op === 'normalize') {
    const ops = parseMaybeJson(p.operations)
    if (Array.isArray(ops)) {
      ops.forEach((o) => o && typeof o.baseUrl === 'string' && out.push(o.baseUrl))
    }
  }
  return out
}

/**
 * Cek variabel template terhadap konteks injeksi runtime + saveAs step sebelumnya.
 * @returns {string[]} peringatan
 */
export function checkWorkflowTemplates(name, wf) {
  const warnings = []
  const inj = WORKFLOW_INJECTED_VARIABLES[name]
  if (!inj || !Array.isArray(wf.steps)) return warnings
  const defined = new Set(inj.always)
  const conditional = new Set(inj.conditional)

  wf.steps.forEach((step, si) => {
    if (!isPlainObject(step)) return
    const prefix = `workflows.${name}.steps[${si}]`
    collectTemplateStrings(step).forEach((str) => {
      if (/\{\{[^}]*\[\d+\][^}]*\}\}/.test(str)) {
        warnings.push(`${prefix}: "{{a[0]}}" tidak cocok pola template engine dan tetap literal`)
      }
      for (const m of str.matchAll(new RegExp(TEMPLATE_REGEX.source, 'g'))) {
        const root = m[1].split('.')[0]
        if (defined.has(root)) continue
        if (conditional.has(root)) {
          warnings.push(
            `${prefix}: {{${m[1]}}} hanya di-inject kondisional pada workflow "${name}" (cache slug / jalur tertentu)`
          )
        } else {
          warnings.push(
            `${prefix}: {{${m[1]}}} tidak di-inject runtime untuk workflow "${name}" dan belum disimpan oleh step sebelumnya (saveAs)`
          )
        }
      }
    })
    if (typeof step.saveAs === 'string' && step.saveAs) defined.add(step.saveAs)
  })
  return warnings
}

/**
 * Validasi struktur provider.json lengkap terhadap engine contract.
 * @param {object} json
 * @param {{ trust?: object }} options
 * @returns {{ valid: boolean, errors: string[], warnings: string[], blockers: string[] }}
 */
export function validateProviderJson(json, options = {}) {
  const errors = []
  const warnings = []
  const blockers = CRITICAL_BLOCKERS.map((b) => b.id)
  const trust = options.trust || null

  if (!isPlainObject(json)) {
    return { valid: false, errors: ['provider.json harus berupa objek JSON'], warnings, blockers }
  }

  checkUnknownKeys(json, TOP_LEVEL_KEYS, 'provider.json', errors)

  if (json.schemaVersion !== ENGINE_SCHEMA_VERSION) {
    errors.push(`schemaVersion harus ${ENGINE_SCHEMA_VERSION}, ditemukan: ${json.schemaVersion}`)
  }

  // providers
  if (!Array.isArray(json.providers) || json.providers.length === 0) {
    errors.push('providers harus berupa array dengan minimal 1 entri')
  } else {
    if (json.providers.length > 100) errors.push('providers maksimal 100 entri')
    const seen = new Set()
    json.providers.forEach((p, i) => {
      const prefix = `providers[${i}]`
      if (!isPlainObject(p)) {
        errors.push(`${prefix} harus berupa objek`)
        return
      }
      checkUnknownKeys(p, PROVIDER_KEYS, prefix, errors)
      if (!p.id || typeof p.id !== 'string' || !/^[a-z0-9_-]+$/.test(p.id)) {
        errors.push(`${prefix}.id harus cocok ^[a-z0-9_-]+$, maksimal 50 karakter`)
      } else {
        if (p.id.length > 50) errors.push(`${prefix}.id maksimal 50 karakter`)
        if (seen.has(p.id.toLowerCase())) {
          errors.push(`${prefix}.id duplikat (case-insensitive): ${p.id}`)
        }
        seen.add(p.id.toLowerCase())
        if (SYSTEM_PROVIDER_IDS.includes(p.id.toLowerCase())) {
          errors.push(`${prefix}.id "${p.id}" bentrok dengan provider bawaan app; pilih id lain`)
        }
      }
      if (!p.name) errors.push(`${prefix}.name wajib diisi`)
      if (p.name && p.name.length > 100) errors.push(`${prefix}.name maksimal 100 karakter`)

      if (!p.endpoint) {
        errors.push(`${prefix}.endpoint wajib diisi`)
      } else {
        const isHttps = p.endpoint.startsWith('https://')
        const isHttp = p.endpoint.startsWith('http://')
        if (!isHttps && !isHttp) {
          errors.push(`${prefix}.endpoint harus HTTP atau HTTPS`)
        } else {
          if (isLoopbackEndpoint(p.endpoint)) {
            errors.push(`${prefix}.endpoint mengarah ke loopback; ditolak permanen oleh engine (trust tidak berlaku)`)
          }
          if (isHttp) {
            if (!trust || !trust.allowHttpEndpoints || !isEndpointTrustedByTrust(p.endpoint, trust)) {
              errors.push(
                `${prefix}.endpoint HTTP memerlukan RemoteSourceTrust eksplisit (allowHttpEndpoints + trusted origin)`
              )
            } else {
              warnings.push(`${prefix}.endpoint HTTP diizinkan via RemoteSourceTrust`)
            }
          }
          const endpointHost = hostnameOf(p.endpoint)
          if (endpointHost && isPrivateOrLocalHost(endpointHost) && !isLoopbackHost(endpointHost)) {
            if (!trust || !trust.allowPrivateIpEndpoints || !isEndpointTrustedByTrust(p.endpoint, trust)) {
              errors.push(
                `${prefix}.endpoint private IP/LAN memerlukan RemoteSourceTrust eksplisit (allowPrivateIpEndpoints + trusted origin)`
              )
            } else {
              warnings.push(`${prefix}.endpoint private IP/LAN diizinkan via RemoteSourceTrust`)
            }
          }
        }
        if (p.endpoint.length > 2048) errors.push(`${prefix}.endpoint maksimal 2048 karakter`)
      }

      if (!SUPPORTED_SOURCES.includes(p.source)) {
        errors.push(`${prefix}.source harus salah satu dari: ${SUPPORTED_SOURCES.join(', ')}`)
      }
      if (p.category !== undefined && !SUPPORTED_CATEGORIES.includes(p.category)) {
        errors.push(
          `${prefix}.category harus salah satu dari: ${SUPPORTED_CATEGORIES.join(', ')} (Json strict install menolak nilai lain)`
        )
      }
      if (p.subtitleVariants !== undefined) {
        if (!Array.isArray(p.subtitleVariants) || p.subtitleVariants.some((v) => !SUPPORTED_SUBTITLE_VARIANTS.includes(v))) {
          errors.push(`${prefix}.subtitleVariants harus array dari: ${SUPPORTED_SUBTITLE_VARIANTS.join(', ')}`)
        }
      }
      if (p.languages !== undefined && (!Array.isArray(p.languages) || p.languages.some((l) => typeof l !== 'string'))) {
        errors.push(`${prefix}.languages harus array string`)
      }
      if (p.priority !== undefined && !Number.isInteger(p.priority)) {
        errors.push(`${prefix}.priority harus bilangan bulat`)
      } else if (p.priority < 0) {
        warnings.push(`${prefix}.priority sebaiknya tidak negatif`)
      }
      if (p.enabled !== undefined && typeof p.enabled !== 'boolean') {
        errors.push(`${prefix}.enabled harus boolean`)
      }
      if (!hasKey(p, 'capabilities') || !isPlainObject(p.capabilities)) {
        errors.push(`${prefix}.capabilities wajib berupa objek (tanpa default di Kotlin)`)
      } else {
        checkUnknownKeys(p.capabilities, CAPABILITY_KEYS, `${prefix}.capabilities`, errors)
      }
    })
  }

  // metadata
  if (!isPlainObject(json.metadata)) {
    errors.push('metadata wajib diisi')
  } else {
    const md = json.metadata
    checkUnknownKeys(md, METADATA_KEYS, 'metadata', errors)
    checkRequiredKeys(md, ['name', 'description', 'author', 'version', 'sourceUrl', 'fetchedAt', 'checksum'], 'metadata', errors)
    if (!md.name) errors.push('metadata.name wajib diisi')
    if (!md.version) errors.push('metadata.version wajib diisi')
    if (!md.sourceUrl) {
      errors.push('metadata.sourceUrl wajib diisi (validator engine: "Metadata sourceUrl is required")')
    } else {
      const isHttps = md.sourceUrl.startsWith('https://')
      const isHttp = md.sourceUrl.startsWith('http://')
      if (!isHttps && !isHttp) {
        errors.push('metadata.sourceUrl harus HTTP atau HTTPS')
      } else if (isHttp) {
        if (!trust || !trust.allowHttpConfig || !isConfigFetchAllowedByTrust(md.sourceUrl, trust)) {
          errors.push('metadata.sourceUrl HTTP memerlukan RemoteSourceTrust eksplisit (allowHttpConfig)')
        } else {
          warnings.push('metadata.sourceUrl HTTP diizinkan via RemoteSourceTrust')
        }
      }
    }
    if (typeof md.fetchedAt !== 'number' || md.fetchedAt <= 0) {
      errors.push('metadata.fetchedAt harus bilangan > 0')
    }
  }

  // workflows (opsional)
  if (json.workflows !== undefined && json.workflows !== null) {
    if (!isPlainObject(json.workflows)) {
      errors.push('workflows harus berupa objek {nama: workflow}')
    } else {
      Object.entries(json.workflows).forEach(([name, wf]) => {
        const wp = `workflows.${name}`
        if (!isPlainObject(wf)) {
          errors.push(`${wp} harus berupa objek`)
          return
        }
        checkUnknownKeys(wf, WORKFLOW_KEYS, wp, errors)
        checkRequiredKeys(wf, WORKFLOW_KEYS, wp, errors)
        if (!Array.isArray(wf.steps)) {
          if (hasKey(wf, 'steps')) errors.push(`${wp}.steps harus berupa array`)
        } else {
          wf.steps.forEach((step, si) => {
            const prefix = `${wp}.steps[${si}]`
            if (!isPlainObject(step)) {
              errors.push(`${prefix} harus berupa objek`)
              return
            }
            checkUnknownKeys(step, STEP_KEYS, prefix, errors)
            checkRequiredKeys(step, ['op', 'id', 'saveAs'], prefix, errors)
            if (!step.op || !SUPPORTED_OPS.includes(step.op)) {
              errors.push(`${prefix}.op harus salah satu dari: ${SUPPORTED_OPS.join(', ')}`)
              return
            }
            validateStepParams(step, prefix, errors, warnings)
          })
          warnings.push(...checkWorkflowTemplates(name, wf))
        }
        if (isPlainObject(wf.output)) {
          checkUnknownKeys(wf.output, OUTPUT_KEYS, `${wp}.output`, errors)
          checkRequiredKeys(wf.output, ['type'], `${wp}.output`, errors)
          const t = wf.output.type
          if (t !== null && t !== undefined && (typeof t !== 'string' || !SUPPORTED_OUTPUT_TYPES.includes(t.toUpperCase()))) {
            errors.push(`${wp}.output.type harus salah satu dari: ${SUPPORTED_OUTPUT_TYPES.join(', ')}`)
          }
          if (wf.output.mapping !== undefined) {
            if (!isPlainObject(wf.output.mapping) || Object.values(wf.output.mapping).some((v) => typeof v !== 'string')) {
              errors.push(`${wp}.output.mapping harus objek {string:string}`)
            }
          }
        } else if (hasKey(wf, 'output') && wf.output !== null) {
          errors.push(`${wp}.output harus berupa objek atau null`)
        }
      })
    }
  }

  // security (opsional) — SecurityConfig @Serializable, semua field camelCase, semua optional (ada default).
  // maxResponseBytes adalah Long di Kotlin → harus number literal, bukan string.
  if (json.security !== undefined && json.security !== null) {
    if (!isPlainObject(json.security)) {
      errors.push('security harus berupa objek')
    } else {
      checkUnknownKeys(json.security, SECURITY_KEYS, 'security', errors)
      if (json.security.allowedDomains !== undefined && !Array.isArray(json.security.allowedDomains)) {
        errors.push('security.allowedDomains harus array string')
      } else if (Array.isArray(json.security.allowedDomains)) {
        json.security.allowedDomains.forEach((d, i) => {
          if (typeof d !== 'string') errors.push(`security.allowedDomains[${i}] harus string`)
        })
      }
      if (json.security.maxResponseBytes !== undefined && typeof json.security.maxResponseBytes !== 'number') {
        errors.push('security.maxResponseBytes harus number literal (Long di Kotlin), bukan string')
      }
      if (json.security.maxRequestsPerRun !== undefined && json.security.maxRequestsPerRun < 1) {
        errors.push('security.maxRequestsPerRun minimal 1')
      }
    }
  }

  // update (opsional)
  if (json.update) {
    checkUnknownKeys(json.update, UPDATE_KEYS, 'update', errors)
    if (json.update.mode === undefined) warnings.push('update.mode tidak diatur, default: "auto"')
  }

  return { valid: errors.length === 0, errors, warnings, blockers }
}

/**
 * Validasi satu workflow step config.
 * @param {object} step
 * @returns {{ valid: boolean, errors: string[] }}
 */
export function validateWorkflowStep(step) {
  const errors = []

  if (!step.op || !SUPPORTED_OPS.includes(step.op)) {
    errors.push(`op harus salah satu dari: ${SUPPORTED_OPS.join(', ')}, ditemukan: ${step.op}`)
  }

  const templateFields = ['url', 'selector', 'jsonPath', 'pattern', 'baseUrl']
  templateFields.forEach((field) => {
    if (step.params && step.params[field] !== undefined) {
      const value = step.params[field]
      if (typeof value === 'string' && /\$\{/.test(value)) {
        errors.push(`params.${field} menggunakan \${} syntax, engine hanya mendukung {{variable.path}}`)
      }
    }
  })

  return { valid: errors.length === 0, errors }
}

/**
 * Validasi template string — cek apakah semua variabel menggunakan sintaks yang benar.
 * @param {string} str
 * @returns {{ valid: boolean, variables: string[], unsupported: string[] }}
 */
export function validateTemplateString(str) {
  const variables = []
  const unsupported = []

  if (typeof str !== 'string') {
    return { valid: true, variables: [], unsupported: [] }
  }

  const dollarMatches = str.match(/\$\{[^}]*\}/g)
  if (dollarMatches) unsupported.push(...dollarMatches)

  const braceMatches = str.match(TEMPLATE_REGEX) || []
  braceMatches.forEach((m) => {
    variables.push(m.replace(/\{\{\s*/, '').replace(/\s*\}\}/, ''))
  })

  return {
    valid: unsupported.length === 0,
    variables: [...new Set(variables)],
    unsupported: [...new Set(unsupported)],
  }
}

/**
 * Dapatkan status blocker engine.
 * @returns {{ critical: Array, suspected: Array, operational: Array }}
 */
export function getBlockerStatus() {
  return {
    critical: CRITICAL_BLOCKERS,
    suspected: SUSPECTED_BLOCKERS,
    operational: OPERATIONAL_LIMITATIONS,
  }
}
