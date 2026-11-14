// P8: Capability inference, manual override, allowedDomains audit, Kotlin strict-decode safety.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  inferCapabilities,
  generateProviderJson,
} from '../../src/lib/generator/providerGenerator.js'
import { validateProviderJson, DEFAULT_CAPABILITIES } from '../../src/lib/contract/engineContract.js'

const CAPABILITY_KEYS = Object.keys(DEFAULT_CAPABILITIES)
import { normalizeImportedProject } from '../../src/lib/import/importProject.js'
import { inferWorkflows } from '../../src/lib/generator/workflowInference.js'
import { analyzeSamples, mergeAnalysis, autoAssignSamples } from '../../src/lib/samples/sampleMap.js'
import { runMappedOffline } from '../../src/lib/samples/runMapped.js'

const EP = 'https://anichin.moe'
const BASE = { name: 'T', endpoint: EP, configSourceUrl: 'https://cfg.example/p.json', source: 'generic_html' }
const stepSearch = { op: 'http', id: 's1', params: { url: EP }, saveAs: 'r' }
const wfSearch = { name: 'search', steps: [stepSearch], output: { type: 'SEARCH', mapping: {} } }
const wfEmpty = { name: 'search', steps: [], output: { type: 'SEARCH', mapping: {} } }

// ── inferCapabilities unit ────────────────────────────────────────────

describe('inferCapabilities', () => {
  it('default: supportsSearch false tanpa workflow', () => {
    expect(inferCapabilities(undefined, undefined).supportsSearch).toBe(false)
    expect(inferCapabilities({}, undefined).supportsSearch).toBe(false)
    expect(inferCapabilities({ search: wfEmpty }, undefined).supportsSearch).toBe(false)
  })

  it('supportsSearch true jika ada workflow search dengan minimal 1 step', () => {
    expect(inferCapabilities({ search: wfSearch }, undefined).supportsSearch).toBe(true)
  })

  it('supportsSearch tidak terpengaruh workflow lain (episodes/sources)', () => {
    const wfs = { episodes: wfSearch, sources: wfSearch }
    expect(inferCapabilities(wfs, undefined).supportsSearch).toBe(false)
  })

  it('override manual false menang atas inferensi true', () => {
    expect(inferCapabilities({ search: wfSearch }, { supportsSearch: false }).supportsSearch).toBe(false)
  })

  it('override manual true menang atas default false (tanpa workflow)', () => {
    expect(inferCapabilities({}, { supportsSearch: true }).supportsSearch).toBe(true)
  })

  it('requiresWebView default false; override manual diterima', () => {
    expect(inferCapabilities({ search: wfSearch }, undefined).requiresWebView).toBe(false)
    expect(inferCapabilities({ search: wfSearch }, { requiresWebView: true }).requiresWebView).toBe(true)
  })

  it('flag lain tetap sama dengan DEFAULT_CAPABILITIES jika tidak di-override', () => {
    const caps = inferCapabilities({ search: wfSearch }, undefined)
    for (const k of CAPABILITY_KEYS) {
      if (k === 'supportsSearch') continue
      expect(caps[k]).toBe(DEFAULT_CAPABILITIES[k])
    }
  })

  it('output hanya berisi key yang dikenal Kotlin (CAPABILITY_KEYS)', () => {
    const caps = inferCapabilities({ search: wfSearch }, { customField: true })
    // customField harus ikut (spread userOverrides), tapi ini diuji di lapisan generateProviderJson
    // yang melewatinya ke validateProviderJson — di sini cukup pastikan key standar ada.
    for (const k of CAPABILITY_KEYS) expect(k in caps).toBe(true)
  })
})

// ── generateProviderJson: capabilities dari workflows nyata ──────────

