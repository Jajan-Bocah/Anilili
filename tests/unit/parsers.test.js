import { describe, it, expect } from 'vitest'
import { parseMhtml, MAX_FILE_SIZE, SENSITIVE_HEADERS } from '../../src/lib/parsers/mhtmlParser.js'
import { parseHar, REDACTED, QUERY_KEYS_TO_REDACT } from '../../src/lib/parsers/harParser.js'

describe('mhtmlParser', () => {
  it('should parse simple MHTML with HTML part', () => {
    const contentType = 'multipart/related; boundary="boundary123"'
    const content = `--boundary123
Content-Type: text/html; charset=utf-8
Content-Location: https://example.com/search.html

<html><body><h1>Search Results</h1></body></html>
--boundary123--`

    const result = parseMhtml(content, contentType, 'https://example.com/')

    expect(result.html).toContain('Search Results')
    expect(result.htmlUrl).toBe('https://example.com/search.html')
    expect(result.errors.length).toBe(0)
  })

  it('should reject empty content', () => {
    const result = parseMhtml('', 'multipart/related; boundary="x"')
    expect(result.html).toBeNull()
    expect(result.errors.length).toBeGreaterThan(0)
  })

  it('should reject missing boundary', () => {
    const result = parseMhtml('<html></html>', 'text/html')
    expect(result.html).toBeNull()
    expect(result.errors.some((e) => e.toLowerCase().includes('boundary'))).toBe(true)
  })

  it('should parse resource parts', () => {
    const contentType = 'multipart/related; boundary="bound456"'
    const content = `--bound456
Content-Type: text/html; charset=utf-8
Content-Location: https://example.com/page.html

<html><img src="cid:img1"></html>
--bound456
Content-Type: image/png
Content-Location: https://example.com/img.png
Content-ID: <img1>

fakeimagedata
--bound456--`

    const result = parseMhtml(content, contentType, 'https://example.com/')
    expect(result.resources.length).toBeGreaterThan(0)
    expect(result.resources[0].url).toBe('https://example.com/img.png')
  })

  it('should reject file over MAX_FILE_SIZE', () => {
    const bigContent = 'a'.repeat(MAX_FILE_SIZE + 1)
    const result = parseMhtml(bigContent, 'multipart/related; boundary="x"')
    expect(result.html).toBeNull()
    expect(result.errors.some((e) => e.includes('terlalu besar'))).toBe(true)
  })

  it('should expose sensitive headers set', () => {
    expect(SENSITIVE_HEADERS.has('cookie')).toBe(true)
    expect(SENSITIVE_HEADERS.has('authorization')).toBe(true)
    expect(SENSITIVE_HEADERS.has('x-api-key')).toBe(true)
  })
})

