// mhtmlParser.js — Parsing MIME multipart (MHTML/MHT) client-side.
// Tidak mengeksekusi script. Hanya manipulasi string/byte.
// RFC 2557: multipart/related dengan boundary dari Content-Type.

const MAX_FILE_SIZE = 100 * 1024 * 1024 // 100 MB batas memori

const SENSITIVE_HEADERS = new Set([
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

const SENSITIVE_QUERY_KEYS = new Set([
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
  if (!content || typeof content !== 'string') return content
  try {
    const parsed = JSON.parse(content)
    const sanitized = redactSensitiveFields(parsed)
    return JSON.stringify(sanitized)
  } catch {
    // Also sanitize HTML form fields (password, token, etc. in value attributes)
    return redactSensitiveInHtml(content)
  }
}

function redactSensitiveInHtml(html) {
  let result = html
  // Sanitize value attributes for sensitive field names
  const sensitiveAttrPatterns = [
    /(value\s*=\s*")([^"]*password[^"]*)"/gi,
    /(value\s*=\s*")([^"]*token[^"]*)"/gi,
    /(value\s*=\s*")([^"]*secret[^"]*)"/gi,
    /(value\s*=\s*")([^"]*api[_-]?key[^"]*)"/gi,
    /(value\s*=\s*")([^"]*session[^"]*)"/gi,
    /(value\s*=\s*")([^"]*auth[^"]*)"/gi,
    /(value\s*=\s*")([^"]*passwd[^"]*)"/gi,
    /(value\s*=\s*")([^"]*code[^"]*)"/gi,
  ]
  sensitiveAttrPatterns.forEach((pattern) => {
    result = result.replace(pattern, `$1${REDACTED}"`)
  })
  // Also apply text-based redaction for any remaining JSON-like content
  return redactSensitiveInText(result)
}

function redactSensitiveFields(obj) {
  if (Array.isArray(obj)) return obj.map(redactSensitiveFields)
  if (obj && typeof obj === 'object') {
    const result = {}
    for (const [key, value] of Object.entries(obj)) {
      if (SENSITIVE_QUERY_KEYS.has(key.toLowerCase())) {
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
 * Ekstrak boundary string dari header Content-Type multipart/related.
 * @param {string} contentType
 * @returns {string|null}
 */
function extractBoundary(contentType) {
  const match = contentType.match(/boundary="([^"]+)"/)
  if (match) return match[1]
  const match2 = contentType.match(/boundary=([^;\s]+)/)
  if (match2) return match2[1]
  return null
}

/**
 * Deteksi charset dari Content-Type.
 * @param {string} contentType
 * @returns {string}
 */
function detectCharset(contentType) {
  const match = contentType.match(/charset=([^;\s]+)/)
  return match ? match[1] : 'utf-8'
}

/**
 * Decode body berdasarkan Content-Transfer-Encoding.
 * @param {string} body
 * @param {string} encoding
 * @returns {string}
 */
function decodeTransferEncoding(body, encoding) {
  const enc = (encoding || '').toLowerCase().trim()
  switch (enc) {
    case 'base64':
      try {
        return atob(body)
      } catch {
        return body
      }
    case 'quoted-printable':
      return body
        .replace(/=\r?\n/g, '') // soft line break
        .replace(/=([0-9A-Fa-f]{2})/g, (_, hex) =>
          String.fromCharCode(parseInt(hex, 16))
        )
    case '7bit':
    case '8bit':
    case 'binary':
    case '':
    default:
      return body
  }
}

/**
 * Parse bagian header dari satu MIME part.
 * @param {string} rawPart
 * @returns {{ headers: Record<string,string>, body: string }}
 */
function parseMimePart(rawPart) {
  const headerEnd = rawPart.search(/\r?\n\r?\n/)
  if (headerEnd === -1) {
    return { headers: {}, body: rawPart }
  }

  const headerBlock = rawPart.slice(0, headerEnd)
  const body = rawPart.slice(headerEnd + 2) // skip \r\n\r\n or \n\n

  const headers = {}
  headerBlock.split(/\r?\n/).forEach((line) => {
    const colonIdx = line.indexOf(':')
    if (colonIdx === -1) return
    const key = line.slice(0, colonIdx).trim().toLowerCase()
    const value = line.slice(colonIdx + 1).trim()
    headers[key] = value
  })

  // Hapus trailing boundary marker dari body
  let cleanBody = body
  if (cleanBody.endsWith('--')) {
    cleanBody = cleanBody.slice(0, -2)
  }
  if (cleanBody.endsWith('\r\n')) {
    cleanBody = cleanBody.slice(0, -2)
  } else if (cleanBody.endsWith('\n')) {
    cleanBody = cleanBody.slice(0, -1)
  }

  return { headers, body: cleanBody }
}

/**
 * Parsing file MHTML/MHT.
 * @param {string} content — isi file sebagai teks
 * @param {string} contentType — Content-Type header utama (untuk boundary)
 * @param {string} baseUrl — URL asli untuk resolusi referensi
 * @returns {{
 *   html: string|null,
 *   htmlUrl: string|null,
 *   resources: Array<{url: string, contentType: string, content: string, blob: string}>,
 *   errors: string[]
 * }}
 */
export function parseMhtml(content, contentType, baseUrl = '') {
  const errors = []

  if (!content) {
    errors.push('Konten MHTML kosong')
    return { html: null, htmlUrl: null, resources: [], errors }
  }

  if (content.length > MAX_FILE_SIZE) {
    errors.push(`File terlalu besar (maksimal ${MAX_FILE_SIZE / (1024 * 1024)} MB)`)
    return { html: null, htmlUrl: null, resources: [], errors }
  }

  const boundary = extractBoundary(contentType)
  if (!boundary) {
    errors.push('Boundary MIME tidak ditemukan dalam Content-Type')
    return { html: null, htmlUrl: null, resources: [], errors }
  }

  const delimiter = `--${boundary}`
  const parts = content.split(delimiter)

  let html = null
  let htmlUrl = null
  const resources = []
  const contentIdMap = new Map() // Content-ID -> resource

  parts.forEach((part) => {
    // Lewati part pertama (biasanya kosong sebelum boundary pertama)
    // dan part terakhir (akhir dengan --)
    const trimmed = part.trim()
    if (!trimmed || trimmed === '--') return

    const { headers, body } = parseMimePart(trimmed)
    const partContentType = headers['content-type'] || ''
    const contentLocation = headers['content-location']
    const contentId = headers['content-id']
    const transferEncoding = headers['content-transfer-encoding']
    const charset = detectCharset(partContentType)

    const decodedBody = decodeTransferEncoding(body, transferEncoding)

    // Jika ini adalah part HTML
    if (partContentType.includes('text/html')) {
      html = sanitizeResponseContent(decodedBody)
      htmlUrl = contentLocation ? sanitizeUrl(contentLocation) : baseUrl
      return
    }

    // Jika ada Content-ID, simpan sebagai resource yang dapat direferensi
    if (contentId) {
      // Hapus kurung saringan dari Content-ID: <cid@example.com> -> cid@example.com
      const cleanCid = contentId.replace(/^[<]/, '').replace(/[>]$/, '')
      contentIdMap.set(cleanCid, {
        url: contentLocation ? sanitizeUrl(contentLocation) : cleanCid,
        contentType: partContentType,
        content: sanitizeResponseContent(decodedBody),
        charset,
      })
    }

    // Simpan resource (gambar, CSS, dll.)
    if (contentLocation) {
      resources.push({
        url: sanitizeUrl(contentLocation),
        contentType: partContentType,
        content: sanitizeResponseContent(decodedBody),
        charset,
      })
    }
  })

  // Resolusi referensi Content-ID (cid:) dalam HTML
  if (html) {
    let resolvedHtml = html
    contentIdMap.forEach((resource, cid) => {
      // Cari referensi cid: dalam src/href
      const cidPattern = new RegExp(`cid:${cid.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`, 'g')
      resolvedHtml = resolvedHtml.replace(cidPattern, resource.url)
    })
    html = resolvedHtml
  }

  // Resolusi relative URL dalam HTML terhadap baseUrl
  if (html && baseUrl) {
    // Catat resource URLs untuk referensi
    resources.forEach((r) => {
      const absUrl = new URL(r.url, baseUrl).href
      r.absoluteUrl = absUrl
    })
  }

  return { html, htmlUrl, resources, errors }
}

export { SENSITIVE_HEADERS, SENSITIVE_QUERY_KEYS, MAX_FILE_SIZE, REDACTED }
