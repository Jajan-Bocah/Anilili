// schemaValidator.js — Validasi struktur provider.json terhadap engine contract.
// Digunakan di Provider Test Center lapisan A.

import { validateProviderJson, getBlockerStatus } from '../contract/engineContract.js'

/**
 * Jalankan schema validation lengkap terhadap provider.json.
 * @param {object} providerJson
 * @param {object} options - Opsi validasi tambahan (dilewatkan ke validateProviderJson)
 * @returns {{
 *   passed: Array<{field, message}>,
 *   failed: Array<{field, message, severity}>,
 *   warnings: Array<{field, message}>,
 *   summary: { total: number, passed: number, failed: number, warnings: number }
 * }}
 */
export function validateSchema(providerJson, options = {}) {
  const result = validateProviderJson(providerJson, options)

  const passed = []
  const failed = []
  const warnings = []

  // Map errors ke failed items
  result.errors.forEach((err) => {
    let field = 'unknown'
    const fieldMatch = err.match(/^(providers|metadata|security|update|schemaVersion)/)
    if (fieldMatch) {
      field = fieldMatch[1]
    }
    failed.push({ field, message: err, severity: 'error' })
  })

  // Map warnings ke warning items
  result.warnings.forEach((warn) => {
    let field = 'unknown'
    const fieldMatch = warn.match(/^(providers|metadata|security|update|schemaVersion)/)
    if (fieldMatch) {
      field = fieldMatch[1]
    }
    warnings.push({ field, message: warn, severity: 'warning' })
  })

  // Blocker kritis selalu ditampilkan sebagai failed (bukan karena error JSON, tapi karena engine limitation)
  const blockers = getBlockerStatus()
  blockers.critical.forEach((blocker) => {
    failed.push({
      field: 'engine',
      message: blocker.message,
      severity: 'critical',
    })
  })

  const total = passed.length + failed.length + warnings.length

  return {
    passed,
    failed,
    warnings,
    summary: {
      total,
      passed: passed.length,
      failed: failed.length,
      warnings: warnings.length,
    },
    valid: result.valid,
    blockers: result.blockers,
  }
}
