import { describe, it, expect } from 'vitest'
import {
  generateProviderJson,
  generateValidationReport,
} from '../../src/lib/generator/providerGenerator.js'

describe('providerGenerator', () => {
  const baseProject = {
    id: 'test-provider',
    name: 'Test Provider',
    endpoint: 'https://example.com/provider.json',
    configSourceUrl: 'https://example.com/provider.json',
    source: 'generic_html',
    description: 'Test description',
    version: '1.0.0',
  }

  it('should generate valid provider.json', () => {
    const result = generateProviderJson(baseProject)

    expect(result.providerJson).not.toBeNull()
    expect(result.meta.valid).toBe(true)
    expect(result.providerJson.schemaVersion).toBe(1)
    expect(result.providerJson.providers.length).toBe(1)
    expect(result.providerJson.providers[0].id).toBe('test-provider')
    expect(result.providerJson.providers[0].endpoint).toBe(
      'https://example.com/provider.json'
    )
    expect(result.providerJson.providers[0].source).toBe('generic_html')
    expect(result.providerJson.metadata.name).toBe('Test Provider')
    expect(result.providerJson.metadata.version).toBe('1.0.0')
  })

  it('should reject project without name', () => {
    const result = generateProviderJson({ ...baseProject, name: '' })
    expect(result.providerJson).toBeNull()
    expect(result.meta.valid).toBe(false)
    expect(result.meta.errors.some((e) => e.includes('name'))).toBe(true)
  })

  it('should reject HTTP endpoint without trust', () => {
    const result = generateProviderJson({
      ...baseProject,
      endpoint: 'http://example.com/p.json',
    })
    expect(result.providerJson).toBeNull()
    expect(result.meta.valid).toBe(false)
    expect(result.meta.errors.some((e) => e.includes('HTTP memerlukan RemoteSourceTrust') || e.includes('HTTPS'))).toBe(true)
  })

  it('should reject invalid source', () => {
    const result = generateProviderJson({
      ...baseProject,
      source: 'invalid_source',
    })
    expect(result.providerJson).toBeNull()
    expect(result.meta.valid).toBe(false)
  })

  it('should generate workflows correctly', () => {
    const result = generateProviderJson({
      ...baseProject,
      workflows: {
        search: {
          name: 'Search',
          steps: [
            { op: 'http', params: { url: 'https://example.com/search' }, saveAs: 'search_response' },
            { op: 'select', params: { selector: '.anime-item', input: 'search_response' }, saveAs: 'results' },
          ],
          output: { type: 'SEARCH', mapping: { results: 'results' } },
        },
      },
    })

    expect(result.providerJson.workflows.search.steps.length).toBe(2)
    expect(result.providerJson.workflows.search.steps[0].op).toBe('http')
    expect(result.providerJson.workflows.search.steps[0].saveAs).toBe('search_response')
    expect(result.providerJson.workflows.search.output.type).toBe('SEARCH')
  })

  it('should include security config when provided', () => {
    const result = generateProviderJson({
      ...baseProject,
      security: {
        allowedDomains: ['example.com'],
        maxRequestsPerRun: 10,
      },
    })

    expect(result.providerJson.security.allowedDomains).toEqual(['example.com'])
    expect(result.providerJson.security.maxRequestsPerRun).toBe(10)
  })

  it('should auto-derive allowedDomains from endpoint when security not provided', () => {
    const result = generateProviderJson(baseProject)
    expect(result.providerJson.security).toBeDefined()
    // baseProject.endpoint = https://example.com/provider.json → hostname example.com
    expect(result.providerJson.security.allowedDomains).toEqual(['example.com'])
    // default values dipertahankan
    expect(result.providerJson.security.maxResponseBytes).toBe(1048576)
  })

  it('should allow user override of allowedDomains', () => {
    const result = generateProviderJson({
      ...baseProject,
      security: { allowedDomains: ['custom.com', 'cdn.custom.com'] },
    })
    expect(result.providerJson.security.allowedDomains).toEqual(['custom.com', 'cdn.custom.com'])
  })

  it('should include update config when provided', () => {
    const result = generateProviderJson({
      ...baseProject,
      update: { mode: 'auto', intervalHours: 24 },
    })

    expect(result.providerJson.update.mode).toBe('auto')
    expect(result.providerJson.update.intervalHours).toBe(24)
  })

  it('should generate valid provider ID from name when id not provided', () => {
    const result = generateProviderJson({
      ...baseProject,
      id: undefined,
      name: 'My Anime Provider',
    })
    expect(result.providerJson.providers[0].id).toBe('my_anime_provider')
  })

  it('should generate validation report', () => {
    const result = generateProviderJson(baseProject)
    const report = generateValidationReport(result.providerJson, {
      offline: { passed: true },
      live: { passed: false },
    })

    expect(report.status).toBe('OFFLINE_PASSED')
    expect(report.valid).toBe(true)
    expect(report.generatedAt).toBeDefined()
  })

  it('should mark BLOCKED when critical blockers exist', () => {
    const result = generateProviderJson(baseProject)
    const report = generateValidationReport(result.providerJson, {
      offline: { passed: true },
      live: { passed: true },
    })

    // Engine memiliki critical blocker (parse stubs), jadi status BLOCKED
    expect(report.status).toBe('BLOCKED')
    expect(report.criticalBlockers.length).toBeGreaterThan(0)
  })
})
