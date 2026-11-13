// @vitest-environment node
// Verifikasi JWT Cloudflare Access memakai keypair sungguhan (bukan mock hasil verifikasi).
// JWKS di-stub lewat fetch; yang diuji adalah kode verifikasi di worker/index.js + jose.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { SignJWT, generateKeyPair, exportJWK } from 'jose'

const ISS = 'https://team.cloudflareaccess.com'
const AUD = 'aud-tag-123'
const env = {
  ACCESS_CERTS_URL: 'https://team.cloudflareaccess.com/cdn-cgi/access/certs',
  ACCESS_ISSUER: ISS,
  ACCESS_AUDIENCE: AUD,
}

let legit // { privateKey, publicJwk }
let attacker // keypair berbeda, kid sama

async function makeKey(alg, kid) {
  const { publicKey, privateKey } = await generateKeyPair(alg, { extractable: true })
  const publicJwk = { ...(await exportJWK(publicKey)), kid, alg, use: 'sig' }
  return { privateKey, publicJwk }
}

function sign(key, { alg = 'RS256', kid = 'k1', iss = ISS, aud = AUD, exp = '10m' } = {}) {
  return new SignJWT({ email: 'a@b.c' })
    .setProtectedHeader({ alg, kid })
    .setIssuer(iss)
    .setAudience(aud)
    .setIssuedAt()
    .setExpirationTime(exp)
    .sign(key.privateKey)
}

const b64 = (o) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

let verifyAccessJWT
let worker
let jwksFetches

