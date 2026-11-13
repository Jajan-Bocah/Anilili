// Search/Episodes/Sources hasil inferensi otomatis dari sampel nyata → gerbang parser engine (runWorkflowOffline).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { inferRules } from '../../src/lib/analyzer/ruleInference.js'
import { buildInferredWorkflow, inferWorkflows } from '../../src/lib/generator/workflowInference.js'
import { generateProviderJson } from '../../src/lib/generator/providerGenerator.js'
import { runWorkflowOffline } from '../../src/lib/tester/offlineTester.js'

const rd = (d, n) => readFileSync(`tests/fixtures/real/${d}/${n}`, 'utf8')
const cfg = JSON.parse(readFileSync('tests/fixtures/real/provider-configs/anichin.json', 'utf8'))
const EP = 'https://anichin.moe'
const FILES = { search: 'search.htm', episodes: 'series.htm', sources: 'watch.htm' }
const GROUP = { search: 'search', episodes: 'episodes', sources: 'stream' }

const build = (name, dir = 'anichin', endpoint = EP) => {
  const rules = inferRules({ html: rd(dir, FILES[name]) })[GROUP[name]]
  return buildInferredWorkflow(name, rules, { endpoint })
}
const run = (name, wf) => runWorkflowOffline(name, wf, { html: rd('anichin', FILES[name]), endpoint: EP })
const officialUrls = (name) => runWorkflowOffline(name, cfg.workflows[name], { html: rd('anichin', FILES[name]), endpoint: EP })

