// Cloudflare Worker — Live Test Proxy untuk Anilili Provider JSON Generator
// DISABLED by default. Aktif hanya dengan Cloudflare Access + server-side allowlist.
// Tidak ada shared secret di frontend. Tidak jadi open proxy publik.

import { jwtVerify, importJWK } from 'jose'

// Konfigurasi keamanan (diubah melalui wrangler.toml atau secrets, tidak di frontend)
const MAX_RESPONSE_BYTES = 1_048_576 // 1 MB
const MAX_REQUEST_BODY_BYTES = 1_048_576 // 1 MB request body cap
const TOTAL_TIMEOUT_MS = 30_000
const MAX_REDIRECTS = 5

// Algoritma yang diterima untuk verifikasi JWT Access.
// Cloudflare Access menandatangani dengan RSA (RS256/384/512) atau ECDSA (ES256/384).
const ALLOWED_JWT_ALGS = ['RS256', 'RS384', 'RS512', 'ES256', 'ES384', 'PS256']

// Cache JWKS di memori isolat selama 1 jam (JWKS dirotasi jarang).
const JWKS_CACHE_TTL_MS = 60 * 60 * 1000
let jwksCache = { keys: null, fetchedAt: 0 }

// ─── Cloudflare Access — verifikasi JWT kriptografis ──────────────────────────

/** Decode base64url → string UTF-8 (hanya untuk membaca header JWT). */
function decodeBase64UrlToString(segment) {
  const cleaned = segment.replace(/-/g, '+').replace(/_/g, '/')
  const padded = cleaned.padEnd(cleaned.length + ((4 - (cleaned.length % 4)) % 4), '=')
  const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

/**
 * Ambil JWKS publik Cloudflare Access dari `env.ACCESS_CERTS_URL`.
 * Cache per-isolat selama JWKS_CACHE_TTL_MS.
 */
async function getAccessJwks(env) {
  const now = Date.now()
  if (jwksCache.keys && now - jwksCache.fetchedAt < JWKS_CACHE_TTL_MS) {
    return jwksCache.keys
  }

  const certsUrl = env.ACCESS_CERTS_URL
  if (!certsUrl) throw new Error('jwks_url_missing')

  const res = await fetch(certsUrl, { headers: { accept: 'application/json' } })
  if (!res.ok) throw new Error('jwks_fetch_failed')
  const body = await res.json()
  const keys = Array.isArray(body?.keys) ? body.keys : []
  if (keys.length === 0) throw new Error('jwks_empty')
  jwksCache = { keys, fetchedAt: now }
  return keys
}

/**
 * Verifikasi kriptografis JWT Cloudflare Access.
 *
 * Memeriksa: signature (via JWKS publik), issuer, audience, expiry, dan algoritma.
 * Token palsu dengan exp/iss/aud valid tetap DITOLAK karena signature tidak cocok.
 *
 * @returns {Promise<{valid: true, payload: object} | {valid: false, error: string}>}
 */
export async function verifyAccessJWT(token, env) {
  if (!token || typeof token !== 'string') {
    return { valid: false, error: 'no_token' }
  }

  const parts = token.split('.')
  if (parts.length !== 3) return { valid: false, error: 'invalid_format' }
  if (!parts[1] || !parts[2]) return { valid: false, error: 'missing_signature' }

  let header
  try {
    header = JSON.parse(decodeBase64UrlToString(parts[0]))
  } catch {
    return { valid: false, error: 'invalid_header' }
  }

  // Reject `alg: none` dan algoritma di luar daftar putih.
  if (!header?.alg || !ALLOWED_JWT_ALGS.includes(header.alg)) {
    return { valid: false, error: 'unsupported_algorithm' }
  }

  let keys
  try {
    keys = await getAccessJwks(env)
  } catch (err) {
    return { valid: false, error: err.message || 'jwks_unavailable' }
  }

  // Cocokkan `kid` dengan JWKS. Key ID yang tidak dikenal = token dari issuer lain.
  const jwk = header.kid ? keys.find((k) => k.kid === header.kid) : null
  if (!jwk) return { valid: false, error: 'unknown_kid' }

  let publicKey
  try {
    publicKey = await importJWK(jwk, header.alg)
  } catch {
    return { valid: false, error: 'invalid_jwk' }
  }

  try {
    const { payload } = await jwtVerify(token, publicKey, {
      issuer: env.ACCESS_ISSUER,
      audience: env.ACCESS_AUDIENCE,
      algorithms: ALLOWED_JWT_ALGS,
      clockTolerance: 30,
    })
    return { valid: true, payload }
  } catch (err) {
    // jose: JWTExpired → 'expired', JWTClaimValidationFailed → 'claim_mismatch',
    // JWSSignatureVerificationFailed → 'signature_invalid', dll.
    return { valid: false, error: err.code || err.name || 'verify_failed' }
  }
}

/** Apakah autentikasi Access wajib untuk request ini. */
function isAuthRequired(env) {
  // Default: wajib. 'disabled' hanya untuk development lokal tanpa Cloudflare Access.
  return env.ACCESS_AUTH_MODE !== 'disabled'
}

function getClientIp(request) {
  const cf = request.cf
  if (cf && cf.clientAddress) return cf.clientAddress
  const forwarded = request.headers.get('x-forwarded-for')
  if (forwarded) return forwarded.split(',')[0].trim()
  return 'unknown'
}

// ─── SSRF protection ──────────────────────────────────────────────────────────

/**
 * Normalisasi hostname untuk deteksi SSRF.
 * - `URL.hostname` untuk IPv6 selalu berkurung siku ("[::1]") → kurung dibuang.
 * - Trailing dot ("localhost.") dibuang.
 * - 192.168.1.1.nip.io / 127.0.0.1.sslip.io me-resolve ke IP di balik nama domain,
 *   jadi diperlakukan sebagai literal IP.
 */
function normalizeHostnameForSSRF(hostname) {
  let h = String(hostname).toLowerCase().trim().replace(/^\[|\]$/g, '').replace(/\.$/, '')
  const nip = h.match(/^(.+)\.(nip\.io|sslip\.io)$/)
  if (nip) h = nip[1]
  return h
}

/** IPv4 dotted-quad: loopback, RFC 1918, link-local, CGNAT (RFC 6598), 0/8, benchmark. */
function isPrivateIpv4(octets) {
  const [a, b] = octets
  return (
    a === 0 || a === 10 || a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19))
  )
}

