import { describe, it, expect } from 'vitest'
import { parseSearchResults, parseEpisodesResult, parseSourcesResult, parseServers } from '../../src/lib/contract/runtimeParsers.js'

describe('runtimeParsers (port Kotlin)', () => {
  it('search: daftar paralel titles/urls/images → item', () => {
    const r = parseSearchResults({ titles: ['A', 'B'], urls: ['/a', '/b'], images: ['i1'] })
    expect(r.items.map((i) => i.slugOrUrl)).toEqual(['/a', '/b'])
    expect(r.items[0].image).toBe('i1')
  })
  it('search: slug kosong → warning', () => {
    expect(parseSearchResults([{ title: 'A' }]).warnings.join()).toMatch(/slug\/url kosong/)
  })
  it('search: output kosong/null → tanpa item', () => {
    expect(parseSearchResults(null).items).toEqual([])
    expect(parseSearchResults({ results: 'x' }).items).toEqual([])
  })
  it('episodes: daftar paralel dengan nomor ketat & urutan naik', () => {
    const r = parseEpisodesResult({ episodeUrls: ['/e2', '/e1'], episodeNumbers: ['2', '1'] }, { providerId: 'p', anilistId: 9 })
    expect(r.items.map((i) => i.number)).toEqual([1, 2])
    expect(r.items[0].pipeId).toBe('remote/p/9/sub//e1')
  })
  it('episodes: tanpa url terbaca → warning nomor-sebagai-url', () => {
    expect(parseEpisodesResult([{ number: '1' }, { number: '2' }]).warnings.join()).toMatch(/nomor sebagai url/)
  })
  it('sources: item Map (url/type/quality) dan hls', () => {
    const r = parseSourcesResult({ streams: [{ file: 'https://x/a.m3u8', label: '720p' }, { url: 'https://x/embed/1' }, { nope: 1 }] }, { endpoint: 'https://x' })
    expect(r.items.map((i) => i.type)).toEqual(['hls', 'embed'])
    expect(r.items[0].quality).toBe('720p')
    expect(r.urlLike).toBe(2)
  })
  it('sources: String tunggal → kosong + warning', () => {
    const r = parseSourcesResult({ streams: 'https://x/a.mp4' })
    expect(r.items).toEqual([])
    expect(r.warnings.join()).toMatch(/String tunggal/)
  })
  it('servers: Map diterima, String dibuang dengan warning', () => {
    expect(parseServers([{ name: 'S1', url: 'u' }]).items).toEqual([{ name: 'S1', url: 'u', quality: null }])
    const r = parseServers(['a', 'b'])
    expect(r.items).toEqual([])
    expect(r.warnings.join()).toMatch(/String/)
  })
})
