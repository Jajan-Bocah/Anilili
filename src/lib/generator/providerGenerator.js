// providerGenerator.js — Generate provider.json sesuai kontrak engine Anilili.
// provider.json TIDAK mencakup status kompatibilitas generator (terpisah di laporan).

import {
  ENGINE_SCHEMA_VERSION,
  SUPPORTED_SOURCES,
  DEFAULT_SECURITY,
  DEFAULT_CAPABILITIES,
  getBlockerStatus,
  validateProviderJson,
  hostnameOf,
  isLoopbackEndpoint,
  isLoopbackHost,
  isPrivateOrLocalHost,
} from '../contract/engineContract.js'

/**
 * Generate provider.json dari objek proyek.
 * @param {object} project
 * @param {object} options - Opsi tambahan (misalnya trust untuk validasi HTTP/private IP)
 * @returns {{
 *   providerJson: object|null,
 *   meta: { valid: boolean, errors: string[], warnings: string[] }
 * }}
 */
export function generateProviderJson(project, options = {}) {
  const errors = []
  const warnings = []

  if (!project) {
    errors.push('Project tidak boleh kosong')
    return { providerJson: null, meta: { valid: false, errors, warnings } }
  }

  if (!project.name) {
    errors.push('project.name wajib diisi')
  }
  // Endpoint validation with trust support
  if (!project.endpoint) {
    errors.push('project.endpoint wajib diisi')
  } else {
    const isHttps = project.endpoint.startsWith('https://')
    const isHttp = project.endpoint.startsWith('http://')
    if (!isHttps && !isHttp) {
      errors.push('project.endpoint harus HTTP atau HTTPS')
    } else if (isHttp) {
      // HTTP endpoint: diperlukan trust eksplisit
      const trust = options.trust
      if (!trust || !trust.allowHttpEndpoints) {
        errors.push('project.endpoint HTTP memerlukan RemoteSourceTrust eksplisit (allowHttpEndpoints)')
      } else {
        warnings.push('project.endpoint HTTP diizinkan via RemoteSourceTrust')
      }
    }
    if (isLoopbackEndpoint(project.endpoint)) {
      errors.push('project.endpoint mengarah ke loopback; ditolak permanen oleh engine (trust tidak berlaku)')
    }
    // Private IP / LAN check
    const endpointHost = hostnameOf(project.endpoint)
    if (endpointHost && isPrivateOrLocalHost(endpointHost) && !isLoopbackHost(endpointHost)) {
      const trust = options.trust
      if (!trust || !trust.allowPrivateIpEndpoints) {
        errors.push('project.endpoint private IP/LAN memerlukan RemoteSourceTrust eksplisit (allowPrivateIpEndpoints)')
      } else {
        warnings.push('project.endpoint private IP/LAN diizinkan via RemoteSourceTrust')
      }
    }
  }
  if (!SUPPORTED_SOURCES.includes(project.source)) {
    errors.push(`project.source harus salah satu dari: ${SUPPORTED_SOURCES.join(', ')}`)
  }

  // Config Source URL = lokasi hosting provider.json (bukan endpoint); tidak ada fallback/karangan.
  const needsInput = []
  if (!project.configSourceUrl || !String(project.configSourceUrl).trim()) {
    errors.push('Config Source URL (metadata.sourceUrl) belum diisi')
    needsInput.push('configSourceUrl')
  }

  if (errors.length > 0) {
    return { providerJson: null, meta: { valid: false, errors, warnings, needsInput } }
  }

  const now = Date.now()

  const providerEntry = {
    id: project.id || generateProviderId(project.name),
    name: project.name || 'Unknown Provider',
    endpoint: project.endpoint,
    source: project.source || 'generic_html',
    capabilities: {
      ...DEFAULT_CAPABILITIES,
      ...(project.capabilities || {}),
    },
    category: project.category || 'general',
    languages: project.languages || [],
    subtitleVariants: project.subtitleVariants || [],
    priority: project.priority || 0,
    enabled: project.enabled !== false,
  }

  const metadata = {
    name: (project.metadata && project.metadata.name) || project.name,
    description:
      (project.metadata && project.metadata.description) ||
      project.description ||
      '',
    author: (project.metadata && project.metadata.author) || project.author || null,
    version:
      (project.metadata && project.metadata.version) || project.version || '1.0.0',
    sourceUrl: project.configSourceUrl.trim(),
    fetchedAt: (project.metadata && project.metadata.fetchedAt) || now,
    checksum: (project.metadata && project.metadata.checksum) || null,
    minAppVersion:
      (project.metadata && project.metadata.minAppVersion) || '0.1.0',
    maxAppVersion:
      (project.metadata && project.metadata.maxAppVersion) || null,
  }

  const workflows = {}
  const workflowNames = ['search', 'episodes', 'servers', 'sources']

  workflowNames.forEach((name) => {
    const wf = project.workflows && project.workflows[name]
    if (!wf) return

    const steps = (wf.steps || []).map((step) => {
      return {
        op: step.op,
        id: step.id || `step-${Date.now()}-${Math.random().toString(36).slice(2)}`,
        params: cleanParams(step.params),
        saveAs: step.saveAs || null,
      }
    })

    // type & mapping wajib eksplisit: Kotlin WorkflowOutputConfig.type nullable tanpa default.
    const output = {
      type: (wf.output && wf.output.type) || 'RAW',
      mapping: (wf.output && wf.output.mapping) || {},
    }

    workflows[name] = {
      name: wf.name || name,
      version: wf.version || '1.0.0',
      description: wf.description || null,
      steps,
      output,
    }
  })

  // Security selalu disertakan. allowedDomains di-derive dari hostname endpoint (konservatif:
  // hanya host terverifikasi dari project itu sendiri). User bisa override via project.security.
  const h = hostnameOf(project.endpoint)
  const security = {
    ...DEFAULT_SECURITY,
    allowedDomains: h ? [h] : [],
    ...(project.security || {}),
  }

  const update = project.update
    ? {
      mode: project.update.mode || 'auto',
      intervalHours: project.update.intervalHours || 24,
    }
    : null

  const providerJson = {
    schemaVersion: ENGINE_SCHEMA_VERSION,
    providers: [providerEntry],
    metadata,
  }

  if (Object.keys(workflows).length > 0) {
    providerJson.workflows = workflows
  }
  providerJson.security = security
  if (update) {
    providerJson.update = update
  }

  // Validasi final terhadap engine contract (dengan trust jika ada)
  const validation = validateProviderJson(providerJson, { trust: options.trust })
  if (!validation.valid) {
    errors.push(...validation.errors)
    warnings.push(...validation.warnings)
    return { providerJson: null, meta: { valid: false, errors, warnings, needsInput } }
  }

  return {
    providerJson,
    meta: {
      valid: errors.length === 0,
      errors,
      warnings,
      needsInput,
    },
  }
}

