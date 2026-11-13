/* global Buffer */
// Fixture nyata tersanitasi dari repo Android Anilili (lihat scripts/sanitize-fixtures.mjs).
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { JSDOM } from 'jsdom'

const ROOT = resolve(import.meta.dirname, '../..')
const manifest = JSON.parse(readFileSync(resolve(ROOT, 'tests/fixtures/real/MANIFEST.json'), 'utf8'))
const dom = (buf) => new JSDOM(buf.toString('utf8')).window.document

// Selector yang harus > 0 per file (fakta dari fixture; a.ep-button = 0 di semua file Anichin).
const MUST_BE_POSITIVE = {
  'anichin/search.htm': ['.listupd article.bs a.tip', 'img[src]'],
  'anichin/series.htm': ['.listupd article.bs a.tip', '.eplister li a', '.eplister li .epl-num', '.eplister li .epl-title'],
  'anichin/watch.htm': ['select.mirror option[value]', 'img[src]'],
}

describe('fixture nyata tersanitasi', () => {
  it('MANIFEST memuat 4 file dengan commit Anilili', () => {
    expect(Object.keys(manifest.files)).toHaveLength(4)
    expect(manifest.anililiCommit).toMatch(/^[0-9a-f]{40}$/)
  })

  for (const [path, m] of Object.entries(manifest.files)) {
    const rel = path.replace('tests/fixtures/real/', '')
    describe(rel, () => {
      const buf = readFileSync(resolve(ROOT, path))

      it('sha256 & ukuran sama dengan manifest', () => {
        expect(createHash('sha256').update(buf).digest('hex')).toBe(m.sha256Sanitized)
        expect(buf.length).toBe(m.bytesSanitized)
      })

      it('bersih dari script/noscript/style/komentar/handler on*', () => {
        const text = buf.toString('utf8')
        expect(text).not.toMatch(/<script/i)
        expect(text).not.toMatch(/<noscript/i)
        expect(text).not.toMatch(/<style/i)
        expect(text).not.toContain('<!--')
        expect(text).not.toMatch(/\son[a-z]+\s*=/i)
      })

      it('selector count sama dengan manifest (original == sanitized)', () => {
        const doc = dom(buf)
        for (const [sel, c] of Object.entries(m.selectorCounts)) {
          expect(c.original, sel).toBe(c.sanitized)
          expect(doc.querySelectorAll(sel).length, sel).toBe(c.sanitized)
        }
      })

      it('selector Anichin inti > 0', () => {
        const doc = dom(buf)
        for (const sel of MUST_BE_POSITIVE[rel] ?? []) {
          expect(doc.querySelectorAll(sel).length, sel).toBeGreaterThan(0)
        }
      })
    })
  }

  it('anichin/watch: nilai option select.mirror utuh dan base64 valid', () => {
    const doc = dom(readFileSync(resolve(ROOT, 'tests/fixtures/real/anichin/watch.htm')))
    const vals = [...doc.querySelectorAll('select.mirror option[value]')].map((o) => o.value).filter(Boolean)
    expect(vals.length).toBeGreaterThan(0)
    for (const v of vals) expect(Buffer.from(v, 'base64').toString('utf8')).toMatch(/<(iframe|div)/i)
  })

  it('kuramanime/search: HTML valid, halaman search (bukan detail)', () => {
    const doc = dom(readFileSync(resolve(ROOT, 'tests/fixtures/real/kuramanime/search.htm')))
    expect(doc.doctype?.name).toBe('html')
    expect(doc.documentElement.getAttribute('lang')).toBe('id')
    expect(doc.head).toBeTruthy()
    expect(doc.body.children.length).toBeGreaterThan(0)
    expect(doc.title.length).toBeGreaterThan(0)
    expect(doc.querySelectorAll('a.ep-button[href*="/episode/"]').length).toBe(0)
  })
})
