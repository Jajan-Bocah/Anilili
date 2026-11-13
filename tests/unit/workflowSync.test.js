import { describe, it, expect } from 'vitest'
import {
  applyInference, markManual, regenerateWorkflow, manualConflicts, originOf, sanitizeWorkflowOrigin,
} from '../../src/lib/generator/workflowSync.js'
import { normalizeImportedProject } from '../../src/lib/import/importProject.js'

const wf = (sel) => ({ name: 'search', version: '1.0', steps: [{ op: 'select', params: { selector: sel } }], output: { type: 'SEARCH', mapping: {} } })
const base = (extra = {}) => ({ name: 'P', id: 'p', endpoint: 'https://x.test', workflows: {}, ...extra })

describe('workflowSync', () => {
  it('menerapkan inferensi pada workflow baru dan menandainya auto', () => {
    const next = applyInference(base(), { search: wf('a') })
    expect(next.workflows.search).toEqual(wf('a'))
    expect(next.workflowOrigin).toEqual({ search: 'auto' })
  })

  it('memperbarui workflow auto ketika inferensi berubah, dan menghapusnya bila tak lagi terbukti', () => {
    let p = applyInference(base(), { search: wf('a') })
    p = applyInference(p, { search: wf('b') })
    expect(p.workflows.search).toEqual(wf('b'))
    p = applyInference(p, {})
    expect(p.workflows).toEqual({})
    expect(p.workflowOrigin).toEqual({})
  })

  it('TIDAK menimpa workflow manual saat inferensi berubah atau hilang', () => {
    let p = applyInference(base(), { search: wf('a') })
    p = markManual(p, 'search', wf('manual'))
    expect(originOf(p, 'search')).toBe('manual')
    expect(applyInference(p, { search: wf('b') })).toBe(p)
    expect(applyInference(p, {})).toBe(p)
  })

  it('workflow tanpa catatan origin (impor/proyek lama) dianggap manual; identik dengan inferensi → auto', () => {
    const imported = base({ workflows: { search: wf('legacy') } })
    expect(originOf(imported, 'search')).toBe('manual')
    expect(applyInference(imported, { search: wf('new') })).toBe(imported)
    const legacyAuto = base({ workflows: { search: wf('a') } })
    expect(applyInference(legacyAuto, { search: wf('a') }).workflowOrigin).toEqual({ search: 'auto' })
  })

  it('mengembalikan objek yang sama bila tidak ada perubahan (aman untuk setState)', () => {
    const p = applyInference(base(), { search: wf('a') })
    expect(applyInference(p, { search: wf('a') })).toBe(p)
  })

  it('manualConflicts hanya melaporkan workflow manual yang berbeda dari inferensi', () => {
    const p = markManual(base(), 'search', wf('manual'))
    expect(manualConflicts(p, { search: wf('auto') })).toEqual(['search'])
    expect(manualConflicts(p, { search: wf('manual') })).toEqual([])
    expect(manualConflicts(p, {})).toEqual([])
  })

  it('regenerateWorkflow (tindakan eksplisit) menimpa dan mengembalikan ke auto', () => {
    const p = markManual(base(), 'search', wf('manual'))
    const r = regenerateWorkflow(p, 'search', wf('auto'))
    expect(r.workflows.search).toEqual(wf('auto'))
    expect(originOf(r, 'search')).toBe('auto')
    expect(regenerateWorkflow(p, 'search', null)).toBe(p)
  })

  it('sanitizeWorkflowOrigin membuang nama tak dikenal, nilai tak sah, dan bentuk non-objek', () => {
    expect(sanitizeWorkflowOrigin({ search: 'auto', episodes: 'manual', x: 'auto', sources: 'hack' }, { search: {}, episodes: {}, sources: {} }))
      .toEqual({ search: 'auto', episodes: 'manual' })
    expect(sanitizeWorkflowOrigin('auto', {})).toEqual({})
    expect(sanitizeWorkflowOrigin(['auto'], { 0: {} })).toEqual({})
  })

  it('impor bundel mempertahankan origin yang sah dan membuang yang tidak sah', () => {
    const bundle = (origin) => ({ version: 1, project: { name: 'P', id: 'p', endpoint: 'https://x.test', workflows: { search: wf('m') }, workflowOrigin: origin } })
    expect(normalizeImportedProject(bundle({ search: 'manual', ghost: 'auto' })).workflowOrigin).toEqual({ search: 'manual' })
    expect(normalizeImportedProject(bundle('oops')).workflowOrigin).toEqual({})
  })
})
