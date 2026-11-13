// sampleMap.js — pemetaan sampel HTML per kategori workflow (Search / Detail-Episodes / Watch-Sources / Servers / Homepage).
// Pemetaan berbasis `fileName` (bundle ekspor membuang html, nama tetap ada) dan disimpan di project.sampleMap.
// Murni: tidak mengenal provider tertentu. Petunjuk nama berkas/URL hanya memprioritaskan, bukan syarat.
import { inferRules } from '../analyzer/ruleInference.js'

export const SAMPLE_CATEGORIES = [
  { key: 'search', label: 'Search', workflow: 'search' },
  { key: 'episodes', label: 'Detail / Episodes', workflow: 'episodes' },
  { key: 'sources', label: 'Watch / Sources', workflow: 'sources' },
  { key: 'servers', label: 'Servers', workflow: 'servers' },
  { key: 'homepage', label: 'Homepage', workflow: null },
]

const HINTS = {
  search: /search|cari|query|find|[?&]s=/i,
  episodes: /series|detail|info|daftar|anime[-_/]|show/i,
  sources: /watch|nonton|stream|play|episode/i,
  servers: /server|mirror|watch|nonton|ajax/i,
  homepage: /home|index|beranda|^main/i,
}

// Rule penentu per kategori (selector yang harus cocok antara rule terpilih dan rule per-sampel).
const KEY_RULES = { search: ['search-url'], episodes: ['episode-url'], sources: ['stream-iframe', 'stream-source'], servers: ['server-selector'] }
const GROUP = { search: 'search', episodes: 'episodes', sources: 'stream', servers: 'stream' }
const STATUS_RANK = { DETECTED: 2, NEEDS_MANUAL_REVIEW: 1, NOT_DETECTED: 0 }

const hintText = (s) => `${s.fileName || ''} ${s.htmlUrl || ''} ${s.title || ''}`
export const hasHint = (cat, sample) => !!HINTS[cat] && HINTS[cat].test(hintText(sample))

const allRules = (r) => [...(r?.search || []), ...(r?.episodes || []), ...(r?.stream || [])]
const sig = (r) => `${r.value}|${r.attribute || ''}`

/** Analisis semua sampel → [{ fileName, htmlUrl, title, rules }] (sampel tanpa html dilewati). */
export function analyzeSamples(samples) {
  return (samples || [])
    .filter((s) => s && s.html)
    .map((s) => ({ fileName: s.fileName, htmlUrl: s.htmlUrl, title: s.title, rules: inferRules({ html: s.html, htmlUrl: s.htmlUrl, resources: s.resources || [] }) }))
}

const rank = (rule, cat, sample) => (STATUS_RANK[rule.status] ?? 0) * 10 + (rule.confidence || 0) + (hasHint(cat, sample) ? 0.1 : 0)

/**
 * Gabungkan rule dari semua sampel. Search/Episodes dipilih per kelompok dari SATU sampel terbaik agar konsisten
 * (selector title/url/image berasal dari struktur yang sama); Sources dipilih per rule id. Tiap rule diberi `sourceSample`.
 */
export function mergeAnalysis(perSample) {
  const merged = { search: [], episodes: [], stream: [] }
  const pickGroup = (group, cat, keyId) => {
    let best = null
    for (const p of perSample) {
      const key = (p.rules?.[group] || []).find((r) => r.id === keyId)
      if (!key) continue
      const score = rank(key, cat, p)
      if (!best || score > best.score) best = { p, score }
    }
    if (best) merged[group] = (best.p.rules[group] || []).map((r) => ({ ...r, sourceSample: best.p.fileName }))
  }
  pickGroup('search', 'search', 'search-url')
  pickGroup('episodes', 'episodes', 'episode-url')
  const streamBest = new Map()
  for (const p of perSample) {
    for (const r of p.rules?.stream || []) {
      const cat = r.id === 'server-selector' ? 'servers' : 'sources'
      const score = rank(r, cat, p)
      const cur = streamBest.get(r.id)
      if (!cur || score > cur.score) streamBest.set(r.id, { score, rule: { ...r, sourceSample: p.fileName } })
    }
  }
  merged.stream = [...streamBest.values()].map((v) => v.rule)
  return merged
}

/**
 * Pilih sampel otomatis per kategori: sampel memenuhi syarat bila rule penentunya DETECTED dengan selector sama
 * seperti rule terpilih. Bila ada yang bernama/ber-URL sesuai petunjuk → hanya itu; selain itu confidence tertinggi.
 * Homepage hanya manual.
 */
export function autoAssignSamples(perSample, inferredRules) {
  const map = { search: [], episodes: [], sources: [], servers: [], homepage: [] }
  const chosen = allRules(inferredRules)
  for (const cat of Object.keys(KEY_RULES)) {
    const targets = chosen.filter((r) => KEY_RULES[cat].includes(r.id) && r.value && r.status === 'DETECTED')
    if (!targets.length) continue
    const eligible = []
    for (const p of perSample) {
      const hits = (p.rules?.[GROUP[cat]] || []).filter(
        (r) => r.status === 'DETECTED' && r.value && targets.some((t) => t.id === r.id && sig(t) === sig(r))
      )
      if (hits.length) eligible.push({ p, conf: Math.max(...hits.map((h) => h.confidence || 0)) })
    }
    const hinted = eligible.filter((e) => hasHint(cat, e.p))
    let pool = hinted
    if (!pool.length) {
      const top = Math.max(0, ...eligible.map((e) => e.conf))
      pool = eligible.filter((e) => e.conf === top)
    }
    map[cat] = pool.sort((a, b) => b.conf - a.conf).map((e) => e.p.fileName)
  }
  return map
}

/** Peta tersimpan (bila ada) dibersihkan dari nama berkas yang sudah tidak ada; kategori yang hilang → []. */
export function normalizeSampleMap(map, samples) {
  const names = new Set((samples || []).map((s) => s.fileName))
  const out = {}
  for (const { key } of SAMPLE_CATEGORIES) out[key] = (map?.[key] || []).filter((n) => names.has(n))
  return out
}

/** Peta efektif proyek: tersimpan → dipakai; belum ada (proyek lama) → auto-assign dari sampel + inferredRules. */
export function effectiveSampleMap(project) {
  if (project?.sampleMap) return normalizeSampleMap(project.sampleMap, project.samples)
  const perSample = analyzeSamples(project?.samples)
  return normalizeSampleMap(autoAssignSamples(perSample, project?.inferredRules), project?.samples)
}

/** Sampel (dengan html) yang dipetakan ke satu workflow; kosong bila belum dipetakan / html tidak tersedia. */
export function samplesForWorkflow(project, workflowName, map = effectiveSampleMap(project)) {
  const cat = SAMPLE_CATEGORIES.find((c) => c.workflow === workflowName)
  if (!cat) return []
  const byName = new Map((project?.samples || []).map((s) => [s.fileName, s]))
  return (map[cat.key] || []).map((n) => byName.get(n)).filter((s) => s && s.html)
}