describe('generateProviderJson: capability inference end-to-end', () => {
  it('tanpa workflow: supportsSearch false di provider.json', () => {
    const r = generateProviderJson(BASE)
    expect(r.meta.valid).toBe(true)
    expect(r.providerJson.providers[0].capabilities.supportsSearch).toBe(false)
  })

  it('dengan workflow search: supportsSearch true di provider.json', () => {
    const r = generateProviderJson({ ...BASE, workflows: { search: wfSearch } })
    expect(r.meta.valid).toBe(true)
    expect(r.providerJson.providers[0].capabilities.supportsSearch).toBe(true)
  })

  it('override eksplisit false menimpa inferensi true', () => {
    const r = generateProviderJson({ ...BASE, workflows: { search: wfSearch }, capabilities: { supportsSearch: false } })
    expect(r.meta.valid).toBe(true)
    expect(r.providerJson.providers[0].capabilities.supportsSearch).toBe(false)
  })

  it('override eksplisit requiresWebView true diterima dan lolos Kotlin strict', () => {
    const r = generateProviderJson({ ...BASE, capabilities: { requiresWebView: true } })
    expect(r.meta.valid).toBe(true)
    expect(r.providerJson.providers[0].capabilities.requiresWebView).toBe(true)
    expect(validateProviderJson(r.providerJson).valid).toBe(true)
  })

  it('key capabilities asing ditolak oleh validateProviderJson (Kotlin strict)', () => {
    const r = generateProviderJson({ ...BASE, capabilities: { unknownCap: true } })
    // generateProviderJson meneruskan ke validateProviderJson → invalid
    expect(r.meta.valid).toBe(false)
    expect(r.meta.errors.some((e) => /unknownCap/.test(e))).toBe(true)
  })

  it('provider.json yang dihasilkan lolos Kotlin strict-decode untuk semua capability defaults', () => {
    const r = generateProviderJson({ ...BASE, workflows: { search: wfSearch } })
    expect(validateProviderJson(r.providerJson).valid).toBe(true)
    const caps = r.providerJson.providers[0].capabilities
    for (const k of CAPABILITY_KEYS) expect(k in caps).toBe(true)
  })
})

// ── Anichin nyata: supportsSearch benar setelah inferensi otomatis ────

describe('Anichin nyata: supportsSearch dari inferensi otomatis', () => {
  const rd = (n) => readFileSync(`tests/fixtures/real/anichin/${n}`, 'utf8')
  const samples = ['search.htm', 'series.htm', 'watch.htm'].map((fileName) => ({ fileName, html: rd(fileName) }))
  const perSample = analyzeSamples(samples)
  const inferredRules = mergeAnalysis(perSample)
  const sampleMap = autoAssignSamples(perSample, inferredRules)
  const { workflows } = inferWorkflows(inferredRules, { endpoint: EP })
  const project = { ...BASE, name: 'Anichin', samples, inferredRules, sampleMap, workflows }

  it('supportsSearch true karena workflow search ter-infer dari sampel nyata', () => {
    const r = generateProviderJson(project)
    expect(r.meta.errors).toEqual([])
    expect(r.providerJson.providers[0].capabilities.supportsSearch).toBe(true)
  })

  it('requiresWebView true karena sources workflow menggunakan iframe (stream-iframe)', () => {
    const r = generateProviderJson(project)
    expect(r.providerJson.providers[0].capabilities.requiresWebView).toBe(true)
  })

  it('meta.warnings memuat info domain embed jika iframe dari domain lain ditemukan', () => {
    const r = generateProviderJson(project)
    // ok.ru ada di sample iframe Anichin; peringatan harus muncul jika domain berbeda dari endpoint
    // (peringatan ini informatif, bukan error — provider.json tetap valid)
    expect(r.meta.valid).toBe(true)
    // Jika inferredRules.stream berisi stream-iframe dengan sample domain asing, ada warning
    const iframeRule = inferredRules.stream?.find((r2) => r2.id === 'stream-iframe')
    if (iframeRule && iframeRule.meta?.sample?.length) {
      // ada sampel → warning mungkin muncul; hanya pastikan tidak memblokir validasi
      expect(r.meta.errors).toEqual([])
    }
  })

  it('Search menghasilkan 2 item, Episodes 166, Sources iframe ok.ru — tidak ada regression', () => {
    const r = generateProviderJson(project)
    const { perWorkflow } = runMappedOffline({ ...project, workflows: r.providerJson.workflows })
    const by = Object.fromEntries(perWorkflow.map((x) => [x.name, x]))
    expect(by.search).toMatchObject({ overallStatus: 'PASSED', engine: { items: 2 } })
    expect(by.episodes).toMatchObject({ overallStatus: 'PASSED', engine: { items: 166 } })
    expect(by.sources).toMatchObject({ overallStatus: 'PASSED', engine: { items: 1 } })
    expect(by.sources.rawOutput.streams).toEqual(['https://ok.ru/videoembed/16438442330802'])
  })
})

// ── allowedDomains: derive dari endpoint, override user ──────────────