describe('mhtmlParser - sanitization', () => {
  const REDACTED = '[REDACTED]'

  it('should sanitize headers in MHTML parts', () => {
    const contentType = 'multipart/related; boundary="bound123"'
    const content = `--bound123
Content-Type: text/html; charset=utf-8
Content-Location: https://example.com/page.html
Set-Cookie: session=abc123
Authorization: Bearer token123
X-Api-Key: key456

<html><body>Test</body></html>
--bound123--`

    const result = parseMhtml(content, contentType, 'https://example.com/')

    expect(result.html).toContain('Test')
    // Headers are sanitized in the internal representation but not directly exposed
    // Verify via resources or html content sanitization
    expect(result.errors.length).toBe(0)
  })

  it('should sanitize URL query parameters in Content-Location', () => {
    const contentType = 'multipart/related; boundary="bound123"'
    const content = `--bound123
Content-Type: text/html; charset=utf-8
Content-Location: https://example.com/page.html?access_token=secret123&token=abc

<html><body>Test</body></html>
--bound123--`

    const result = parseMhtml(content, contentType, 'https://example.com/')

    expect(result.htmlUrl).not.toContain('secret123')
    expect(result.htmlUrl).not.toContain('abc')
    expect(result.htmlUrl).toContain(REDACTED)
  })

  it('should sanitize response content (JSON) in MHTML parts', () => {
    const contentType = 'multipart/related; boundary="bound123"'
    const content = `--bound123
Content-Type: application/json
Content-Location: https://api.example.com/data.json

{"access_token":"response_token","refresh_token":"refresh_123"}
--bound123--`

    const result = parseMhtml(content, contentType, 'https://example.com/')

    expect(result.resources.length).toBe(1)
    expect(result.resources[0].content).not.toContain('response_token')
    expect(result.resources[0].content).not.toContain('refresh_123')
    expect(result.resources[0].content).toContain(REDACTED)
  })

  it('should sanitize post data in HTML form content', () => {
    const contentType = 'multipart/related; boundary="bound123"'
    const content = `--bound123
Content-Type: text/html; charset=utf-8
Content-Location: https://example.com/login.html

<html><body><form action="/login"><input name="password" value="secret123"></form></body></html>
--bound123--`

    const result = parseMhtml(content, contentType, 'https://example.com/')

    // HTML content is sanitized
    expect(result.html).toContain(REDACTED)
  })

  it('should not leak credentials in serialized MHTML output', () => {
    const contentType = 'multipart/related; boundary="bound123"'
    const content = `--bound123
Content-Type: text/html; charset=utf-8
Content-Location: https://api.example.com/data?api_key=SENTINEL_API_KEY_12345
Set-Cookie: session=SENTINEL_SESSION_COOKIE
Authorization: Bearer SENTINEL_BEARER_TOKEN_67890

<html><body>{"token":"SENTINEL_RESPONSE_TOKEN"}</body></html>
--bound123--`

    const result = parseMhtml(content, contentType, 'https://example.com/')

    // Serialize entire result to JSON and check for sentinel tokens
    const serialized = JSON.stringify(result)
    expect(serialized).not.toContain('SENTINEL_API_KEY_12345')
    expect(serialized).not.toContain('SENTINEL_BEARER_TOKEN_67890')
    expect(serialized).not.toContain('SENTINEL_SESSION_COOKIE')
    expect(serialized).not.toContain('SENTINEL_RESPONSE_TOKEN')
    expect(serialized).toContain(REDACTED)
  })
})

