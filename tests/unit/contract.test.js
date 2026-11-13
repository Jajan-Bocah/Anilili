import { describe, it, expect } from 'vitest'
import {
  validateProviderJson,
  validateWorkflowStep,
  validateTemplateString,
  getBlockerStatus,
  ENGINE_SCHEMA_VERSION,
  SUPPORTED_OPS,
  SUPPORTED_SOURCES,
  CRITICAL_BLOCKERS,
  OPERATIONAL_LIMITATIONS,
} from '../../src/lib/contract/engineContract.js'

describe('engineContract', () => {
  describe('constants', () => {
    it('should have schemaVersion 1', () => {
      expect(ENGINE_SCHEMA_VERSION).toBe(1)
    })

    it('should have supported ops', () => {
      expect(SUPPORTED_OPS).toContain('select')
      expect(SUPPORTED_OPS).toContain('http')
      expect(SUPPORTED_OPS).toContain('parse_html')
      expect(SUPPORTED_OPS).toContain('parse_json')
      expect(SUPPORTED_OPS).toContain('regex')
      expect(SUPPORTED_OPS).toContain('map')
      expect(SUPPORTED_OPS).toContain('normalize')
      expect(SUPPORTED_OPS).toContain('assert')
      expect(SUPPORTED_OPS).toContain('filter')
    })

    it('should have supported sources', () => {
      expect(SUPPORTED_SOURCES).toContain('generic_html')
      expect(SUPPORTED_SOURCES).toContain('generic_json')
      expect(SUPPORTED_SOURCES).toContain('miruro')
      expect(SUPPORTED_SOURCES).toContain('anivexa')
    })

    it('should have critical blockers', () => {
      // P5 (b755e47) memperbaiki pipeId/slug — dua blocker lama dihapus.
      expect(CRITICAL_BLOCKERS.length).toBe(2)
      expect(CRITICAL_BLOCKERS.some((b) => b.id === 'no-per-item-base64-decode')).toBe(true)
      expect(CRITICAL_BLOCKERS.some((b) => b.id === 'android-playback-untested')).toBe(true)
      // Blocker lama (P4) tidak lagi berlaku untuk engine P5.
      expect(CRITICAL_BLOCKERS.some((b) => b.id === 'episode-slug-cache-stores-pipeid')).toBe(false)
      expect(CRITICAL_BLOCKERS.some((b) => b.id === 'sources-url-prefers-anime-slug')).toBe(false)
    })

    it('should have operational limitations', () => {
      expect(OPERATIONAL_LIMITATIONS.length).toBeGreaterThan(0)
    })
  })

  describe('validateProviderJson', () => {
    it('should accept valid provider.json', () => {
      const valid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test Provider',
            endpoint: 'https://example.com/provider.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/provider.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(valid)
      expect(result.valid).toBe(true)
      expect(result.errors.length).toBe(0)
    })

    it('should reject schemaVersion not 1', () => {
      const invalid = {
        schemaVersion: 2,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'https://example.com/p.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(invalid)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('schemaVersion'))).toBe(true)
    })

    it('should reject HTTP endpoint without trust', () => {
      const invalid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'http://example.com/p.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(invalid)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('HTTP memerlukan RemoteSourceTrust') || e.includes('HTTPS'))).toBe(true)
    })

    it('should reject invalid source', () => {
      const invalid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'https://example.com/p.json',
            source: 'invalid_source',
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(invalid)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('source'))).toBe(true)
    })

    it('should reject missing metadata', () => {
      const invalid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'https://example.com/p.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
      }

      const result = validateProviderJson(invalid)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('metadata'))).toBe(true)
    })

    it('should return blockers even when valid', () => {
      const valid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'https://example.com/p.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(valid)
      expect(result.blockers.length).toBeGreaterThan(0)
      expect(result.blockers).toContain('no-per-item-base64-decode')
      expect(result.blockers).toContain('android-playback-untested')
      // Blocker lama P4 tidak lagi ada.
      expect(result.blockers).not.toContain('episode-slug-cache-stores-pipeid')
    })

    it('should handle null/undefined input', () => {
      const result1 = validateProviderJson(null)
      expect(result1.valid).toBe(false)

      const result2 = validateProviderJson(undefined)
      expect(result2.valid).toBe(false)
    })

    it('should validate security block — reject unknown key', () => {
      const withBadSecurity = {
        schemaVersion: 1,
        providers: [{ id: 'x', name: 'X', endpoint: 'https://x.com', source: 'generic_html', capabilities: {} }],
        metadata: { name: 'X', version: '1.0.0', description: '', author: null, checksum: null, sourceUrl: 'https://x.com', fetchedAt: Date.now() },
        security: { allowedDomains: ['x.com'], unknownKey: true },
      }
      const result = validateProviderJson(withBadSecurity)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('unknownKey'))).toBe(true)
    })

    it('should validate security block — reject non-array allowedDomains', () => {
      const withBadDomains = {
        schemaVersion: 1,
        providers: [{ id: 'x', name: 'X', endpoint: 'https://x.com', source: 'generic_html', capabilities: {} }],
        metadata: { name: 'X', version: '1.0.0', description: '', author: null, checksum: null, sourceUrl: 'https://x.com', fetchedAt: Date.now() },
        security: { allowedDomains: 'x.com' },
      }
      const result = validateProviderJson(withBadDomains)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('allowedDomains'))).toBe(true)
    })

    it('should validate security block — reject string maxResponseBytes', () => {
      const withStringBytes = {
        schemaVersion: 1,
        providers: [{ id: 'x', name: 'X', endpoint: 'https://x.com', source: 'generic_html', capabilities: {} }],
        metadata: { name: 'X', version: '1.0.0', description: '', author: null, checksum: null, sourceUrl: 'https://x.com', fetchedAt: Date.now() },
        security: { allowedDomains: ['x.com'], maxResponseBytes: '1048576' },
      }
      const result = validateProviderJson(withStringBytes)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('maxResponseBytes'))).toBe(true)
    })

    it('should accept valid security block', () => {
      const withSecurity = {
        schemaVersion: 1,
        providers: [{ id: 'x', name: 'X', endpoint: 'https://x.com', source: 'generic_html', capabilities: {} }],
        metadata: { name: 'X', version: '1.0.0', description: '', author: null, checksum: null, sourceUrl: 'https://x.com', fetchedAt: Date.now() },
        security: { allowedDomains: ['x.com'], maxResponseBytes: 1048576, maxRequestsPerRun: 10 },
      }
      const result = validateProviderJson(withSecurity)
      expect(result.valid).toBe(true)
    })
  })

  describe('validateWorkflowStep', () => {
    it('should accept valid step', () => {
      const result = validateWorkflowStep({
        op: 'select',
        params: { selector: '.anime-item', input: 'html' },
      })
      expect(result.valid).toBe(true)
      expect(result.errors.length).toBe(0)
    })

    it('should reject invalid op', () => {
      const result = validateWorkflowStep({
        op: 'invalid_op',
        params: {},
      })
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('op'))).toBe(true)
    })

    it('should reject ${} syntax in template fields', () => {
      const result = validateWorkflowStep({
        op: 'http',
        params: { url: 'https://example.com/${id}', input: 'html' },
      })
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('${}'))).toBe(true)
    })

    it('should accept {{}} syntax in template fields', () => {
      const result = validateWorkflowStep({
        op: 'http',
        params: { url: 'https://example.com/{{id}}', input: 'html' },
      })
      expect(result.valid).toBe(true)
    })
  })

  describe('validateTemplateString', () => {
    it('should extract {{variable.path}} variables', () => {
      const result = validateTemplateString('https://api.com/{{id}}/episodes')
      expect(result.variables).toContain('id')
      expect(result.valid).toBe(true)
    })

    it('should detect unsupported ${} syntax', () => {
      const result = validateTemplateString('https://api.com/${id}')
      expect(result.unsupported).toContain('${id}')
      expect(result.valid).toBe(false)
    })

    it('should handle nested path variables', () => {
      const result = validateTemplateString('{{response.body.results.0.title}}')
      expect(result.variables).toContain('response.body.results.0.title')
      expect(result.valid).toBe(true)
    })

    it('should handle non-string input', () => {
      const result = validateTemplateString(123)
      expect(result.valid).toBe(true)
      expect(result.variables).toEqual([])
    })

    it('should support dash in variable names (parity Kotlin)', () => {
      const result = validateTemplateString('{{response.body.api-data.items}}')
      expect(result.variables).toContain('response.body.api-data.items')
      expect(result.valid).toBe(true)
    })
  })

  describe('validateProviderJson with trust', () => {
    const mockTrust = {
      allowHttpEndpoints: true,
      allowPrivateIpEndpoints: true,
      trustedEndpointOrigins: ['http://cdn.example.com:8080', 'http://192.168.1.100:8080'],
      allowHttpConfig: true,
      configOrigin: 'https://example.com:443',
    }

    it('should accept HTTP endpoint with trust', () => {
      const invalid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'http://cdn.example.com:8080/provider.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(invalid, { trust: mockTrust })
      expect(result.valid).toBe(true)
    })

    it('should accept private IP endpoint with trust', () => {
      const invalid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'http://192.168.1.100:8080/provider.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(invalid, { trust: mockTrust })
      expect(result.valid).toBe(true)
    })

    it('should reject HTTP endpoint without trust', () => {
      const invalid = {
        schemaVersion: 1,
        providers: [
          {
            id: 'test-provider',
            name: 'Test',
            endpoint: 'http://example.com/p.json',
            source: 'generic_html',
            capabilities: {},
          },
        ],
        metadata: {
          name: 'Test',
          version: '1.0.0',
          description: '',
          author: null,
          checksum: null,
          sourceUrl: 'https://example.com/p.json',
          fetchedAt: Date.now(),
        },
      }

      const result = validateProviderJson(invalid)
      expect(result.valid).toBe(false)
      expect(result.errors.some((e) => e.includes('HTTP memerlukan RemoteSourceTrust'))).toBe(true)
    })
  })

  describe('getBlockerStatus', () => {
    it('should return critical and operational blockers', () => {
      const status = getBlockerStatus()
      expect(Array.isArray(status.critical)).toBe(true)
      expect(Array.isArray(status.operational)).toBe(true)
      expect(status.critical.length).toBeGreaterThan(0)
    })
  })
  describe('paritas Kotlin (P2)', () => {
    const base = () => ({
      schemaVersion: 1,
      providers: [
        { id: 'p1', name: 'P', endpoint: 'https://example.com/p.json', source: 'generic_html', capabilities: {} },
      ],
      metadata: {
        name: 'T', version: '1', description: '', author: null, checksum: null,
        sourceUrl: 'https://example.com/p.json', fetchedAt: 1,
      },
    })
    const wf = (steps, output = { type: 'RAW', mapping: {} }) => ({
      workflows: { search: { name: 'search', version: 1, description: '', steps, output } },
    })

    it('menolak endpoint loopback walau HTTPS', () => {
      const j = base()
      j.providers[0].endpoint = 'https://localhost/p.json'
      const r = validateProviderJson(j)
      expect(r.valid).toBe(false)
      expect(r.errors.some((e) => /loopback/i.test(e))).toBe(true)
    })

    it('menolak id duplikat dan id provider sistem', () => {
      const j = base()
      j.providers.push({ ...j.providers[0] })
      expect(validateProviderJson(j).errors.some((e) => /duplikat/i.test(e))).toBe(true)
      const k = base()
      k.providers[0].id = 'bee'
      expect(validateProviderJson(k).valid).toBe(false)
    })

    it('menolak key tidak dikenal (strict decode manager)', () => {
      const j = base()
      j.extra = 1
      expect(validateProviderJson(j).valid).toBe(false)
    })

    it('step tanpa params.input ditolak; input variabel diterima', () => {
      const bad = { ...base(), ...wf([{ op: 'select', params: { selector: 'a' }, saveAs: 'x' }]) }
      expect(validateProviderJson(bad).valid).toBe(false)
      const ok = {
        ...base(),
        ...wf([
          { op: 'http', id: null, params: { url: 'https://example.com/' }, saveAs: 'resp' },
          { op: 'select', id: null, params: { input: 'resp', selector: 'a' }, saveAs: 'x' },
        ]),
      }
      const r = validateProviderJson(ok)
      expect(r.errors).toEqual([])
    })

    it('attribute/jsonPath kosong atau null ditolak (null menjadi string "null")', () => {
      const j = {
        ...base(),
        ...wf([
          { op: 'http', id: null, params: { url: 'https://example.com/' }, saveAs: 'resp' },
          { op: 'select', id: null, params: { input: 'resp', selector: 'a', attribute: '' }, saveAs: 'x' },
        ]),
      }
      expect(validateProviderJson(j).valid).toBe(false)
    })

    it('getBlockerStatus mengembalikan suspected terpisah dari critical', () => {
      const s = getBlockerStatus()
      expect(Array.isArray(s.suspected)).toBe(true)
      expect(s.suspected.length).toBeGreaterThan(0)
      expect(s.critical.some((b) => b.id === 'android-playback-untested')).toBe(true)
    })
  })
})