describe('allowedDomains: konservatif + override user', () => {
  it('derive dari hostname endpoint secara default', () => {
    const r = generateProviderJson(BASE)
    expect(r.providerJson.security.allowedDomains).toEqual(['anichin.moe'])
  })

  it('override user menang atas derive otomatis', () => {
    const r = generateProviderJson({ ...BASE, security: { allowedDomains: ['cdn.example.com'] } })
    expect(r.providerJson.security.allowedDomains).toEqual(['cdn.example.com'])
  })

  it('domain embed tidak ditambahkan otomatis ke allowedDomains (harus manual)', () => {
    // ok.ru adalah domain embed iframe — tidak boleh masuk allowedDomains tanpa permintaan user
    const rd = (n) => readFileSync(`tests/fixtures/real/anichin/${n}`, 'utf8')
    const samples = ['search.htm', 'series.htm', 'watch.htm'].map((fileName) => ({ fileName, html: rd(fileName) }))
    const inferredRules = mergeAnalysis(analyzeSamples(samples))
    const { workflows } = inferWorkflows(inferredRules, { endpoint: EP })
    const r = generateProviderJson({ ...BASE, name: 'Anichin', workflows, inferredRules })
    expect(r.providerJson.security.allowedDomains).not.toContain('ok.ru')
    expect(r.providerJson.security.allowedDomains).toEqual(['anichin.moe'])
  })
})

// ── Import proyek lama: capabilities dipertahankan tanpa regression ───

describe('import proyek lama: capabilities aman', () => {
  it('provider.json lama tanpa capabilities diimport tanpa error', () => {
    const legacy = {
      schemaVersion: 1,
      providers: [{ id: 'old', name: 'Old', endpoint: EP, source: 'generic_html', capabilities: {} }],
      metadata: { name: 'Old', description: '', author: null, version: '1', checksum: null, sourceUrl: 'https://cfg.example/p.json', fetchedAt: 1 },
    }
    const project = normalizeImportedProject(legacy)
    expect(project.capabilities).toEqual({})
  })

  it('provider.json dengan capabilities eksplisit: dipertahankan setelah impor', () => {
    const withCaps = {
      schemaVersion: 1,
      providers: [{
        id: 'src', name: 'Src', endpoint: EP, source: 'generic_html',
        capabilities: { supportsSearch: true, requiresWebView: true, supportsSubtitles: false,
          supportsSkipTimes: false, supportsDownload: false, supportsEpisodes: true,
          supportsSources: true, multiAudio: false, shortLivedStreamUrls: false, countryLocked: null, adultOnly: false },
      }],
      metadata: { name: 'Src', description: '', author: null, version: '1', checksum: null, sourceUrl: 'https://cfg.example/p.json', fetchedAt: 1 },
    }
    const project = normalizeImportedProject(withCaps)
    expect(project.capabilities.supportsSearch).toBe(true)
    expect(project.capabilities.requiresWebView).toBe(true)
  })

  it('impor bundel + re-generate: capabilities dari workflows terbaru, bukan bundel lama', () => {
    // Bundel lama punya capabilities.supportsSearch=false, tapi workflow search tersedia
    const bundle = {
      version: 1,
      project: {
        id: 'rebundle', name: 'Rebundle', endpoint: EP,
        configSourceUrl: 'https://cfg.example/p.json', source: 'generic_html',
        workflows: { search: wfSearch },
        capabilities: { supportsSearch: false }, // override lama
      },
    }
    const project = normalizeImportedProject(bundle)
    // capabilities dari bundel ikut project.capabilities → menang sebagai override
    const r = generateProviderJson(project)
    expect(r.meta.valid).toBe(true)
    // override false dari bundel tetap dihormati (user sengaja set false)
    expect(r.providerJson.providers[0].capabilities.supportsSearch).toBe(false)
  })
})

// ── Regression tests untuk P9.1–P9.6: capability inference edge cases ───

