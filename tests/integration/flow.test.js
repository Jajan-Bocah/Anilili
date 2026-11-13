// Integration test: alur lengkap generate provider.json → validasi schema →
// laporan kompatibilitas 4-tier → export/import bundel proyek.
import { describe, it, expect } from 'vitest'
import { generateProviderJson, generateValidationReport } from '../../src/lib/generator/providerGenerator.js'
import { validateSchema } from '../../src/lib/tester/schemaValidator.js'

const baseProject = {
  name: 'Contoh Anime',
  id: 'contoh-anime',
  endpoint: 'https://contoh.com/provider.json',
  configSourceUrl: 'https://contoh.com/provider.json',
  source: 'generic_html',
  category: 'anime',
  languages: ['ind'],
  workflows: {
    search: {
      name: 'search',
      steps: [
        { op: 'http', params: { url: 'https://contoh.com/?s={{query}}' }, saveAs: 'resp' },
        {
          op: 'select',
          params: { input: 'resp', selector: '.anime-item', mode: 'ALL' },
          saveAs: 'searchResults',
        },
      ],
      output: { type: 'SEARCH', mapping: { items: 'searchResults' } },
    },
    episodes: {
      name: 'episodes',
      steps: [
        { op: 'http', params: { url: '{{url}}' }, saveAs: 'resp' },
        {
          op: 'select',
          params: { input: 'resp', selector: '.episode-item a', mode: 'ALL', attribute: 'href' },
          saveAs: 'episodeList',
        },
      ],
      output: { type: 'EPISODES', mapping: { items: 'episodeList' } },
    },
  },
}

describe('integration: generate → validate → report', () => {
  it('generate provider.json yang valid dan terstruktur', () => {
    const result = generateProviderJson(baseProject)
    expect(result.providerJson).not.toBeNull()
    expect(result.meta.valid).toBe(true)
    expect(result.providerJson.schemaVersion).toBe(1)
    expect(result.providerJson.providers.length).toBe(1)
    expect(result.providerJson.providers[0].id).toBe('contoh-anime')
    expect(result.providerJson.providers[0].endpoint).toBe('https://contoh.com/provider.json')
    expect(result.providerJson.workflows.search.steps.length).toBe(2)
    expect(result.providerJson.workflows.episodes.steps.length).toBe(2)
    // Tidak ada _generatorMeta di output
    expect(JSON.stringify(result.providerJson)).not.toContain('_generatorMeta')
  })

  it('schema validator terima provider.json hasil generate', () => {
    const result = generateProviderJson(baseProject)
    const schema = validateSchema(result.providerJson)
    expect(schema.valid).toBe(true)
    // Critical blocker engine sengaja dimasukkan sebagai failed, jadi failed > 0
    expect(schema.failed.length).toBeGreaterThan(0)
  })

  it('laporan kompatibilitas 4-tier tidak langsung ANILILI READY karena blocker engine', () => {
    const result = generateProviderJson(baseProject)
    const report = generateValidationReport(result.providerJson, {
      offline: { passed: true },
      live: { passed: true },
    })
    expect(report.valid).toBe(true)
    // Ada critical blocker dari engine (episode slug cache menyimpan pipeId), sehingga bukan ANILILI READY
    expect(report.criticalBlockers.length).toBeGreaterThan(0)
    expect(report.status).not.toBe('ANILILI_READY')
  })

  it('tolak project tanpa endpoint HTTPS/trust', () => {
    const bad = { ...baseProject, endpoint: 'http://contoh.com/provider.json' }
    const result = generateProviderJson(bad)
    expect(result.providerJson).toBeNull()
    expect(result.meta.valid).toBe(false)
    expect(result.meta.errors.some((e) => e.includes('HTTP memerlukan RemoteSourceTrust') || e.includes('HTTPS'))).toBe(true)
  })

  it('export/import bundel proyek preserved melalui JSON round-trip', () => {
    const result = generateProviderJson(baseProject)
    const bundle = JSON.stringify(result.providerJson)
    const parsed = JSON.parse(bundle)
    expect(parsed.schemaVersion).toBe(1)
    expect(parsed.providers[0].id).toBe('contoh-anime')
    expect(parsed.workflows.search.steps[1].op).toBe('select')
    expect(parsed.workflows.search.steps[1].params.selector).toBe('.anime-item')
  })

  it('workflow step dengan op tidak didukung ditolak generator', () => {
    const badWf = {
      ...baseProject,
      workflows: {
        search: {
          name: 'search',
          steps: [{ op: 'invalid_op', params: {}, saveAs: 'x' }],
          output: { type: 'RAW', mapping: {} },
        },
      },
    }
    const result = generateProviderJson(badWf)
    expect(result.providerJson).toBeNull() // generator now validates and rejects
    expect(result.meta.valid).toBe(false)
    expect(result.meta.errors.some((e) => e.includes('op'))).toBe(true)
  })
})
