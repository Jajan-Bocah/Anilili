// @vitest-environment node
// SSRF (hostname seperti yang dihasilkan URL parser), batas ukuran response (streaming), timeout body, redirect.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import worker, { isPrivateIp } from '../../worker/index.js'

const host = (u) => new URL(u).hostname

describe('isPrivateIp — hostname hasil URL parser', () => {
  it.each([
    'https://127.0.0.1/', 'https://2130706433/', 'https://0x7f.1/', 'https://10.1.2.3/',
    'https://172.16.0.1/', 'https://172.31.255.255/', 'https://192.168.0.1/',
    'https://169.254.169.254/', 'https://0.0.0.0/', 'https://100.64.0.1/', 'https://100.127.255.255/',
    'https://198.18.0.1/',
    'https://[::1]/', 'https://[::]/', 'https://[fe80::1]/', 'https://[febf::1]/',
    'https://[fc00::1]/', 'https://[fd12:3456::1]/',
    'https://[::ffff:127.0.0.1]/', 'https://[::ffff:10.0.0.1]/', 'https://[::ffff:c0a8:1]/',
    'https://[64:ff9b::7f00:1]/',
    'https://localhost/', 'https://localhost./', 'https://foo.localhost/',
    'https://metadata.google.internal/', 'https://printer.local/',
    'https://10.0.0.1.nip.io/', 'https://127.0.0.1.sslip.io/',
  ])('memblokir %s', (u) => {
    expect(isPrivateIp(host(u))).toBe(true)
  })

  it.each([
    'https://8.8.8.8/', 'https://1.1.1.1/', 'https://172.15.0.1/', 'https://172.32.0.1/',
    'https://100.63.0.1/', 'https://100.128.0.1/', 'https://198.20.0.1/', 'https://169.253.0.1/',
    'https://[2606:4700:4700::1111]/', 'https://[::ffff:8.8.8.8]/', 'https://[::ffff:808:808]/',
    'https://example.com/', 'https://api.anilili.site/', 'https://notlocalhost.com/', 'https://internal.example.com/',
  ])('mengizinkan %s', (u) => {
    expect(isPrivateIp(host(u))).toBe(false)
  })
})

const envAllowingLiterals = {
  // IP literal sengaja ada di allowlist → yang memblokir HARUS pemeriksaan SSRF, bukan allowlist.
  SECRET_ALLOWED_DOMAINS: 'example.com,[::1],127.0.0.1,[::ffff:7f00:1],169.254.169.254',
  ACCESS_AUTH_MODE: 'disabled',
  RATE_LIMIT: { limit: async() => ({ success: true }) },
}
const post = (url) =>
  new Request('https://w.example/api/proxy', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ url }),
  })

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async() => new Response('should-not-be-called')))
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('Worker — SSRF diblokir oleh pemeriksaan IP (bukan allowlist) dan fetch tidak pernah dipanggil', () => {
  it.each(['https://[::1]/', 'https://127.0.0.1/', 'https://[::ffff:127.0.0.1]/', 'https://169.254.169.254/latest/meta-data'])(
    '403 + pesan SSRF untuk %s',
    async(u) => {
      const res = await worker.fetch(post(u), envAllowingLiterals)
      expect(res.status).toBe(403)
      expect((await res.json()).error).toMatch(/IP private/)
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('redirect ke IPv6 loopback diblokir walau ada di allowlist', async() => {
    fetch.mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: 'https://[::1]/admin' } }))
    const res = await worker.fetch(post('https://example.com/'), envAllowingLiterals)
    expect(res.status).toBe(403)
    expect((await res.json()).error).toMatch(/Redirect ke IP private/)
    expect(fetch).toHaveBeenCalledTimes(1) // hop ke ::1 tidak pernah di-fetch
  })
})

