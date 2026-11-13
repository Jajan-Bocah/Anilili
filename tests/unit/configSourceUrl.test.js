import { describe, expect, it } from 'vitest'
import { generateProviderJson } from '../../src/lib/generator/providerGenerator.js'
import { normalizeImportedProject, migrateConfigSourceUrl } from '../../src/lib/import/importProject.js'

const base = {
  id: 'anichin',
  name: 'Anichin',
  endpoint: 'https://anichin.moe/',
  source: 'generic_html',
  workflows: {},
}

describe('generateProviderJson: configSourceUrl', () => {
  it('memakai configSourceUrl sebagai metadata.sourceUrl, terpisah dari endpoint', () => {
    const r = generateProviderJson({ ...base, configSourceUrl: 'https://anichin.moe/provider.json' })
    expect(r.providerJson.metadata.sourceUrl).toBe('https://anichin.moe/provider.json')
    expect(r.providerJson.providers[0].endpoint).toBe('https://anichin.moe/')
    expect(r.providerJson.metadata.sourceUrl).not.toBe(r.providerJson.providers[0].endpoint)
    expect(r.meta.needsInput).toEqual([])
  })

  it.each(['', undefined, '   '])('tanpa configSourceUrl (%j): tidak fallback ke endpoint', (v) => {
    const r = generateProviderJson({ ...base, configSourceUrl: v })
    expect(r.providerJson).toBeNull()
    expect(r.meta.valid).toBe(false)
    expect(r.meta.needsInput).toEqual(['configSourceUrl'])
    expect(r.meta.errors).toContain('Config Source URL (metadata.sourceUrl) belum diisi')
  })
})

describe('import/migrasi configSourceUrl', () => {
  it('provider.json: configSourceUrl dari metadata.sourceUrl, endpoint tetap', () => {
    const p = normalizeImportedProject({
      schemaVersion: 1,
      providers: [{ id: 'a', name: 'A', endpoint: 'https://anichin.moe/', source: 'generic_html' }],
      metadata: { sourceUrl: 'https://anichin.moe/provider.json' },
    })
    expect(p.configSourceUrl).toBe('https://anichin.moe/provider.json')
    expect(p.endpoint).toBe('https://anichin.moe/')
  })

  it('bundle lama dengan endpoint .json: dipindah ke configSourceUrl, endpoint dikosongkan', () => {
    const p = normalizeImportedProject({ version: 1, project: { ...base, endpoint: 'https://x.test/provider.json?v=1' } })
    expect(p.configSourceUrl).toBe('https://x.test/provider.json?v=1')
    expect(p.endpoint).toBe('')
  })

  it('bundle lama dengan endpoint bukan .json: endpoint dipertahankan, configSourceUrl kosong', () => {
    const p = normalizeImportedProject({ version: 1, project: base })
    expect(p.endpoint).toBe('https://anichin.moe/')
    expect(p.configSourceUrl).toBe('')
  })

  it('round-trip bundle mempertahankan configSourceUrl', () => {
    const project = { ...base, configSourceUrl: 'https://anichin.moe/provider.json' }
    const bundle = JSON.parse(JSON.stringify({ version: 1, project }))
    const p = normalizeImportedProject(bundle)
    expect(p.configSourceUrl).toBe(project.configSourceUrl)
    expect(p.endpoint).toBe(project.endpoint)
  })

  it('migrateConfigSourceUrl tidak menyentuh proyek yang sudah punya field', () => {
    const p = { ...base, configSourceUrl: '' }
    expect(migrateConfigSourceUrl(p)).toBe(p)
  })
})