function isPrivateIp(hostname) {
  try {
    const h = normalizeHostnameForSSRF(hostname)

    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) {
      const octets = h.split('.').map(Number)
      // Octet > 255 tidak valid; URL parser tidak menghasilkannya → fail-closed.
      return octets.some((o) => o > 255) || isPrivateIpv4(octets)
    }

    if (h.includes(':')) {
      if (h === '::' || h === '::1') return true
      if (/^f[cd][0-9a-f]{2}:/.test(h)) return true // fc00::/7 (ULA)
      if (/^fe[89ab][0-9a-f]:/.test(h)) return true // fe80::/10 (link-local)
      // IPv4-mapped (::ffff:a.b.c.d / ::ffff:7f00:1) dan NAT64 (64:ff9b::/96):
      // URL parser menormalkan ke dua grup hex di akhir → dekode jadi IPv4.
      const embedded = h.match(/^(?:::ffff:|64:ff9b::)(?:(\d{1,3}(?:\.\d{1,3}){3})|([0-9a-f]{1,4}):([0-9a-f]{1,4}))$/)
      if (embedded) {
        if (embedded[1]) return isPrivateIp(embedded[1])
        const hi = parseInt(embedded[2], 16)
        const lo = parseInt(embedded[3], 16)
        return isPrivateIpv4([hi >> 8, hi & 255, lo >> 8, lo & 255])
      }
      return false
    }

    // shortcut: daftar nama internal statis; DNS rebinding tetap tidak terdeteksi (lihat SECURITY_AUDIT.md).
    return /(^|\.)(localhost|localdomain|internal|local)$/.test(h)
  } catch {
    return true
  }
}

