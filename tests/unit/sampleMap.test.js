import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { analyzeSamples, mergeAnalysis, autoAssignSamples, effectiveSampleMap, samplesForWorkflow, normalizeSampleMap } from '../../src/lib/samples/sampleMap.js'
import { inferWorkflows } from '../../src/lib/generator/workflowInference.js'
import { runMappedOffline, offlinePassedFlag } from '../../src/lib/samples/runMapped.js'
import { normalizeImportedProject } from '../../src/lib/import/importProject.js'

const rd = (n) => readFileSync(`tests/fixtures/real/anichin/${n}`, 'utf8')
const EP = 'https://anichin.moe'
const mk = (names) => names.map((fileName) => ({ fileName, html: rd(fileName.replace(/^copy-/, '')) }))
const build = (samples, extra = {}) => {
  const per = analyzeSamples(samples)
  const inferredRules = mergeAnalysis(per)
  const { workflows } = inferWorkflows(inferredRules, { endpoint: EP })
  return { name: 'A', endpoint: EP, samples, inferredRules, workflows, sampleMap: autoAssignSamples(per, inferredRules), ...extra }
}

describe('sampleMap', () => {
  it('banyak sampel per kategori didukung: dua halaman search → keduanya dijalankan', () => {
    const p = build(mk(['search.htm', 'copy-search.htm', 'series.htm', 'watch.htm']))
    expect(p.sampleMap.search.sort()).toEqual(['copy-search.htm', 'search.htm'])
    const { perSample, perWorkflow } = runMappedOffline(p)
    expect(perSample.filter((r) => r.name === 'search')).toHaveLength(2)
    expect(perWorkflow.find((r) => r.name === 'search').sampleNames).toHaveLength(2)
  })

  it('pilihan manual memengaruhi replay: sampel lain / tanpa sampel', () => {
    const p = build(mk(['search.htm', 'series.htm', 'watch.htm']))
    const auto = runMappedOffline(p).perWorkflow.find((r) => r.name === 'search')
    expect(auto.sampleNames).toEqual(['search.htm'])
    const swapped = runMappedOffline({ ...p, sampleMap: { ...p.sampleMap, search: ['series.htm'] } }).perWorkflow.find((r) => r.name === 'search')
    expect(swapped.sampleNames).toEqual(['series.htm'])
    expect(swapped.engine.items).not.toBe(auto.engine.items)
    const none = runMappedOffline({ ...p, sampleMap: { ...p.sampleMap, search: [] } })
    const r = none.perWorkflow.find((x) => x.name === 'search')
    expect(r).toMatchObject({ overallStatus: 'NOT_TESTED', noSample: true })
    expect(none.perSample.some((x) => x.name === 'search')).toBe(false)
    // Tanpa sampel ≠ gagal: bukan OFFLINE_FAILED, tetapi juga bukan lulus.
    expect(offlinePassedFlag(none.perWorkflow)).toBe(null)
  })

  it('proyek lama tanpa sampleMap dimigrasikan otomatis dari sampel + inferredRules', () => {
    const { sampleMap, ...old } = build(mk(['search.htm', 'series.htm', 'watch.htm']))
    expect(sampleMap).toBeDefined()
    expect(effectiveSampleMap(old)).toEqual({ search: ['search.htm'], episodes: ['series.htm'], sources: ['watch.htm'], servers: [], homepage: [] })
  })

  it('bundel: html dibuang tetapi pemetaan (fileName) bertahan; tanpa html → NOT_TESTED, tidak memakai HTML lain', () => {
    const p = build(mk(['search.htm', 'series.htm', 'watch.htm']))
    const bundle = JSON.parse(JSON.stringify({ version: 1, project: { ...p, id: 'x', samples: p.samples.map(({ fileName }) => ({ fileName })) } }))
    const imported = normalizeImportedProject(bundle)
    expect(imported.sampleMap).toEqual(p.sampleMap)
    expect(samplesForWorkflow(imported, 'search')).toEqual([])
    expect(runMappedOffline(imported).perWorkflow.every((r) => r.overallStatus === 'NOT_TESTED')).toBe(true)
  })

  it('impor tak tepercaya: sampleMap berbentuk salah dibuang; nama berkas tak dikenal dibersihkan', () => {
    const base = { version: 1, project: { id: 'x', name: 'A', endpoint: EP, samples: [{ fileName: 'a.htm' }] } }
    expect(normalizeImportedProject({ ...base, project: { ...base.project, sampleMap: { search: 'a.htm' } } }).sampleMap).toBeUndefined()
    expect(normalizeImportedProject({ ...base, project: { ...base.project, sampleMap: { search: ['a.htm'] } } }).sampleMap).toEqual({ search: ['a.htm'] })
    expect(normalizeSampleMap({ search: ['a.htm', 'ghost.htm'] }, [{ fileName: 'a.htm' }]).search).toEqual(['a.htm'])
  })
})