describe('workflow otomatis Anichin (tanpa hardcode provider)', () => {
  it('search: output SEARCH diterima parser, mapping konsisten dengan saveAs, URL dari atribut href', () => {
    const { workflow, status } = build('search')
    expect(status).toBe('READY')
    expect(workflow.output.type).toBe('SEARCH')
    const saved = workflow.steps.map((s) => s.saveAs)
    for (const v of Object.values(workflow.output.mapping)) expect(saved).toContain(v)
    expect(workflow.steps[0].params).toMatchObject({ url: 'https://anichin.moe/', query: { s: '{{query}}' } })
    const urlStep = workflow.steps.find((s) => s.saveAs === workflow.output.mapping.urls)
    expect(urlStep.params).toMatchObject({ attribute: 'href', mode: 'ALL', input: 'search_document' })
    const r = run('search', workflow)
    expect(r.overallStatus).toBe('PASSED')
    expect(r.engine.items).toBe(officialUrls('search').engine.items)
    expect(r.engine.items).toBeGreaterThan(0)
    // Presisi vs config resmi: URL dan judul identik (urutan sama).
    expect(r.rawOutput.urls).toEqual(officialUrls('search').rawOutput.urls)
    expect(r.rawOutput.titles).toEqual(officialUrls('search').rawOutput.titles)
  })

  it('episodes: daftar episode sama dengan config resmi (jumlah dan URL)', () => {
    const { workflow } = build('episodes')
    expect(workflow.output.type).toBe('EPISODES')
    const r = run('episodes', workflow)
    const off = officialUrls('episodes')
    expect(r.overallStatus).toBe('PASSED')
    expect(r.engine.items).toBe(166)
    expect(r.engine.items).toBe(off.engine.items)
    expect(r.rawOutput.episodeUrls).toEqual(off.rawOutput.episodeUrls)
    expect(r.rawOutput.episodeNumbers).toEqual(off.rawOutput.episodeNumbers)
  })

  it('sources: iframe nyata dipetakan ke streams dan berisi URL', () => {
    const { workflow } = build('sources')
    expect(workflow.output).toEqual({ type: 'SOURCES', mapping: { streams: 'sources_streams' } })
    const r = run('sources', workflow)
    expect(r.overallStatus).toBe('PASSED')
    expect(r.engine.items).toBe(1)
    expect(JSON.stringify(r.rawOutput)).toMatch(/https?:\/\//)
  })

  it('bukti kurang → NEEDS_REVIEW tanpa workflow (tidak mengarang)', () => {
    const noQuery = inferRules({ html: rd('anichin', 'search.htm') }).search.filter((r) => r.id !== 'search-query')
    const r = buildInferredWorkflow('search', noQuery, { endpoint: EP })
    expect(r.workflow).toBeNull()
    expect(r.status).toBe('NEEDS_REVIEW')
    expect(r.reasons.join(' ')).toMatch(/query/i)
    expect(buildInferredWorkflow('servers', [], { endpoint: EP }).workflow).toBeNull()
    const emptySrc = inferRules({ html: '<html><body><p>x</p></body></html>' })
    expect(inferWorkflows(emptySrc, { endpoint: EP }).workflows).toEqual({})
  })

  it('edit user memengaruhi workflow: rule dinonaktifkan/selector diganti', () => {
    const rules = inferRules({ html: rd('anichin', 'search.htm') }).search
    const edited = rules.map((r) => (r.id === 'search-title' ? { ...r, value: 'h2.custom', attribute: null } : r))
    const { workflow } = buildInferredWorkflow('search', edited, { endpoint: EP })
    const t = workflow.steps.find((s) => s.saveAs === workflow.output.mapping.titles)
    expect(t.params.selector).toBe('h2.custom')
    expect(t.params.attribute).toBeUndefined()
    const off = rules.map((r) => (r.id === 'search-image' ? { ...r, enabled: false } : r))
    expect(buildInferredWorkflow('search', off, { endpoint: EP }).workflow.output.mapping.images).toBeUndefined()
  })

  it('provider.json end-to-end: valid, memuat output & sourceUrl terpisah dari endpoint', () => {
    const { workflows } = inferWorkflows(
      { search: inferRules({ html: rd('anichin', 'search.htm') }).search, episodes: inferRules({ html: rd('anichin', 'series.htm') }).episodes, stream: inferRules({ html: rd('anichin', 'watch.htm') }).stream },
      { endpoint: EP }
    )
    const gen = generateProviderJson({ name: 'Anichin', endpoint: EP, configSourceUrl: 'https://cfg.example/provider.json', source: 'generic_html', workflows })
    expect(gen.meta.errors).toEqual([])
    expect(gen.providerJson.metadata.sourceUrl).toBe('https://cfg.example/provider.json')
    expect(gen.providerJson.workflows.search.output.type).toBe('SEARCH')
    expect(Object.keys(gen.providerJson.workflows)).toEqual(['search', 'episodes', 'sources'])
  })
})

describe('anti-overfit: situs lain (Kuramanime) — tanpa kode khusus provider', () => {
  const KE = 'https://v20.kuramanime.ing'
  const html = rd('kuramanime', 'search.htm')

  it('search Kuramanime: 18 judul bersih (teks h5) + URL absolut/relatif nyata, tanpa teks sampah', () => {
    const rules = inferRules({ html }).search
    const r = buildInferredWorkflow('search', rules, { endpoint: KE })
    expect(r.status).toBe('READY')
    expect(r.workflow.steps[0].params).toMatchObject({ url: 'https://v20.kuramanime.ing/anime', query: { search: '{{query}}' } })
    const res = runWorkflowOffline('search', r.workflow, { html, endpoint: KE })
    expect(res.overallStatus).toBe('PASSED')
    expect(res.engine.items).toBe(18)
    expect(res.rawOutput.titles[0]).toBe('Cosmic Baton Girl Comet-san☆')
    expect(JSON.stringify(res.rawOutput.titles)).not.toMatch(/Loading|SELESAI/)
    expect(res.rawOutput.urls.every((u) => /\/anime\/\d+\//.test(u))).toBe(true)
  })

  it('episodes Kuramanime (sidebar komentar, nomor tak bersih) tidak dipaksa DETECTED', () => {
    const ep = inferRules({ html }).episodes.find((x) => x.id === 'episode-list')
    expect(ep.status).not.toBe('DETECTED')
    expect(buildInferredWorkflow('episodes', inferRules({ html }).episodes, { endpoint: KE }).workflow).toBeNull()
  })
})
