// workflowSync.js — aturan sinkron antara workflow hasil inferensi (otomatis) dan edit manual pengguna.
// `project.workflowOrigin[nama]` = 'auto' | 'manual'. Workflow yang ada tanpa catatan origin (proyek lama,
// provider.json hasil impor) dianggap 'manual': lebih aman dipertahankan daripada ditimpa tanpa sepengetahuan user.
// Fungsi murni; tidak ada state tambahan. Hanya tindakan eksplisit (regenerateWorkflow) yang menimpa edit manual.

export const ORIGIN = { AUTO: 'auto', MANUAL: 'manual' }

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

/** Origin efektif satu workflow; null bila workflow tidak ada. */
export function originOf(project, name) {
  if (!project?.workflows?.[name]) return null
  return project.workflowOrigin?.[name] === ORIGIN.AUTO ? ORIGIN.AUTO : ORIGIN.MANUAL
}

/**
 * Terapkan hasil inferensi terbaru HANYA ke workflow otomatis (atau yang belum ada).
 * Workflow auto yang tidak lagi terbukti dihapus; workflow manual tidak pernah disentuh.
 * Mengembalikan objek `project` yang sama bila tidak ada perubahan (aman untuk setState).
 */
export function applyInference(project, inferred) {
  const current = project.workflows || {}
  const origin = project.workflowOrigin || {}
  const workflows = { ...current }
  const nextOrigin = { ...origin }
  let changed = false
  for (const name of new Set([...Object.keys(current), ...Object.keys(inferred || {})])) {
    const inf = inferred?.[name]
    // Proyek lama tanpa catatan origin, isi identik dengan inferensi → memang hasil otomatis.
    if (!origin[name] && inf && current[name] && same(current[name], inf)) {
      nextOrigin[name] = ORIGIN.AUTO
      changed = true
      continue
    }
    if (originOf(project, name) === ORIGIN.MANUAL) continue
    if (inf) {
      if (!current[name] || !same(current[name], inf) || origin[name] !== ORIGIN.AUTO) {
        workflows[name] = inf
        nextOrigin[name] = ORIGIN.AUTO
        changed = true
      }
    } else if (current[name]) {
      delete workflows[name]
      delete nextOrigin[name]
      changed = true
    }
  }
  return changed ? { ...project, workflows, workflowOrigin: nextOrigin } : project
}

/** Edit pengguna pada satu workflow → disimpan dan ditandai manual. */
export function markManual(project, name, workflow) {
  return {
    ...project,
    workflows: { ...(project.workflows || {}), [name]: workflow },
    workflowOrigin: { ...(project.workflowOrigin || {}), [name]: ORIGIN.MANUAL },
  }
}

/** Tindakan eksplisit: timpa workflow dengan hasil inferensi dan kembalikan statusnya ke otomatis. */
export function regenerateWorkflow(project, name, inferredWorkflow) {
  if (!inferredWorkflow) return project
  return {
    ...project,
    workflows: { ...(project.workflows || {}), [name]: inferredWorkflow },
    workflowOrigin: { ...(project.workflowOrigin || {}), [name]: ORIGIN.AUTO },
  }
}

/** Nama workflow manual yang berbeda dari inferensi terbaru (kandidat regenerasi, butuh konfirmasi). */
export function manualConflicts(project, inferred) {
  return Object.keys(inferred || {}).filter(
    (name) => originOf(project, name) === ORIGIN.MANUAL && !same(project.workflows[name], inferred[name])
  )
}

/** Bundel tak tepercaya: hanya {nama workflow yang ada: 'auto'|'manual'}. */
export function sanitizeWorkflowOrigin(origin, workflows) {
  const out = {}
  if (!origin || typeof origin !== 'object' || Array.isArray(origin)) return out
  for (const [name, v] of Object.entries(origin)) {
    if (workflows && Object.hasOwn(workflows, name) && (v === ORIGIN.AUTO || v === ORIGIN.MANUAL)) out[name] = v
  }
  return out
}
