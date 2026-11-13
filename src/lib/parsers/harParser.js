// harParser.js — Analisis file HAR (HTTP Archive) dengan sanitasi sensitif.
// Sanitasi WAJIB sebelum persistence: hapus credential, redaksi token dalam query.

const MAX_FILE_SIZE = 100 * 1024 * 1024 // 100 MB

const HEADER_KEYS_TO_REDACT = new Set([
  'cookie',
  'set-cookie',
  'set-cookie2',
  'authorization',
  'proxy-authorization',
  'www-authenticate',
  'x-api-key',
  'x-auth-token',
  'x-access-token',
  'x-session-token',
  'cookie2',
  'x-forwarded-for',
])

const QUERY_KEYS_TO_REDACT = new Set([
  'access_token',
  'api_key',
  'apikey',
  'token',
  'session',
  'sessionid',
  'auth',
  'key',
  'secret',
  'password',
  'passwd',
  'code',
  'id_token',
  'refresh_token',
  'client_secret',
  'client_id',
])

const REDACTED = '[REDACTED]'

function isSensitiveHeader(name) {
  return HEADER_KEYS_TO_REDACT.has(name.toLowerCase())
}

function isSensitiveQueryKey(name) {
  return QUERY_KEYS_TO_REDACT.has(name.toLowerCase())
}

/**
 * Sanitasi satu header array dari HAR entry.
 * @param {Array<{name:string, value:string}>} headers
 * @returns {Array<{name:string, value:string}>}
 */
function sanitizeHeaders(headers) {
  if (!Array.isArray(headers)) return []
  return headers.map((h) => ({
    name: h.name,
    value: isSensitiveHeader(h.name) ? REDACTED : h.value,
  }))
}

/**
 * Sanitasi queryString array dari HAR request.
 * @param {Array<{name:string, value:string}>} queryString
 * @returns {Array<{name:string, value:string}>}
 */
function sanitizeQueryString(queryString) {
  if (!Array.isArray(queryString)) return []
  return queryString.map((q) => ({
    name: q.name,
    value: isSensitiveQueryKey(q.name) ? REDACTED : q.value,
  }))
}

/**
 * Sanitasi postData jika mengandung field sensitif.
 * @param {object|null} postData
 * @returns {object|null}
 */
function sanitizePostData(postData) {
  if (!postData) return null
  const result = { ...postData }
  if (result.text) {
    // Redaksi field sensitif dalam teks postData (JSON atau form)
    try {
      const parsed = JSON.parse(result.text)
      const sanitized = redactSensitiveFields(parsed)
      result.text = JSON.stringify(sanitized)
    } catch {
      // Jika bukan JSON, coba redaksi pola key=value sederhana
      result.text = redactSensitiveInText(result.text)
    }
  }
  return result
}

const SENSITIVE_FIELD_PATTERNS = [
  /("access_token"\s*:\s*)"([^"]*)"/gi,
  /("api_key"\s*:\s*)"([^"]*)"/gi,
  /("apikey"\s*:\s*)"([^"]*)"/gi,
  /("token"\s*:\s*)"([^"]*)"/gi,
  /("session"\s*:\s*)"([^"]*)"/gi,
  /("password"\s*:\s*)"([^"]*)"/gi,
  /("passwd"\s*:\s*)"([^"]*)"/gi,
  /("secret"\s*:\s*)"([^"]*)"/gi,
  /("code"\s*:\s*)"([^"]*)"/gi,
  /("id_token"\s*:\s*)"([^"]*)"/gi,
  /("refresh_token"\s*:\s*)"([^"]*)"/gi,
  /("client_secret"\s*:\s*)"([^"]*)"/gi,
  /("client_id"\s*:\s*)"([^"]*)"/gi,
]

