// runMapped.js — replay offline tiap workflow terhadap sampel yang DIPETAKAN untuk workflow itu (bukan satu HTML untuk semua).
import { runWorkflowOffline } from '../tester/offlineTester.js'
import { effectiveSampleMap, samplesForWorkflow } from './sampleMap.js'

const SEVERITY = { FAILED: 4, BLOCKED: 3, PARTIAL: 2, NOT_TESTED: 1, PASSED: 0 }
const NO_SAMPLE = 'Belum ada sampel HTML yang dipetakan untuk workflow ini (atau html tidak tersedia setelah impor) — unggah ulang/pilih sampel di Test Center. Tidak memakai HTML demo.'

/**
 * @returns {{ perSample: Array, perWorkflow: Array }} perSample: satu hasil per (workflow, sampel) + `sampleName`;
 *   perWorkflow: satu hasil per workflow (yang terburuk antar sampel) + `sampleNames`; tanpa sampel → NOT_TESTED, `noSample: true`.
 */
export function runMappedOffline(project, map = effectiveSampleMap(project)) {
  const perSample = []
  const perWorkflow = []
  for (const [name, wf] of Object.entries(project?.workflows || {})) {
    const samples = samplesForWorkflow(project, name, map)
    if (!samples.length) {
      perWorkflow.push({ name, overallStatus: 'NOT_TESTED', stepResults: [], engine: { items: 0, warnings: [], error: null }, noSample: true, note: NO_SAMPLE, sampleNames: [] })
      continue
    }
    const runs = samples.map((s) => ({ ...runWorkflowOffline(name, wf, { html: s.html, endpoint: project.endpoint || '' }), sampleName: s.fileName }))
    perSample.push(...runs)
    const worst = runs.reduce((a, b) => ((SEVERITY[b.overallStatus] ?? 0) > (SEVERITY[a.overallStatus] ?? 0) ? b : a))
    perWorkflow.push({ ...worst, sampleNames: runs.map((r) => r.sampleName) })
  }
  return { perSample, perWorkflow }
}

/** true = semua PASSED; false = ada yang gagal/diblokir/parsial; null = belum teruji (tanpa sampel) tanpa kegagalan. */
export function offlinePassedFlag(results) {
  if (!results.length) return null
  if (results.some((r) => r.overallStatus !== 'PASSED' && r.overallStatus !== 'NOT_TESTED')) return false
  return results.every((r) => r.overallStatus === 'PASSED') ? true : null
}