beforeEach(async() => {
  legit ??= await makeKey('RS256', 'k1')
  attacker ??= await makeKey('RS256', 'k1')
  jwksFetches = 0
  vi.stubGlobal('fetch', vi.fn(async() => {
    jwksFetches++
    return new Response(JSON.stringify({ keys: [legit.publicJwk] }), { status: 200 })
  }))
  // jwksCache ada di level modul → reset modul agar tiap test mulai tanpa cache.
  vi.resetModules()
  const mod = await import('../../worker/index.js')
  verifyAccessJWT = mod.verifyAccessJWT
  worker = mod.default
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('verifyAccessJWT — token valid', () => {
  it('menerima token RS256 yang ditandatangani key di JWKS', async() => {
    const res = await verifyAccessJWT(await sign(legit), env)
    expect(res.valid).toBe(true)
    expect(res.payload.email).toBe('a@b.c')
  })

  it('menerima token ES256 bila key ES256 ada di JWKS', async() => {
    const ec = await makeKey('ES256', 'ec1')
    vi.stubGlobal('fetch', vi.fn(async() => new Response(JSON.stringify({ keys: [ec.publicJwk] }))))
    const res = await verifyAccessJWT(await sign(ec, { alg: 'ES256', kid: 'ec1' }), env)
    expect(res.valid).toBe(true)
  })
})

describe('verifyAccessJWT — forgery & serangan', () => {
  it('MENOLAK token dengan claim valid tetapi ditandatangani key penyerang (kid sama)', async() => {
    const forged = await sign(attacker) // iss/aud/exp semua benar, signature salah
    const res = await verifyAccessJWT(forged, env)
    expect(res.valid).toBe(false)
    expect(res.error).toMatch(/signature/i)
  })

  it('MENOLAK token yang signature-nya dirusak satu karakter', async() => {
    const t = await sign(legit)
    const tampered = t.slice(0, -4) + (t.endsWith('AAAA') ? 'BBBB' : 'AAAA')
    expect((await verifyAccessJWT(tampered, env)).valid).toBe(false)
  })

  it('MENOLAK token yang payload-nya diubah setelah ditandatangani', async() => {
    const [h, , s] = (await sign(legit)).split('.')
    const evil = `${h}.${b64({ iss: ISS, aud: AUD, exp: 9999999999, email: 'admin@evil' })}.${s}`
    expect((await verifyAccessJWT(evil, env)).valid).toBe(false)
  })

  it('MENOLAK alg "none" (token tanpa signature)', async() => {
    const t = `${b64({ alg: 'none', typ: 'JWT', kid: 'k1' })}.${b64({ iss: ISS, aud: AUD, exp: 9999999999 })}.`
    const res = await verifyAccessJWT(t, env)
    expect(res.valid).toBe(false)
  })

  it('MENOLAK HS256 (alg confusion: public key dipakai sebagai secret)', async() => {
    const secret = new TextEncoder().encode(JSON.stringify(legit.publicJwk))
    const t = await new SignJWT({}).setProtectedHeader({ alg: 'HS256', kid: 'k1' })
      .setIssuer(ISS).setAudience(AUD).setExpirationTime('10m').sign(secret)
    const res = await verifyAccessJWT(t, env)
    expect(res).toEqual({ valid: false, error: 'unsupported_algorithm' })
  })

  it('MENOLAK kid yang tidak ada di JWKS', async() => {
    const res = await verifyAccessJWT(await sign(legit, { kid: 'tidak-dikenal' }), env)
    expect(res).toEqual({ valid: false, error: 'unknown_kid' })
  })

  it('MENOLAK token tanpa kid', async() => {
    const t = await new SignJWT({}).setProtectedHeader({ alg: 'RS256' })
      .setIssuer(ISS).setAudience(AUD).setExpirationTime('10m').sign(legit.privateKey)
    expect((await verifyAccessJWT(t, env)).error).toBe('unknown_kid')
  })

  it('MENOLAK token expired', async() => {
    const res = await verifyAccessJWT(await sign(legit, { exp: Math.floor(Date.now() / 1000) - 3600 }), env)
    expect(res.valid).toBe(false)
    expect(res.error).toBe('ERR_JWT_EXPIRED')
  })

  it('MENOLAK issuer yang salah', async() => {
    const res = await verifyAccessJWT(await sign(legit, { iss: 'https://evil.example' }), env)
    expect(res.valid).toBe(false)
    expect(res.error).toBe('ERR_JWT_CLAIM_VALIDATION_FAILED')
  })

  it('MENOLAK audience yang salah', async() => {
    const res = await verifyAccessJWT(await sign(legit, { aud: 'aud-lain' }), env)
    expect(res.valid).toBe(false)
    expect(res.error).toBe('ERR_JWT_CLAIM_VALIDATION_FAILED')
  })

  it.each([
    ['kosong', ''],
    ['bukan 3 segmen', 'abc.def'],
    ['signature kosong', 'aaa.bbb.'],
    ['header bukan JSON', 'bm90anNvbg.e30.sig'],
  ])('MENOLAK token malformed: %s', async(_n, tok) => {
    expect((await verifyAccessJWT(tok, env)).valid).toBe(false)
  })
})

describe('verifyAccessJWT — JWKS fail-closed', () => {
  it('menolak bila fetch JWKS gagal (jaringan)', async() => {
    vi.stubGlobal('fetch', vi.fn(async() => { throw new Error('net down') }))
    const res = await verifyAccessJWT(await sign(legit), env)
    expect(res.valid).toBe(false)
  })

  it('menolak bila JWKS balas non-200', async() => {
    vi.stubGlobal('fetch', vi.fn(async() => new Response('x', { status: 500 })))
    expect((await verifyAccessJWT(await sign(legit), env)).error).toBe('jwks_fetch_failed')
  })

  it('menolak bila JWKS kosong', async() => {
    vi.stubGlobal('fetch', vi.fn(async() => new Response(JSON.stringify({ keys: [] }))))
    expect((await verifyAccessJWT(await sign(legit), env)).error).toBe('jwks_empty')
  })

  it('cache JWKS dipakai ulang (satu fetch untuk dua verifikasi)', async() => {
    await verifyAccessJWT(await sign(legit), env)
    await verifyAccessJWT(await sign(legit), env)
    expect(jwksFetches).toBe(1)
  })
})

describe('Worker.fetch — auth wajib (end-to-end di level handler)', () => {
  const workerEnv = {
    ...env,
    SECRET_ALLOWED_DOMAINS: 'example.com',
    RATE_LIMIT: { limit: async() => ({ success: true }) },
  }
  const req = (token) => new Request('https://w.example/api/proxy', {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { 'Cf-Access-Jwt-Assertion': token } : {}) },
    body: JSON.stringify({ url: 'https://example.com/x' }),
  })

  it('401 tanpa token', async() => {
    expect((await worker.fetch(req(null), workerEnv)).status).toBe(401)
  })

  it('401 untuk token palsu (claim benar, signature key penyerang)', async() => {
    expect((await worker.fetch(req(await sign(attacker)), workerEnv)).status).toBe(401)
  })

  it('503 bila ACCESS_* tidak lengkap (fail-closed), walau token valid', async() => {
    const res = await worker.fetch(req(await sign(legit)), { ...workerEnv, ACCESS_AUDIENCE: '' })
    expect(res.status).toBe(503)
  })

  it('kunci rate limit dari klaim JWT terverifikasi: header identitas palsu & host berbeda tidak membuat bucket baru', async() => {
    const keys = []
    const spyEnv = {
      ...workerEnv,
      SECRET_ALLOWED_DOMAINS: 'example.com,other.example.org',
      RATE_LIMIT: { limit: async({ key }) => { keys.push(key); return { success: true } } },
    }
    vi.stubGlobal('fetch', vi.fn(async(url) => String(url).includes('/cdn-cgi/access/certs')
      ? new Response(JSON.stringify({ keys: [legit.publicJwk] }))
      : new Response('ok', { status: 200, headers: { 'content-type': 'text/plain' } })))
    const token = await sign(legit)
    const call = (target, spoof) => worker.fetch(new Request('https://w.example/api/proxy', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'Cf-Access-Jwt-Assertion': token,
        ...(spoof ? { 'cf-access-authenticated-email': spoof, 'cf-access-authenticated-identity': spoof } : {}),
      },
      body: JSON.stringify({ url: target }),
    }), spyEnv)
    await call('https://example.com/a', 'spoof-1@evil')
    await call('https://example.com/a', 'spoof-2@evil')
    await call('https://other.example.org/b', null)
    expect(keys).toEqual(['user:a@b.c', 'user:a@b.c', 'user:a@b.c'])
  })

  it('token valid lolos autentikasi dan request diteruskan ke target (fetch target dipanggil)', async() => {
    const calls = []
    vi.stubGlobal('fetch', vi.fn(async(url) => {
      calls.push(String(url))
      if (String(url).includes('/cdn-cgi/access/certs')) {
        return new Response(JSON.stringify({ keys: [legit.publicJwk] }))
      }
      return new Response('ok', { status: 200, headers: { 'content-type': 'text/plain' } })
    }))
    const res = await worker.fetch(req(await sign(legit)), workerEnv)
    expect(res.status).toBe(200)
    expect(calls).toContain('https://example.com/x')
  })
})
