import { describe, it, expect } from 'vitest'
import { validateSchema } from '../../src/lib/tester/schemaValidator.js'

describe('schemaValidator', () => {
  const validJson = {
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

  it('should pass valid provider.json', () => {
    const result = validateSchema(validJson)
    expect(result.valid).toBe(true)
    // Critical blockers from engine are reported as failed but don't make valid=false
    expect(result.failed.length).toBeGreaterThan(0)
    expect(result.blockers.length).toBeGreaterThan(0)
  })

  it('should detect missing schemaVersion', () => {
    const invalid = { ...validJson }
    delete invalid.schemaVersion
    const result = validateSchema(invalid)
    expect(result.valid).toBe(false)
    expect(result.failed.some((f) => f.message.includes('schemaVersion'))).toBe(true)
  })

  it('should detect missing providers', () => {
    const invalid = { ...validJson }
    delete invalid.providers
    const result = validateSchema(invalid)
    expect(result.valid).toBe(false)
    expect(result.failed.some((f) => f.message.includes('providers'))).toBe(true)
  })

  it('should detect missing metadata', () => {
    const invalid = { ...validJson }
    delete invalid.metadata
    const result = validateSchema(invalid)
    expect(result.valid).toBe(false)
    expect(result.failed.some((f) => f.message.includes('metadata'))).toBe(true)
  })

  it('should have summary counts', () => {
    const result = validateSchema(validJson)
    expect(result.summary.total).toBe(
      result.summary.passed + result.summary.failed + result.summary.warnings
    )
  })

  it('should handle null input', () => {
    const result = validateSchema(null)
    expect(result.valid).toBe(false)
    expect(result.failed.length).toBeGreaterThan(0)
  })
})