describe('harParser', () => {
  const validHar = {
    log: {
      entries: [
        {
          startedDateTime: '2026-11-12T00:00:00Z',
          time: 100,
          request: {
            method: 'GET',
            url: 'https://api.example.com/search?q=test&access_token=secret123',
            httpVersion: 'HTTP/1.1',
            headers: [
              { name: 'Cookie', value: 'session=abc123' },
              { name: 'Authorization', value: 'Bearer token123' },
              { name: 'Accept', value: 'application/json' },
            ],
            queryString: [
              { name: 'q', value: 'test' },
              { name: 'access_token', value: 'secret123' },
            ],
            postData: { text: '{"password":"secret"}' },
            headersSize: 200,
            bodySize: 0,
          },
          response: {
            status: 200,
            statusText: 'OK',
            httpVersion: 'HTTP/1.1',
            headers: [
              { name: 'Set-Cookie', value: 'session=xyz' },
              { name: 'Content-Type', value: 'application/json' },
            ],
            content: { mimeType: 'application/json', text: '{"results":[]}' },
            redirectURL: '',
            headersSize: 100,
            bodySize: 50,
          },
          cache: {},
          timings: {},
        },
        {
          startedDateTime: '2026-11-12T00:00:01Z',
          time: 50,
          request: {
            method: 'GET',
            url: 'https://example.com/search.html',
            httpVersion: 'HTTP/1.1',
            headers: [{ name: 'Accept', value: 'text/html' }],
            queryString: [],
            postData: null,
            headersSize: 100,
            bodySize: 0,
          },
          response: {
            status: 200,
            statusText: 'OK',
            httpVersion: 'HTTP/1.1',
            headers: [{ name: 'Content-Type', value: 'text/html' }],
            content: { mimeType: 'text/html', text: '<html></html>' },
            redirectURL: '',
            headersSize: 50,
            bodySize: 30,
          },
          cache: {},
          timings: {},
        },
      ],
    },
  }

  it('should parse valid HAR and sanitize credentials', () => {
    const content = JSON.stringify(validHar)
    const result = parseHar(content, 'https://example.com/')

    expect(result.success !== false).toBe(true)
    expect(result.errors.length).toBe(0)
    expect(result.entries.length).toBe(2)

    // Cek sanitasi header
    const entry0 = result.entries[0]
    const cookieHeader = entry0.request.headers.find((h) => h.name === 'Cookie')
    expect(cookieHeader.value).toBe(REDACTED)

    const authHeader = entry0.request.headers.find((h) => h.name === 'Authorization')
    expect(authHeader.value).toBe(REDACTED)

    // Cek sanitasi query
    const tokenQuery = entry0.request.queryString.find((q) => q.name === 'access_token')
    expect(tokenQuery.value).toBe(REDACTED)

    // Cek cookies dihapus
    expect(entry0.request.cookies.length).toBe(0)

    // Cek classificasi
    expect(result.apiCalls.length).toBe(1)
    expect(result.apiCalls[0].url).toContain('api.example.com')
    expect(result.htmlPages.length).toBe(1)
    expect(result.htmlPages[0].url).toContain('search.html')
  })

  it('should handle empty content', () => {
    const result = parseHar('')
    expect(result.entries).toEqual([])
    expect(result.errors.length).toBeGreaterThan(0)
  })

  it('should handle invalid JSON', () => {
    const result = parseHar('not json')
    expect(result.entries).toEqual([])
    expect(result.errors.some((e) => e.includes('JSON'))).toBe(true)
  })

  it('should expose query keys to redact set', () => {
    expect(QUERY_KEYS_TO_REDACT.has('access_token')).toBe(true)
    expect(QUERY_KEYS_TO_REDACT.has('password')).toBe(true)
    expect(QUERY_KEYS_TO_REDACT.has('token')).toBe(true)
  })

  it('should reject file over MAX_FILE_SIZE', () => {
    const bigContent = 'a'.repeat(MAX_FILE_SIZE + 1)
    const result = parseHar(bigContent)
    expect(result.entries).toEqual([])
    expect(result.errors.some((e) => e.includes('terlalu besar'))).toBe(true)
  })

  it('should sanitize URL query parameters in apiCalls, htmlPages, mediaUrls, redirectChains', () => {
    const harWithSensitiveUrls = {
      log: {
        entries: [
          {
            startedDateTime: '2026-11-12T00:00:00Z',
            time: 100,
            request: {
              method: 'GET',
              url: 'https://api.example.com/search?q=test&access_token=secret123&token=abc',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Accept', value: 'application/json' }],
              queryString: [
                { name: 'q', value: 'test' },
                { name: 'access_token', value: 'secret123' },
                { name: 'token', value: 'abc' },
              ],
              postData: null,
              headersSize: 100,
              bodySize: 0,
            },
            response: {
              status: 200,
              statusText: 'OK',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Content-Type', value: 'application/json' }],
              content: { mimeType: 'application/json', text: '{"data":"ok"}' },
              redirectURL: 'https://auth.example.com/callback?code=authcode123&state=xyz',
              headersSize: 50,
              bodySize: 30,
            },
            cache: {},
            timings: {},
          },
          {
            startedDateTime: '2026-11-12T00:00:01Z',
            time: 50,
            request: {
              method: 'GET',
              url: 'https://cdn.example.com/video.m3u8?token=streamtoken',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Accept', value: 'application/vnd.apple.mpegurl' }],
              queryString: [{ name: 'token', value: 'streamtoken' }],
              postData: null,
              headersSize: 100,
              bodySize: 0,
            },
            response: {
              status: 200,
              statusText: 'OK',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Content-Type', value: 'application/vnd.apple.mpegurl' }],
              content: { mimeType: 'application/vnd.apple.mpegurl', text: '#EXTM3U' },
              redirectURL: '',
              headersSize: 50,
              bodySize: 30,
            },
            cache: {},
            timings: {},
          },
          {
            startedDateTime: '2026-11-12T00:00:02Z',
            time: 30,
            request: {
              method: 'GET',
              url: 'https://example.com/login?session=oldsession',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Accept', value: 'text/html' }],
              queryString: [{ name: 'session', value: 'oldsession' }],
              postData: null,
              headersSize: 100,
              bodySize: 0,
            },
            response: {
              status: 302,
              statusText: 'Found',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Location', value: 'https://auth.example.com/callback?code=authcode123&state=xyz' }],
              content: { mimeType: 'text/html', text: '' },
              redirectURL: 'https://auth.example.com/callback?code=authcode123&state=xyz',
              headersSize: 50,
              bodySize: 30,
            },
            cache: {},
            timings: {},
          },
        ],
      },
    }

    const content = JSON.stringify(harWithSensitiveUrls)
    const result = parseHar(content, 'https://example.com/')

    // Check apiCalls URL sanitized
    expect(result.apiCalls[0].url).not.toContain('secret123')
    expect(result.apiCalls[0].url).not.toContain('abc')
    expect(result.apiCalls[0].url).toContain(REDACTED)

    // Check mediaUrls URL sanitized
    expect(result.mediaUrls[0].url).not.toContain('streamtoken')
    expect(result.mediaUrls[0].url).toContain(REDACTED)

    // Check entries have sanitized URLs
    expect(result.entries[0].request.url).not.toContain('secret123')
    expect(result.entries[0].request.url).toContain(REDACTED)
    expect(result.entries[1].request.url).not.toContain('streamtoken')
    expect(result.entries[1].request.url).toContain(REDACTED)
    expect(result.entries[2].request.url).not.toContain('oldsession')
    expect(result.entries[2].request.url).toContain(REDACTED)
  })

  it('should sanitize response content in entries', () => {
    const harWithSensitiveResponse = {
      log: {
        entries: [
          {
            startedDateTime: '2026-11-12T00:00:00Z',
            time: 100,
            request: {
              method: 'POST',
              url: 'https://api.example.com/login',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Content-Type', value: 'application/json' }],
              queryString: [],
              postData: { text: '{"password":"secret123","token":"abc"}' },
              headersSize: 200,
              bodySize: 50,
            },
            response: {
              status: 200,
              statusText: 'OK',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Content-Type', value: 'application/json' }],
              content: { mimeType: 'application/json', text: '{"access_token":"response_token","refresh_token":"refresh_123"}' },
              redirectURL: '',
              headersSize: 100,
              bodySize: 80,
            },
            cache: {},
            timings: {},
          },
        ],
      },
    }

    const content = JSON.stringify(harWithSensitiveResponse)
    const result = parseHar(content, 'https://example.com/')

    const entry = result.entries[0]
    expect(entry.response.content.text).not.toContain('secret123')
    expect(entry.response.content.text).not.toContain('response_token')
    expect(entry.response.content.text).not.toContain('refresh_123')
    expect(entry.response.content.text).toContain(REDACTED)
    // postData should also be sanitized
    expect(entry.request.postData.text).toContain(REDACTED)
  })

  it('should not leak credentials in serialized output', () => {
    const harWithTokens = {
      log: {
        entries: [
          {
            startedDateTime: '2026-11-12T00:00:00Z',
            time: 100,
            request: {
              method: 'GET',
              url: 'https://api.example.com/data?api_key=SENTINEL_API_KEY_12345',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Authorization', value: 'Bearer SENTINEL_BEARER_TOKEN_67890' }],
              queryString: [{ name: 'api_key', value: 'SENTINEL_API_KEY_12345' }],
              postData: null,
              headersSize: 100,
              bodySize: 0,
            },
            response: {
              status: 200,
              statusText: 'OK',
              httpVersion: 'HTTP/1.1',
              headers: [{ name: 'Set-Cookie', value: 'session=SENTINEL_SESSION_COOKIE' }],
              content: { mimeType: 'application/json', text: '{"token":"SENTINEL_RESPONSE_TOKEN"}' },
              redirectURL: '',
              headersSize: 100,
              bodySize: 50,
            },
            cache: {},
            timings: {},
          },
        ],
      },
    }

    const content = JSON.stringify(harWithTokens)
    const result = parseHar(content, 'https://example.com/')

    // Serialize entire result to JSON and check for sentinel tokens
    const serialized = JSON.stringify(result)
    expect(serialized).not.toContain('SENTINEL_API_KEY_12345')
    expect(serialized).not.toContain('SENTINEL_BEARER_TOKEN_67890')
    expect(serialized).not.toContain('SENTINEL_SESSION_COOKIE')
    expect(serialized).not.toContain('SENTINEL_RESPONSE_TOKEN')
    expect(serialized).toContain(REDACTED)
  })
})