const SENSITIVE_URL_QUERY_PATTERNS = [
  /([?&]access_token=)([^&]+)/gi,
  /([?&]api_key=)([^&]+)/gi,
  /([?&]apikey=)([^&]+)/gi,
  /([?&]token=)([^&]+)/gi,
  /([?&]session=)([^&]+)/gi,
  /([?&]sessionid=)([^&]+)/gi,
  /([?&]auth=)([^&]+)/gi,
  /([?&]key=)([^&]+)/gi,
  /([?&]secret=)([^&]+)/gi,
  /([?&]password=)([^&]+)/gi,
  /([?&]passwd=)([^&]+)/gi,
  /([?&]code=)([^&]+)/gi,
  /([?&]id_token=)([^&]+)/gi,
  /([?&]refresh_token=)([^&]+)/gi,
  /([?&]client_secret=)([^&]+)/gi,
  /([?&]client_id=)([^&]+)/gi,
]

function sanitizeUrl(url) {
  if (!url || typeof url !== 'string') return url
  let result = url
  SENSITIVE_URL_QUERY_PATTERNS.forEach((pattern) => {
    result = result.replace(pattern, `$1${REDACTED}`)
  })
  return result
}

function sanitizeResponseContent(content) {
  if (!content) return content
  const result = { ...content }
  if (result.text) {
    try {
      const parsed = JSON.parse(result.text)
      const sanitized = redactSensitiveFields(parsed)
      result.text = JSON.stringify(sanitized)
    } catch {
      result.text = redactSensitiveInText(result.text)
    }
  }
  return result
}

function redactSensitiveFields(obj) {
  if (Array.isArray(obj)) return obj.map(redactSensitiveFields)
  if (obj && typeof obj === 'object') {
    const result = {}
    for (const [key, value] of Object.entries(obj)) {
      if (QUERY_KEYS_TO_REDACT.has(key.toLowerCase())) {
        result[key] = REDACTED
      } else if (typeof value === 'object' && value !== null) {
        result[key] = redactSensitiveFields(value)
      } else {
        result[key] = value
      }
    }
    return result
  }
  return obj
}

function redactSensitiveInText(text) {
  let result = text
  SENSITIVE_FIELD_PATTERNS.forEach((pattern) => {
    result = result.replace(pattern, `$1"${REDACTED}"`)
  })
  return result
}

/**
 * Klasifikasi tipe entry HAR berdasarkan URL dan mimeType.
 * @param {object} entry
 * @returns {'api' | 'html' | 'media' | 'redirect' | 'other'}
 */
function classifyEntry(entry) {
  const url = (entry.request && entry.request.url) || ''
  const mimeType = (entry.response && entry.response.content && entry.response.content.mimeType) || ''

  if (url.match(/\.(m3u8|mpd)/i)) return 'media'
  if (url.match(/\.(mp4|webm|mkv|ts)/i)) return 'media'
  if (mimeType.includes('application/json') || mimeType.includes('text/json')) {
    return 'api'
  }
  if (mimeType.includes('text/html')) return 'html'
  if (entry.response && entry.response.status >= 300 && entry.response.status < 400) {
    return 'redirect'
  }
  return 'other'
}

/**
 * Parsing dan sanitasi file HAR.
 * @param {string} content — isi file HAR sebagai JSON teks
 * @returns {{
 *   entries: Array<{...sanitized...}>,
 *   apiCalls: Array<{url, method, status, ...}>,
 *   htmlPages: Array<{url, status, ...}>,
 *   mediaUrls: Array<{url, type, ...}>,
 *   redirectChains: Array<{from, to, status}>,
 *   errors: string[]
 * }}
 */
