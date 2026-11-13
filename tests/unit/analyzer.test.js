import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { inferRules, RULE_STATUS } from '../../src/lib/analyzer/ruleInference.js'

// Fungsi helper untuk mengakses fungsi internal untuk test terpisah
function createDom(html) {
  const parser = new DOMParser()
  return parser.parseFromString(html, 'text/html')
}

describe('ruleInference', () => {
  describe('analyzeSearch (via inferRules)', () => {
    it('should detect search container with multiple detail links', () => {
      const dom = createDom(`
        <html><body>
          <div class="anime-list">
            <a href="/anime/naruto">Naruto</a>
            <a href="/anime/onepiece">One Piece</a>
            <a href="/anime/bleach">Bleach</a>
          </div>
        </body></html>
      `)
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const containerRule = result.search.find((r) => r.id === 'search-container')
      expect(containerRule.status).toBe(RULE_STATUS.DETECTED)
      expect(containerRule.confidence).toBeGreaterThanOrEqual(0.5)
      expect(containerRule.evidence.length).toBeGreaterThan(0)
    })

    it('should return NOT_DETECTED when too few links', () => {
      const dom = createDom(`
        <html><body><a href="/anime/naruto">Naruto</a></body></html>
      `)
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const containerRule = result.search.find((r) => r.id === 'search-container')
      expect(containerRule.status).toBe(RULE_STATUS.NOT_DETECTED)
      expect(containerRule.value).toBeNull()
    })
  })

  describe('episode detection (via inferRules)', () => {
    it('should detect episode list', () => {
      const dom = createDom(`
        <html><body>
          <div class="episodes">
            <a href="/episode/1">Episode 1</a>
            <a href="/episode/2">Episode 2</a>
            <a href="/episode/3">Episode 3</a>
          </div>
        </body></html>
      `)
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const listRule = result.episodes.find((r) => r.id === 'episode-list')
      expect(listRule.status).toBe(RULE_STATUS.DETECTED)
    })
  })

  describe('stream detection (via inferRules)', () => {
    it('should detect iframe player', () => {
      const dom = createDom(`
        <html><body><iframe src="https://player.example.com/watch/123"></iframe></body></html>
      `)
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const iframeRule = result.stream.find((r) => r.id === 'stream-iframe')
      expect(iframeRule.status).toBe(RULE_STATUS.DETECTED)
    })

    it('should return NOT_DETECTED when no iframe', () => {
      const dom = createDom('<html><body>No player</body></html>')
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const iframeRule = result.stream.find((r) => r.id === 'stream-iframe')
      expect(iframeRule.status).toBe(RULE_STATUS.NOT_DETECTED)
      expect(iframeRule.value).toBeNull()
    })
  })

  describe('inferRules summary', () => {
    it('should return correct summary counts', () => {
      const dom = createDom(`
        <html><body>
          <div class="anime-list">
            <a href="/anime/naruto">Naruto</a>
            <a href="/anime/onepiece">One Piece</a>
            <a href="/anime/bleach">Bleach</a>
          </div>
          <iframe src="https://player.example.com/watch/123"></iframe>
        </body></html>
      `)
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      expect(result.summary.total).toBeGreaterThan(0)
      expect(
        result.summary.detected +
          result.summary.notDetected +
          result.summary.needsReview
      ).toBe(result.summary.total)
    })

    it('should handle empty HTML', () => {
      const result = inferRules({ html: null }, 'https://example.com/')
      expect(result.summary.total).toBe(0)
      expect(result.summary.detected).toBe(0)
      expect(result.search).toEqual([])
      expect(result.episodes).toEqual([])
      expect(result.stream).toEqual([])
    })
  })

  describe('RULE_STATUS constants', () => {
    it('should have all status values', () => {
      expect(RULE_STATUS.DETECTED).toBe('DETECTED')
      expect(RULE_STATUS.NOT_DETECTED).toBe('NOT_DETECTED')
      expect(RULE_STATUS.NEEDS_MANUAL_REVIEW).toBe('NEEDS_MANUAL_REVIEW')
    })
  })

  describe('episode number data quality warnings', () => {
    // Helper: buat HTML dengan 3+ episode anchor yang lolos heuristik (href berisi "episode", nomor di child span).
    const makeEpHtml = (entries) => {
      const items = entries.map(({ href, num }) =>
        `<a href="${href}"><span class="epl-num">${num}</span></a>`
      ).join('')
      return `<html><body><div class="eps">${items}</div></body></html>`
    }

    it('should warn on duplicate episode numbers', () => {
      const dom = createDom(makeEpHtml([
        { href: '/episode/1', num: '1' },
        { href: '/episode/2', num: '2' },
        { href: '/episode/3', num: '2' }, // duplikat
      ]))
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const listRule = result.episodes.find((r) => r.id === 'episode-list')
      const allText = JSON.stringify(listRule)
      expect(allText).toMatch(/duplikat/)
    })

    it('should warn on episode number jumps >= 50', () => {
      const dom = createDom(makeEpHtml([
        { href: '/episode/1', num: '1' },
        { href: '/episode/2', num: '100' }, // lonjakan 99
        { href: '/episode/3', num: '101' },
      ]))
      const result = inferRules({ html: dom.documentElement.outerHTML }, 'https://example.com/')
      const listRule = result.episodes.find((r) => r.id === 'episode-list')
      const allText = JSON.stringify(listRule)
      expect(allText).toMatch(/Lonjakan/)
    })
  })
})

