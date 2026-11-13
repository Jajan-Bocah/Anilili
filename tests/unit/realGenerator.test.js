// Pipeline otomatis (sampel nyata → analyzer → workflowInference → provider.json) vs gerbang parser engine
// (runWorkflowOffline) — gerbang SAMA dengan baseline P2 (P2: output otomatis 0 item / FAILED untuk ketiganya).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { analyzeSamples, mergeAnalysis, autoAssignSamples } from '../../src/lib/samples/sampleMap.js'
import { inferWorkflows } from '../../src/lib/generator/workflowInference.js'
import { generateProviderJson, generateValidationReport } from '../../src/lib/generator/providerGenerator.js'
import { runMappedOffline, offlinePassedFlag } from '../../src/lib/samples/runMapped.js'
import { runWorkflowOffline } from '../../src/lib/tester/offlineTester.js'

const rd = (n) => readFileSync(`tests/fixtures/real/anichin/${n}`, 'utf8')
const cfg = JSON.parse(readFileSync('tests/fixtures/real/provider-configs/anichin.json', 'utf8'))
const EP = 'https://anichin.moe'
const samples = ['search.htm', 'series.htm', 'watch.htm'].map((fileName) => ({ fileName, html: rd(fileName) }))

const perSample = analyzeSamples(samples)
const inferredRules = mergeAnalysis(perSample)
const sampleMap = autoAssignSamples(perSample, inferredRules)
const { workflows, report } = inferWorkflows(inferredRules, { endpoint: EP })
const project = { name: 'Anichin', endpoint: EP, configSourceUrl: 'https://cfg.example/provider.json', source: 'generic_html', samples, inferredRules, sampleMap, workflows }
const gen = generateProviderJson(project)

describe('generator otomatis (sampel nyata Anichin) vs parser engine', () => {
  it('sampel dipetakan otomatis per workflow (bukan satu HTML untuk semua)', () => {
    expect(sampleMap).toEqual({ search: ['search.htm'], episodes: ['series.htm'], sources: ['watch.htm'], servers: [], homepage: [] })
  })

  it('provider.json valid; search/episodes/sources dihasilkan, servers NEEDS_REVIEW dengan alasan', () => {
    expect(gen.meta.errors).toEqual([])
    expect(Object.keys(gen.providerJson.workflows)).toEqual(['search', 'episodes', 'sources'])
    expect(report.servers.status).toBe('NEEDS_REVIEW')
    expect(report.servers.reasons[0]).toMatch(/servers/)
  })

  it('gerbang parser: search & episodes & sources PASSED dengan item nyata (P2: 0 item/FAILED)', () => {
    const { perWorkflow } = runMappedOffline({ ...project, workflows: gen.providerJson.workflows })
    const by = Object.fromEntries(perWorkflow.map((r) => [r.name, r]))
    expect(by.search).toMatchObject({ overallStatus: 'PASSED', engine: { items: 2 }, sampleNames: ['search.htm'] })
    expect(by.episodes).toMatchObject({ overallStatus: 'PASSED', engine: { items: 166 }, sampleNames: ['series.htm'] })
    expect(by.sources).toMatchObject({ overallStatus: 'PASSED', engine: { items: 1 }, sampleNames: ['watch.htm'] })
    // URL iframe harus benar (bukan sekadar 1 item); ini bukti format, bukan klaim playable di Android.
    expect(by.sources.rawOutput.streams).toEqual(['https://ok.ru/videoembed/16438442330802'])
  })

  it('hasil identik dengan config resmi pada gerbang yang sama (search & episodes)', () => {
    const { perWorkflow } = runMappedOffline({ ...project, workflows: gen.providerJson.workflows })
    for (const [n, f] of [['search', 'search.htm'], ['episodes', 'series.htm']]) {
      const off = runWorkflowOffline(n, cfg.workflows[n], { html: rd(f), endpoint: EP })
      const auto = perWorkflow.find((r) => r.name === n)
      expect(auto.engine.items).toBe(off.engine.items)
      expect(auto.rawOutput).toMatchObject(Object.fromEntries(Object.entries(off.rawOutput).filter(([k]) => ['urls', 'titles', 'episodeUrls', 'episodeNumbers'].includes(k))))
    }
  })

  it('sources resmi tetap BLOCKED (base64 per item); mirror otomatis yang diaktifkan manual juga BLOCKED', () => {
    expect(runWorkflowOffline('sources', cfg.workflows.sources, { html: rd('watch.htm'), endpoint: EP })).toMatchObject({ overallStatus: 'BLOCKED' })
    const rules = inferredRules.stream.map((r) => (r.id === 'server-selector' ? { ...r, enabled: true } : { ...r, enabled: false }))
    const { workflows: wf } = inferWorkflows({ stream: rules }, { endpoint: EP })
    expect(wf.sources.output.mapping).toEqual({ streams: 'sources_streams', servers: 'sources_servers' })
    expect(runWorkflowOffline('sources', wf.sources, { html: rd('watch.htm'), endpoint: EP }).overallStatus).toBe('BLOCKED')
  })

  it('status kompatibilitas tidak pernah ANILILI_READY tanpa uji Android; JSON_VALID ≠ OFFLINE_PASSED', () => {
    const results = runMappedOffline({ ...project, workflows: gen.providerJson.workflows }).perWorkflow
    expect(offlinePassedFlag(results)).toBe(true)
    expect(generateValidationReport(gen.providerJson, { offline: { passed: null }, live: { passed: null } }).status).toBe('JSON_VALID')
    const st = generateValidationReport(gen.providerJson, { offline: { passed: true }, live: { passed: null } })
    expect(st.status).toBe('OFFLINE_PASSED')
    expect(st.criticalBlockers.map((b) => b.id)).toContain('android-playback-untested')
  })
})