export function parseHar(content) {
  const errors = []

  if (!content) {
    errors.push('Konten HAR kosong')
    return { entries: [], apiCalls: [], htmlPages: [], mediaUrls: [], redirectChains: [], errors }
  }

  if (content.length > MAX_FILE_SIZE) {
    errors.push(`File terlalu besar (maksimal ${MAX_FILE_SIZE / (1024 * 1024)} MB)`)
    return { entries: [], apiCalls: [], htmlPages: [], mediaUrls: [], redirectChains: [], errors }
  }

  let parsed
  try {
    parsed = JSON.parse(content)
  } catch (err) {
    errors.push(`HAR tidak valid JSON: ${err.message}`)
    return { entries: [], apiCalls: [], htmlPages: [], mediaUrls: [], redirectChains: [], errors }
  }

  if (!parsed.log || !Array.isArray(parsed.log.entries)) {
    errors.push('Struktur HAR tidak valid: log.entries hilang')
    return { entries: [], apiCalls: [], htmlPages: [], mediaUrls: [], redirectChains: [], errors }
  }

  const entries = []
  const apiCalls = []
  const htmlPages = []
  const mediaUrls = []
  const redirectChains = []

  parsed.log.entries.forEach((rawEntry) => {
    const request = rawEntry.request || {}
    const response = rawEntry.response || {}

    // Sanitasi headers
    const sanitizedRequestHeaders = sanitizeHeaders(request.headers)
    const sanitizedResponseHeaders = sanitizeHeaders(response.headers)

    // Sanitasi query string
    const sanitizedQuery = sanitizeQueryString(request.queryString)

    // Sanitasi postData
    const sanitizedPostData = sanitizePostData(request.postData)

    const entry = {
      startedDateTime: rawEntry.startedDateTime,
      time: rawEntry.time,
      request: {
        method: request.method,
        url: request.url,
        httpVersion: request.httpVersion,
        headers: sanitizedRequestHeaders,
        queryString: sanitizedQuery,
        cookies: request.cookies || [], // cookies dihapus dari headers tapi tetap ada di request.cookies — hapus juga
        postData: sanitizedPostData,
        headersSize: request.headersSize,
        bodySize: request.bodySize,
      },
      response: {
        status: response.status,
        statusText: response.statusText,
        httpVersion: response.httpVersion,
        headers: sanitizedResponseHeaders,
        content: response.content,
        redirectURL: response.redirectURL,
        headersSize: response.headersSize,
        bodySize: response.bodySize,
      },
      cache: rawEntry.cache,
      timings: rawEntry.timings,
      serverIPAddress: rawEntry.serverIPAddress,
      connection: rawEntry.connection,
      _sanitized: true,
    }

    // Hapus cookies dari request (tidak disimpan dalam headers atau cookies)
    entry.request.cookies = []

    entries.push(entry)

    const classification = classifyEntry(rawEntry)

    // Sanitize URLs and response content for summary outputs
    const sanitizedRequestUrl = sanitizeUrl(request.url)
    const sanitizedResponseContent = sanitizeResponseContent(response.content)
    const sanitizedRedirectUrl = sanitizeUrl(response.redirectURL)

    switch (classification) {
      case 'api':
        apiCalls.push({
          url: sanitizedRequestUrl,
          method: request.method,
          status: response.status,
          mimeType: sanitizedResponseContent && sanitizedResponseContent.mimeType,
          time: rawEntry.time,
        })
        break
      case 'html':
        htmlPages.push({
          url: sanitizedRequestUrl,
          status: response.status,
          mimeType: sanitizedResponseContent && sanitizedResponseContent.mimeType,
          time: rawEntry.time,
        })
        break
      case 'media':
        mediaUrls.push({
          url: sanitizedRequestUrl,
          type: sanitizedResponseContent && sanitizedResponseContent.mimeType,
          status: response.status,
          time: rawEntry.time,
        })
        break
      case 'redirect':
        redirectChains.push({
          from: sanitizedRequestUrl,
          to: sanitizedRedirectUrl || '',
          status: response.status,
        })
        break
    }
  })

  // Sanitize response content in entries
  entries.forEach((entry) => {
    entry.response.content = sanitizeResponseContent(entry.response.content)
    entry.request.url = sanitizeUrl(entry.request.url)
  })

  return { entries, apiCalls, htmlPages, mediaUrls, redirectChains, errors }
}

export { HEADER_KEYS_TO_REDACT, QUERY_KEYS_TO_REDACT, MAX_FILE_SIZE, REDACTED }