function isAllowedDomain(hostname, allowedDomains) {
  if (allowedDomains.length === 0) return false
  return allowedDomains.some((d) => {
    const domain = d.toLowerCase().trim()
    const host = hostname.toLowerCase().trim()
    return host === domain || host.endsWith(`.${domain}`)
  })
}

function sanitizeResponseHeaders(headers) {
  const sanitized = new Headers()
  const sensitive = new Set([
    'set-cookie', 'set-cookie2', 'authorization', 'x-api-key',
    'x-auth-token', 'x-access-token', 'x-session-token',
  ])
  headers.forEach((value, key) => {
    if (!sensitive.has(key.toLowerCase())) {
      sanitized.set(key, value)
    }
  })
  return sanitized
}

function corsHeaders(env) {
  const headers = {
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  }
  const origin = env.CORS_ALLOWED_ORIGIN
  if (origin) {
    headers['Access-Control-Allow-Origin'] = origin
  }
  return headers
}

function jsonResponse(body, status, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

/**
 * Baca body maksimal `max` byte. Mengembalikan ArrayBuffer, atau null bila melebihi batas
 * (stream di-cancel segera, tidak membaca sisanya).
 */
async function readBodyCapped(response, max) {
  if (!response.body) return new ArrayBuffer(0)
  const reader = response.body.getReader()
  const chunks = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > max) {
      await reader.cancel().catch(() => {})
      return null
    }
    chunks.push(value)
  }
  const out = new Uint8Array(total)
  let offset = 0
  for (const c of chunks) {
    out.set(c, offset)
    offset += c.byteLength
  }
  return out.buffer
}

export { isPrivateIp, isAllowedDomain, sanitizeResponseHeaders }

