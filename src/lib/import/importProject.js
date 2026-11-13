import { sanitizeWorkflowOrigin } from '../generator/workflowSync.js'

// Convert a project bundle or an Anilili provider.json into the wizard's project model.
// provider.json stores workflows/metadata/security/update at the TOP level.
export function normalizeImportedProject(imported) {
  if (!imported || typeof imported !== 'object' || Array.isArray(imported)) {
    throw new Error('Berkas harus berupa objek JSON')
  }

  let project
  if ('version' in imported && 'project' in imported) {
    if (imported.version !== 1) {
      throw new Error(`Versi bundel tidak didukung: ${imported.version}`)
    }
    project = imported.project
  } else if (Array.isArray(imported.providers)) {
    const entry = imported.providers[0]
    if (!entry || typeof entry !== 'object') {
      throw new Error('provider.json tidak berisi provider yang valid')
    }
    project = {
      name: entry.name,
      id: entry.id,
      endpoint: entry.endpoint,
      source: entry.source || 'generic_html',
      category: entry.category || 'general',
      languages: entry.languages || [],
      subtitleVariants: entry.subtitleVariants || [],
      priority: entry.priority ?? 0,
      enabled: entry.enabled !== false,
      capabilities: entry.capabilities || {},
      metadata: imported.metadata || {},
      workflows: imported.workflows || entry.workflows || {},
      security: imported.security || null,
      update: imported.update || null,
      samples: [],
    }
  } else if ('name' in imported && 'endpoint' in imported) {
    // Unversioned project exports from earlier generator builds.
    project = imported
  } else {
    throw new Error('Format tidak dikenal: pilih provider.json atau project-bundle.json')
  }

  if (!project || typeof project !== 'object' || Array.isArray(project)) {
    throw new Error('Bundel tidak memiliki objek proyek')
  }
  if (!project.name || typeof project.name !== 'string' ||
      !project.endpoint || typeof project.endpoint !== 'string') {
    throw new Error('Nama dan endpoint proyek wajib tersedia')
  }
  if (!project.id || typeof project.id !== 'string') {
    throw new Error('Provider ID tidak valid')
  }

  const migrated = migrateConfigSourceUrl({
    ...project,
    samples: Array.isArray(project.samples) ? project.samples : [],
    workflows: project.workflows || {},
  })
  // Bundel tak tepercaya: sampleMap harus {kategori: [string]}; selain itu dibuang (dihitung ulang saat dipakai).
  if (!isSampleMap(migrated.sampleMap)) delete migrated.sampleMap
  // Catatan origin workflow (otomatis/manual) ikut bundel; yang tak sah dibuang → dianggap manual (aman).
  if ('workflowOrigin' in migrated) migrated.workflowOrigin = sanitizeWorkflowOrigin(migrated.workflowOrigin, migrated.workflows)
  return migrated
}

function isSampleMap(m) {
  return !!m && typeof m === 'object' && !Array.isArray(m) &&
    Object.values(m).every((v) => Array.isArray(v) && v.every((n) => typeof n === 'string'))
}

function endsWithJson(url) {
  try {
    return new URL(url).pathname.toLowerCase().endsWith('.json')
  } catch {
    return false
  }
}

/**
 * Proyek lama tanpa configSourceUrl: ambil dari metadata.sourceUrl, atau dari endpoint lama
 * bila berakhiran .json (itu URL config, bukan base URL -> endpoint dikosongkan, tidak dikarang).
 * Selain itu configSourceUrl = ''.
 */
export function migrateConfigSourceUrl(project) {
  if (!project || typeof project.configSourceUrl === 'string') return project
  const metaUrl = project.metadata && typeof project.metadata.sourceUrl === 'string' ? project.metadata.sourceUrl : ''
  const legacyEndpointIsConfig = typeof project.endpoint === 'string' && endsWithJson(project.endpoint)
  return {
    ...project,
    configSourceUrl: metaUrl || (legacyEndpointIsConfig ? project.endpoint : ''),
    endpoint: legacyEndpointIsConfig ? '' : project.endpoint,
  }
}