describe('analyzer pada fixture nyata (presisi vs config resmi Anichin)', () => {
  const rd = (n) => readFileSync(`tests/fixtures/real/anichin/${n}`, 'utf8')
  const cfg = JSON.parse(readFileSync('tests/fixtures/real/provider-configs/anichin.json', 'utf8'))
  const hrefs = (html, sel) => Array.from(new DOMParser().parseFromString(html, 'text/html').querySelectorAll(sel)).map((e) => e.getAttribute('href'))
  const byId = (rules, id) => rules.find((r) => r.id === id)

  it('search: selector otomatis memilih himpunan elemen yang sama dengan config resmi (precision = recall = 1)', () => {
    const html = rd('search.htm')
    const url = byId(inferRules({ html }).search, 'search-url')
    expect(url).toMatchObject({ status: 'DETECTED', attribute: 'href' })
    const official = cfg.workflows.search.steps.find((s) => s.params.attribute === 'href').params.selector
    expect(hrefs(html, url.value)).toEqual(hrefs(html, official))
    expect(byId(inferRules({ html }).search, 'search-title')).toMatchObject({ status: 'DETECTED', attribute: 'title' })
    expect(byId(inferRules({ html }).search, 'search-query')).toMatchObject({ value: 's', status: 'DETECTED' })
  })

  it('episodes: himpunan anchor episode sama dengan config resmi; nomor dan judul terpisah', () => {
    const html = rd('series.htm')
    const rules = inferRules({ html }).episodes
    const url = byId(rules, 'episode-url')
    const official = cfg.workflows.episodes.steps.find((s) => s.params.attribute === 'href').params.selector
    expect(url.status).toBe('DETECTED')
    expect(hrefs(html, url.value)).toEqual(hrefs(html, official))
    expect(byId(rules, 'episode-number').status).toBe('DETECTED')
    expect(byId(rules, 'episode-title').status).toBe('DETECTED')
  })

  it('watch: iframe ok.ru DETECTED (atribut src); dropdown mirror base64 TIDAK dipaksa DETECTED', () => {
    const rules = inferRules({ html: rd('watch.htm') }).stream
    expect(byId(rules, 'stream-iframe')).toMatchObject({ status: 'DETECTED', attribute: 'src' })
    expect(byId(rules, 'server-selector').status).toBe('NEEDS_MANUAL_REVIEW')
  })

  it('tidak memilih body/html root, nav/menu, atau widget jadwal sebagai wadah hasil', () => {
    for (const f of ['search.htm', 'series.htm', 'watch.htm']) {
      const all = Object.values(inferRules({ html: rd(f) })).flat().filter((r) => r && r.value && r.type === 'css-selector')
      for (const r of all) expect(r.value).not.toMatch(/^(html|body)\b|\b(menu|nav|navbar|footer|sidebar|schedule|jadwal)\b/i)
    }
  })

  it('skor confidence tidak melebihi 1', () => {
    for (const f of ['search.htm', 'series.htm', 'watch.htm']) {
      for (const r of Object.values(inferRules({ html: rd(f) })).flat().filter((x) => x && typeof x.confidence === 'number')) expect(r.confidence).toBeLessThanOrEqual(1)
    }
  })
})