describe('Worker — batas ukuran response (streaming)', () => {
  const env = { ...envAllowingLiterals }

  it('menghentikan pembacaan di batas 1 MB untuk stream tanpa content-length (tidak membaca semuanya)', async() => {
    let pulls = 0
    let cancelled = false
    const chunk = new Uint8Array(64 * 1024)
    const body = new ReadableStream({
      pull(c) { pulls++; c.enqueue(chunk) }, // tak berujung
      cancel() { cancelled = true },
    }, { highWaterMark: 0 })
    fetch.mockResolvedValueOnce(new Response(body, { status: 200 }))
    const res = await worker.fetch(post('https://example.com/big'), env)
    expect(res.status).toBe(413)
    expect(cancelled).toBe(true)
    expect(pulls).toBeLessThan(40) // 1 MB = 16 chunk; jauh di bawah "tak berujung"
  })

  it('menolak dari header content-length tanpa membaca body', async() => {
    let pulled = false
    const body = new ReadableStream({ pull(c) { pulled = true; c.close() } }, { highWaterMark: 0 })
    fetch.mockResolvedValueOnce(new Response(body, { status: 200, headers: { 'content-length': '5000000' } }))
    const res = await worker.fetch(post('https://example.com/big'), env)
    expect(res.status).toBe(413)
    expect(pulled).toBe(false)
  })

  it('menerima body tepat 1 MB', async() => {
    fetch.mockResolvedValueOnce(new Response(new Uint8Array(1_048_576), { status: 200 }))
    const res = await worker.fetch(post('https://example.com/exact'), env)
    expect(res.status).toBe(200)
  })

  it('body yang menetes lambat berakhir 504 setelah 30 detik (timer tidak dimatikan sebelum body dibaca)', async() => {
    vi.useFakeTimers()
    fetch.mockImplementationOnce(async(_u, opts) => {
      const body = new ReadableStream({
        pull() {
          return new Promise((_res, rej) =>
            opts.signal.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError'))))
        },
      }, { highWaterMark: 0 })
      return new Response(body, { status: 200 })
    })
    const p = worker.fetch(post('https://example.com/slow'), env)
    await vi.advanceTimersByTimeAsync(30_001)
    const res = await p
    expect(res.status).toBe(504)
  })
})

describe('Worker — batas ukuran REQUEST body (tanpa Content-Length)', () => {
  const env = { ...envAllowingLiterals }
  const streamReq = (chunks) => {
    const enc = new TextEncoder()
    let i = 0
    const body = new ReadableStream({
      pull(c) { i < chunks.length ? c.enqueue(enc.encode(chunks[i++])) : c.close() },
    }, { highWaterMark: 0 })
    return new Request('https://w.example/api/proxy', { method: 'POST', body, duplex: 'half' })
  }

  it('body chunked ±2 MB tanpa content-length → 413 dan fetch tidak dipanggil', async() => {
    const req = streamReq(['{"url":"https://example.com/","pad":"', 'x'.repeat(2_000_000), '"}'])
    expect(req.headers.get('content-length')).toBeNull()
    const res = await worker.fetch(req, env)
    expect(res.status).toBe(413)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('body chunked kecil yang valid tetap diterima', async() => {
    fetch.mockResolvedValueOnce(new Response('ok', { status: 200, headers: { 'content-type': 'text/plain' } }))
    const res = await worker.fetch(streamReq(['{"url":"https://exam', 'ple.com/x"}']), env)
    expect(res.status).toBe(200)
  })

  it.each([['body kosong', ''], ['JSON null', 'null'], ['JSON array/angka', '42'], ['bukan JSON', 'xx']])(
    '%s → 400, bukan crash',
    async(_n, raw) => {
      const res = await worker.fetch(streamReq([raw]), env)
      expect(res.status).toBe(400)
      expect(fetch).not.toHaveBeenCalled()
    },
  )
})

describe('Worker — batas redirect', () => {
  const env = { ...envAllowingLiterals }
  const redirect = (n) => new Response(null, { status: 302, headers: { location: `https://example.com/r${n}` } })

  it('5 redirect lalu 200 → diterima, redirectCount = 5', async() => {
    for (let i = 1; i <= 5; i++) fetch.mockResolvedValueOnce(redirect(i))
    fetch.mockResolvedValueOnce(new Response('done', { status: 200, headers: { 'content-type': 'text/plain' } }))
    const res = await worker.fetch(post('https://example.com/start'), env)
    expect(res.status).toBe(200)
    expect((await res.json()).redirectCount).toBe(5)
  })

  it('masih redirect setelah 5 hop → 429', async() => {
    fetch.mockImplementation(async() => redirect(0))
    const res = await worker.fetch(post('https://example.com/loop'), env)
    expect(res.status).toBe(429)
  })
})