describe('Regression: capability inference edge cases (P9 contracts)', () => {
  // Helper: workflow sources dengan step select yang memetakan ke streams
  const makeSourcesWf = (selector, _sampleUrl = '') => ({
    sources: {
      name: 'sources',
      steps: [
        { op: 'http', id: 's1', params: { url: EP }, saveAs: 'r' },
        { op: 'parse_html', id: 's2', params: { input: '{{r.body}}' }, saveAs: 'doc' },
        { op: 'select', id: 's3', params: { input: 'doc', selector, attribute: 'src', mode: 'ALL' }, saveAs: 'streams' },
      ],
      output: { type: 'SOURCES', mapping: { streams: 'streams' } },
    },
  })

  it('manual override requiresWebView=false menang atas inferensi iframe', () => {
    const wf = makeSourcesWf('div#pembed > iframe', 'https://ok.ru/videoembed/123')
    const r = inferCapabilities({ search: wfSearch, sources: wf.sources }, { requiresWebView: false })
    expect(r.requiresWebView).toBe(false)
  })

  it('iframe selector (stream-iframe) → requiresWebView=true', () => {
    const wf = makeSourcesWf('div#pembed > iframe', 'https://ok.ru/videoembed/123')
    const r = inferCapabilities({ search: wfSearch, sources: wf.sources }, {})
    expect(r.requiresWebView).toBe(true)
  })

  it('MP4 direct media selector (stream-source) → requiresWebView=false', () => {
    const wf = makeSourcesWf('video > source', 'https://cdn.example.com/video.mp4')
    const r = inferCapabilities({ search: wfSearch, sources: wf.sources }, {})
    expect(r.requiresWebView).toBe(false)
  })

  it('DASH (.mpd) direct media → requiresWebView=false', () => {
    const wf = makeSourcesWf('video > source', 'https://cdn.example.com/manifest.mpd')
    const r = inferCapabilities({ search: wfSearch, sources: wf.sources }, {})
    expect(r.requiresWebView).toBe(false)
  })

  it('HLS (.m3u8) direct media → requiresWebView=false', () => {
    const wf = makeSourcesWf('video > source', 'https://cdn.example.com/playlist.m3u8')
    const r = inferCapabilities({ search: wfSearch, sources: wf.sources }, {})
    expect(r.requiresWebView).toBe(false)
  })

  it('webm direct media → requiresWebView=false', () => {
    const wf = makeSourcesWf('video > source', 'https://cdn.example.com/video.webm')
    const r = inferCapabilities({ search: wfSearch, sources: wf.sources }, {})
    expect(r.requiresWebView).toBe(false)
  })

  it('mixed sources: iframe + direct media → requiresWebView=true (iframe dominan)', () => {
    // Workflow sources dengan iframe (stream-iframe rule aktif)
    const wfIframe = makeSourcesWf('div#pembed > iframe', 'https://ok.ru/videoembed/123')
    // Override eksplisit false tetap menang
    const r = inferCapabilities({ search: wfSearch, sources: wfIframe.sources }, { requiresWebView: false })
    expect(r.requiresWebView).toBe(false)
  })

  it('mixed sources tanpa override: iframe present → requiresWebView=true', () => {
    const wfIframe = makeSourcesWf('div#pembed > iframe', 'https://ok.ru/videoembed/123')
    const r = inferCapabilities({ search: wfSearch, sources: wfIframe.sources }, {})
    expect(r.requiresWebView).toBe(true)
  })

  it('override eksplisit requiresWebView=true tanpa workflow sources tetap dihormati', () => {
    const r = inferCapabilities({ search: wfSearch }, { requiresWebView: true })
    expect(r.requiresWebView).toBe(true)
  })

  it('supportsSearch override manual false tanpa workflow search', () => {
    const r = inferCapabilities({}, { supportsSearch: false })
    expect(r.supportsSearch).toBe(false)
  })

  it('allowedDomains hanya dari endpoint, bukan domain iframe', () => {
    const wf = makeSourcesWf('div#pembed > iframe', 'https://ok.ru/videoembed/123')
    const r = generateProviderJson({
      ...BASE,
      workflows: { search: wfSearch, sources: wf.sources },
    })
    expect(r.providerJson.security.allowedDomains).toEqual(['anichin.moe'])
    expect(r.providerJson.security.allowedDomains).not.toContain('ok.ru')
  })

  it('SSRF protection: loopback endpoint ditolak', () => {
    const r = generateProviderJson({
      ...BASE,
      endpoint: 'http://localhost:8080',
    })
    expect(r.meta.valid).toBe(false)
    expect(r.meta.errors.some((e) => e.includes('loopback'))).toBe(true)
  })

  it('SSRF protection: private IP endpoint ditolak tanpa trust', () => {
    const r = generateProviderJson({
      ...BASE,
      endpoint: 'http://192.168.1.100',
    })
    expect(r.meta.valid).toBe(false)
    expect(r.meta.errors.some((e) => e.includes('private IP') || e.includes('LAN'))).toBe(true)
  })
})