export default {
  async fetch(request, env) {
    const headers = corsHeaders(env)

    // OPTIONS preflight (CORS)
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers })
    }

    // Fail-closed: jika allowlist tidak dikonfigurasi, tolak semua
    const allowedDomainsStr = env.SECRET_ALLOWED_DOMAINS || ''
    const allowedDomains = allowedDomainsStr
      .split(',')
      .map((d) => d.trim())
      .filter(Boolean)

    if (allowedDomains.length === 0) {
      return jsonResponse(
        { error: 'Live Test tidak dikonfigurasi. Atur SECRET_ALLOWED_DOMAINS di wrangler.toml/secrets.' },
        503,
        headers
      )
    }

    // Autentikasi Cloudflare Access — wajib secara default (fail-closed).
    let verifiedIdentity = null // dari klaim JWT yang sudah diverifikasi, bukan header request
    if (isAuthRequired(env)) {
      // Konfigurasi JWKS/issuer/audience wajib ada; tanpa itu Worker menolak semua request.
      if (!env.ACCESS_CERTS_URL || !env.ACCESS_ISSUER || !env.ACCESS_AUDIENCE) {
        return jsonResponse(
          { error: 'Autentikasi Access belum dikonfigurasi. Atur ACCESS_CERTS_URL, ACCESS_ISSUER, ACCESS_AUDIENCE.' },
          503,
          headers
        )
      }

      const jwtToken = request.headers.get('Cf-Access-Jwt-Assertion')
      if (!jwtToken) {
        return jsonResponse({ error: 'Akses ditolak: token Access tidak ada' }, 401, headers)
      }

      const jwtResult = await verifyAccessJWT(jwtToken, env)
      if (!jwtResult.valid) {
        return jsonResponse({ error: 'Akses ditolak' }, 401, headers)
      }
      verifiedIdentity = jwtResult.payload?.sub || jwtResult.payload?.email || null
    }

    // Hanya izinkan POST dengan body JSON
    if (request.method !== 'POST') {
      return jsonResponse({ error: 'Method tidak diizinkan' }, 405, headers)
    }

    // Pemeriksaan ukuran request body sebelum parse
    const contentLength = request.headers.get('content-length')
    if (contentLength !== null && Number(contentLength) > MAX_REQUEST_BODY_BYTES) {
      return jsonResponse({ error: 'Request body terlalu besar' }, 413, headers)
    }

    // Content-Length bisa tidak ada (chunked) atau bohong → batasi saat membaca, bukan hanya dari header.
    let rawBody
    try {
      rawBody = await readBodyCapped(request, MAX_REQUEST_BODY_BYTES)
    } catch {
      return jsonResponse({ error: 'Body JSON tidak valid' }, 400, headers)
    }
    if (rawBody === null) {
      return jsonResponse({ error: 'Request body terlalu besar' }, 413, headers)
    }
    let body
    try {
      body = JSON.parse(new TextDecoder().decode(rawBody))
    } catch {
      return jsonResponse({ error: 'Body JSON tidak valid' }, 400, headers)
    }
    if (!body || typeof body !== 'object') {
      return jsonResponse({ error: 'Body JSON tidak valid' }, 400, headers)
    }

    const { url, method = 'GET', headers: requestHeaders = {} } = body

    if (!url) {
      return jsonResponse({ error: 'url wajib diisi' }, 400, headers)
    }

    let targetUrl
    try {
      targetUrl = new URL(url)
    } catch {
      return jsonResponse({ error: 'URL tidak valid' }, 400, headers)
    }

    // SSRF protection: blokir private IP
    if (isPrivateIp(targetUrl.hostname)) {
      return jsonResponse(
        { error: 'Target URL mengarah ke IP private (SSRF protection)' },
        403,
        headers
      )
    }

    // Domain allowlist
    if (!isAllowedDomain(targetUrl.hostname, allowedDomains)) {
      return jsonResponse(
        { error: `Domain ${targetUrl.hostname} tidak dalam allowlist.` },
        403,
        headers
      )
    }

    // Hanya HTTPS
    if (targetUrl.protocol !== 'https:') {
      return jsonResponse({ error: 'Hanya HTTPS yang diizinkan' }, 403, headers)
    }

    // Rate limiting menggunakan Cloudflare Rate Limiting binding (RATE_LIMIT).
    // API binding: await env.RATE_LIMIT.limit({ key }) → { success: boolean }
    // Fail-closed: binding tidak tersedia / konfigurasi salah → 503.
    if (!env.RATE_LIMIT || typeof env.RATE_LIMIT.limit !== 'function') {
      return jsonResponse({ error: 'Rate limit belum dikonfigurasi.' }, 503, headers)
    }

    // Header cf-access-authenticated-* dibuat klien bila Worker dijangkau tanpa Access, jadi tidak
    // boleh jadi kunci. Per-identitas (bukan per-host) agar kuota tidak berlipat per domain allowlist.
    const rateLimitKey = verifiedIdentity ? `user:${verifiedIdentity}` : `ip:${getClientIp(request)}`

    let rateLimitResult
    try {
      rateLimitResult = await env.RATE_LIMIT.limit({ key: rateLimitKey })
    } catch {
      return jsonResponse({ error: 'Rate limit belum dikonfigurasi.' }, 503, headers)
    }

    if (!rateLimitResult || rateLimitResult.success !== true) {
      return jsonResponse({ error: 'Rate limit exceeded. Coba lagi nanti.' }, 429, headers)
    }

    // Siapkan request ke target
    const fetchHeaders = new Headers()
    const sensitiveRequestHeaders = new Set([
      'cookie', 'authorization', 'x-api-key', 'x-auth-token', 'x-access-token', 'x-session-token',
    ])
    Object.entries(requestHeaders).forEach(([key, value]) => {
      if (!sensitiveRequestHeaders.has(key.toLowerCase())) {
        fetchHeaders.set(key, value)
      }
    })

    const fetchOptions = {
      method,
      headers: fetchHeaders,
      redirect: 'manual',
    }

    if (method !== 'GET' && method !== 'HEAD' && body.body) {
      fetchOptions.body = JSON.stringify(body.body)
      fetchHeaders.set('content-type', 'application/json')
    }

    // Satu AbortController untuk timeout total seluruh alur redirect
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), TOTAL_TIMEOUT_MS)

    let response
    let currentUrl = targetUrl
    let redirectCount = 0

    try {
      response = await fetch(currentUrl.href, { ...fetchOptions, signal: controller.signal })
    } catch (err) {
      clearTimeout(timeoutId)
      if (err.name === 'AbortError') {
        return jsonResponse({ error: 'Request timeout' }, 504, headers)
      }
      return jsonResponse({ error: 'Target tidak dapat dihubungi' }, 502, headers)
    }

    // Redirect revalidation: setiap hop divalidasi ulang (HTTPS + allowlist)
    while (
      response.status >= 300 &&
      response.status < 400 &&
      redirectCount < MAX_REDIRECTS
    ) {
      const redirectUrl = response.headers.get('location')
      if (!redirectUrl) break

      let nextUrl
      try {
        nextUrl = new URL(redirectUrl, currentUrl.href)
      } catch {
        break
      }

      if (nextUrl.protocol !== 'https:') {
        clearTimeout(timeoutId)
        return jsonResponse({ error: 'Redirect ke non-HTTPS diblokir' }, 403, headers)
      }

      if (isPrivateIp(nextUrl.hostname)) {
        clearTimeout(timeoutId)
        return jsonResponse({ error: 'Redirect ke IP private diblokir' }, 403, headers)
      }

      if (!isAllowedDomain(nextUrl.hostname, allowedDomains)) {
        clearTimeout(timeoutId)
        return jsonResponse({ error: 'Redirect ke domain lain diblokir' }, 403, headers)
      }

      currentUrl = nextUrl
      redirectCount++

      try {
        response = await fetch(nextUrl.href, {
          method: 'GET',
          headers: fetchHeaders,
          redirect: 'manual',
          signal: controller.signal,
        })
      } catch {
        clearTimeout(timeoutId)
        return jsonResponse({ error: 'Target tidak dapat dihubungi' }, 502, headers)
      }
    }

    // Masih 3xx setelah batas hop → tolak. (Final 200 tepat di hop ke-5 tetap diterima.)
    if (redirectCount >= MAX_REDIRECTS && response.status >= 300 && response.status < 400) {
      clearTimeout(timeoutId)
      return jsonResponse({ error: 'Terlalu banyak redirect' }, 429, headers)
    }

    // Baca body bertahap dengan batas ukuran; timer 30s tetap aktif selama membaca,
    // sehingga body yang menetes lambat berakhir 504 (bukan menggantung).
    const tooLarge = () =>
      jsonResponse(
        { error: `Response terlalu besar (maksimal ${MAX_RESPONSE_BYTES / (1024 * 1024)} MB)` },
        413,
        headers
      )
    const declaredLength = Number(response.headers.get('content-length'))
    if (declaredLength > MAX_RESPONSE_BYTES) {
      clearTimeout(timeoutId)
      await response.body?.cancel().catch(() => {})
      return tooLarge()
    }

    let responseBody
    try {
      responseBody = await readBodyCapped(response, MAX_RESPONSE_BYTES)
    } catch (err) {
      clearTimeout(timeoutId)
      if (err.name === 'AbortError') return jsonResponse({ error: 'Request timeout' }, 504, headers)
      return jsonResponse({ error: 'Target tidak dapat dihubungi' }, 502, headers)
    }
    clearTimeout(timeoutId)
    if (responseBody === null) return tooLarge()

    const sanitizedHeaders = sanitizeResponseHeaders(response.headers)

    const result = {
      ok: response.ok,
      status: response.status,
      statusText: response.statusText,
      url: currentUrl.href,
      redirectCount,
      headers: Object.fromEntries(sanitizedHeaders.entries()),
      body: responseBody.byteLength > 0 ? Array.from(new Uint8Array(responseBody)) : null,
      bodyText:
        responseBody.byteLength > 0 &&
        sanitizedHeaders.get('content-type')?.includes('text/')
          ? new TextDecoder().decode(responseBody)
          : null,
    }

    return new Response(JSON.stringify(result), {
      status: 200,
      headers: { 'Content-Type': 'application/json', ...headers },
    })
  },
}
