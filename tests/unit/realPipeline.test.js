// Pipeline nyata: config provider RESMI dari repo Anilili (tests/fixtures/real/provider-configs, commit di
// MANIFEST) + fixture HTML nyata tersanitasi → validateProviderJson → executeWorkflow (replay) → runtimeParsers.
// Hanya data nyata di sini; kasus sintetis ada di runtimeParsers.test.js. Bukan eksekusi JVM/Android.
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { validateProviderJson } from '../../src/lib/contract/engineContract.js'
import { executeWorkflow, STATUS } from '../../src/lib/tester/offlineTester.js'
import { parseEpisodesResult, parseSearchResults, parseSourcesResult } from '../../src/lib/contract/runtimeParsers.js'

const ROOT = resolve(import.meta.dirname, '../fixtures/real')
const read = (p) => readFileSync(resolve(ROOT, p), 'utf8')
const config = (n) => JSON.parse(read(`provider-configs/${n}.json`))
const anichin = config('anichin')
const kuramanime = config('kuramanime')

describe('config resmi Anilili lolos validator generator', () => {
  it('provenance MANIFEST cocok dengan isi file (sha256, commit)', () => {
    const m = JSON.parse(readFileSync(resolve(ROOT, 'MANIFEST.json'), 'utf8'))
    for (const [path, e] of Object.entries(m.providerConfigs)) {
      const buf = readFileSync(resolve(ROOT, '../../..', path))
      expect(createHash('sha256').update(buf).digest('hex')).toBe(e.sha256)
      expect(e.anililiCommit).toBe(m.anililiCommit)
    }
  })

  for (const [name, cfg] of [['anichin', anichin], ['kuramanime', kuramanime]]) {
    it(`${name}.json valid (0 error) — paritas validator dengan config yang dikirim engine`, () => {
      const r = validateProviderJson(cfg)
      expect(r.errors).toEqual([])
      expect(r.valid).toBe(true)
    })
  }
})

describe('Anichin — workflow resmi vs fixture nyata (replay)', () => {
  const run = (wfName, fixture, variables = {}) =>
    executeWorkflow(anichin.workflows[wfName], null, '', { fixtureHtml: read(fixture), variables })

  it('Search: 3 select paralel → titles/urls/images; engine memetakan 2 hasil', () => {
    const r = run('search', 'anichin/search.htm', { query: 'quanzhi' })
    expect(r.overallStatus).toBe(STATUS.PASSED)
    expect(r.replayed).toBe(true)
    expect(r.finalOutput.titles).toEqual(['Quanzhi Fashi Season 7', 'Quanzhi Fashi Season 6'])
    expect(r.finalOutput.urls).toEqual(['/quanzhi-fashi-season-7/', '/quanzhi-fashi-season-6/'])
    expect(r.finalOutput.images[0]).toMatch(/^\/wp-content\/uploads\/.*\.jpg$/)
    const { items, warnings } = parseSearchResults(r.rawOutput)
    expect(items).toHaveLength(2)
    expect(items[0]).toMatchObject({ title: 'Quanzhi Fashi Season 7', slugOrUrl: '/quanzhi-fashi-season-7/' })
    expect(warnings).toEqual([])
  })

  it('Detail/Episodes: 168 baris terpilih; engine membuang 2 non-numerik (HIATUS!/LIBUR!) → 166, terurut naik', () => {
    const r = run('episodes', 'anichin/series.htm', { url: 'https://anichin.moe/btth-season-5/' })
    expect(r.overallStatus).toBe(STATUS.PASSED)
    expect(r.finalOutput.episodeUrls).toHaveLength(168)
    const { items } = parseEpisodesResult(r.rawOutput, { providerId: 'anichin', anilistId: 7 })
    expect(r.finalOutput.episodeNumbers).toEqual(expect.arrayContaining(['HIATUS!', 'LIBUR!']))
    expect(items).toHaveLength(166) // nomor tanpa digit → NaN → dilewati parser engine (mapNotNull)
    expect(items[0].number).toBeLessThan(items[1].number)
    const ep218 = items.find((i) => i.url === '/btth-season-5-episode-218-subtitle-indonesia/')
    expect(ep218.number).toBe(218)
    expect(ep218.pipeId).toBe('remote/anichin/7/sub/' + ep218.url)
    // Label nyata "04 [56]" → regex engine [^0-9.] menghasilkan "0456" = 456 (nomor rusak, urutan salah).
    expect(r.finalOutput.episodeNumbers).toContain('04 [56]')
    expect(items.map((i) => i.number)).toContain(456)
    expect(items[items.length - 1].number).toBe(456)
  })

  it('Sources: 12 option mirror; engine menerima 11 (placeholder kosong dibuang) tetapi 0 berupa URL', () => {
    const r = run('sources', 'anichin/watch.htm', { url: 'https://anichin.moe/watch/' })
    expect(r.overallStatus).toBe(STATUS.PASSED)
    expect(r.finalOutput.streams).toHaveLength(12)
    expect(r.finalOutput.servers[0]).toBe('Pilih Server Video')
    const res = parseSourcesResult(r.rawOutput, { endpoint: anichin.providers[0].endpoint })
    expect(res.items).toHaveLength(11)
    expect(res.items.every((i) => i.type === 'embed')).toBe(true) // isMirrorDropdown
    expect(res.urlLike).toBe(0) // blob base64 (PGlmcmFt… = "<iframe"); tidak ada decode per item di engine
    expect(res.warnings.join(' ')).toMatch(/base64/)
  })

  it('Servers: config resmi tidak punya workflow servers → NOT_TESTED (tidak diklaim)', () => {
    expect(anichin.workflows.servers).toBeUndefined()
  })
})

describe('Kuramanime — keterbatasan fixture', () => {
  it('Episodes vs search.htm: selector a.ep-button = 0 (bukan halaman detail) → tidak ada episode, bukan PASS', () => {
    const r = executeWorkflow(kuramanime.workflows.episodes, null, '', { fixtureHtml: read('kuramanime/search.htm'), variables: { url: 'https://v20.kuramanime.ing/anime/x' } })
    expect(r.finalOutput.episodeUrls).toEqual([])
    expect(parseEpisodesResult(r.rawOutput).items).toEqual([])
  })
  it('Search: workflow JSON (need_json=true) — fixture HTML bukan respons JSON → parse_json gagal; HAR dibutuhkan', () => {
    const r = executeWorkflow(kuramanime.workflows.search, null, '', { fixtureHtml: read('kuramanime/search.htm'), variables: { query: 'x' } })
    expect(r.overallStatus).toBe(STATUS.FAILED)
    expect(r.stepResults[1].error).toMatch(/JSON parsing failed/)
  })
})