/**
 * Buang params null/undefined (WorkflowParamsSerializer mengubah null → string "null")
 * dan string kosong untuk key opsional attribute/jsonPath (kosong ≠ tidak ada di engine).
 * @param {object} params
 * @returns {object}
 */
function cleanParams(params) {
  const out = {}
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v === null || v === undefined) return
    if (v === '' && (k === 'attribute' || k === 'jsonPath')) return
    out[k] = v
  })
  return out
}

/**
 * Generate ID provider dari nama.
 * @param {string} name
 * @returns {string}
 */
function generateProviderId(name) {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9_-]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 50) || 'provider'
}

/**
 * Generate validation report untuk provider.json.
 * @param {object} providerJson
 * @param {object} testResults
 * @returns {object}
 */
export function generateValidationReport(providerJson, testResults = {}) {
  const structure = validateProviderJsonStructure(providerJson)
  const blockers = getBlockerStatus()
  const criticalBlockerIds = blockers.critical.map((b) => b.id)

  let status = 'JSON_VALID'

  if (!structure.valid) {
    status = 'JSON_INVALID'
  } else if (testResults.offline && testResults.offline.passed === false) {
    status = 'OFFLINE_FAILED'
  } else if (
    testResults.offline &&
    testResults.offline.passed === true &&
    testResults.live &&
    testResults.live.passed === true
  ) {
    // ANILILI_READY hanya dengan bukti uji Android; tanpa itu, playback tidak boleh diklaim.
    if (testResults.android && testResults.android.passed === true && criticalBlockerIds.every((id) => id === 'android-playback-untested')) {
      status = 'ANILILI_READY'
    } else {
      status = 'BLOCKED'
    }
  } else if (testResults.offline && testResults.offline.passed === true) {
    status = 'OFFLINE_PASSED'
  }

  return {
    status,
    valid: structure.valid,
    errors: structure.errors,
    warnings: structure.warnings,
    criticalBlockers: blockers.critical,
    suspectedBlockers: blockers.suspected,
    operationalLimitations: blockers.operational,
    testResults,
    generatedAt: Date.now(),
  }
}

/**
 * Validasi struktur provider.json.
 * @param {object} json
 * @returns {{ valid: boolean, errors: string[], warnings: string[] }}
 */
function validateProviderJsonStructure(json) {
  const errors = []
  const warnings = []

  if (!json) {
    return { valid: false, errors: ['provider.json kosong'], warnings }
  }

  if (json.schemaVersion !== ENGINE_SCHEMA_VERSION) {
    errors.push(`schemaVersion harus ${ENGINE_SCHEMA_VERSION}`)
  }

  if (!Array.isArray(json.providers) || json.providers.length === 0) {
    errors.push('providers wajib berisi minimal 1 entri')
  } else {
    json.providers.forEach((p, i) => {
      if (!p.id) errors.push(`providers[${i}].id wajib diisi`)
      if (!p.endpoint) errors.push(`providers[${i}].endpoint wajib diisi`)
      if (!p.source) errors.push(`providers[${i}].source wajib diisi`)
    })
  }

  if (!json.metadata) {
    errors.push('metadata wajib diisi')
  }

  return { valid: errors.length === 0, errors, warnings }
}
