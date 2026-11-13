import { describe, expect, it } from 'vitest'
import { normalizeImportedProject } from '../../src/lib/import/importProject.js'

const sampleProvider = {
  schemaVersion: 1,
  providers: [{
    id: 'anichin',
    name: 'Anichin',
    endpoint: 'https://example.com',
    source: 'generic_html',
    category: 'donghua',
    priority: 0,
    enabled: true,
  }],
  workflows: { search: { name: 'search', steps: [] } },
  metadata: { name: 'Example', sourceUrl: 'https://example.com/provider.json' },
  security: { allowedDomains: ['example.com'] },
}

describe('normalizeImportedProject', () => {
  it('loads workflows and metadata from provider.json top level', () => {
    const project = normalizeImportedProject(sampleProvider)
    expect(project.workflows).toEqual(sampleProvider.workflows)
    expect(project.metadata).toEqual(sampleProvider.metadata)
    expect(project.security).toEqual(sampleProvider.security)
    expect(project.category).toBe('donghua')
  })

  it('loads a versioned bundle', () => {
    const project = normalizeImportedProject({ version: 1, project: {
      id: 'bundle', name: 'Bundle', endpoint: 'https://example.com', workflows: {},
    } })
    expect(project.name).toBe('Bundle')
    expect(project.samples).toEqual([])
  })

  it('rejects unsupported versions and malformed files', () => {
    expect(() => normalizeImportedProject({ version: 2, project: {} })).toThrow(/Versi bundel/)
    expect(() => normalizeImportedProject({ providers: [] })).toThrow(/provider/)
    expect(() => normalizeImportedProject({ unrelated: true })).toThrow(/Format tidak dikenal/)
    expect(() => normalizeImportedProject(null)).toThrow()
  })

  it('supports a legacy workflow field on the provider entry', () => {
    const { workflows, ...rest } = sampleProvider
    const project = normalizeImportedProject({
      ...rest,
      providers: [{ ...rest.providers[0], workflows }],
    })
    expect(project.workflows).toEqual(workflows)
  })
})
