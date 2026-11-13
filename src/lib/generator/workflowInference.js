// workflowInference.js — susun workflow lengkap (steps + output.type/mapping) dari rule hasil analyzer.
// Murni & umum: hanya memakai field yang dibuktikan rule (selector, atribut, param query). Tidak mengarang
// selector/URL; bukti kurang → NEEDS_REVIEW tanpa workflow. Format output mengikuti runtimeParsers.js.

export const INFERENCE_STATUS = { READY: 'READY', NEEDS_REVIEW: 'NEEDS_REVIEW' }

const SAFE_VAR = /^[A-Za-z_][A-Za-z0-9_]*$/

/** Rule aktif = diaktifkan user (enabled) atau, bila belum diset, DETECTED; wajib punya selector/nilai. */
function active(rule) {
  if (!rule || !rule.value) return false
  return rule.enabled ?? rule.status === 'DETECTED'
}

function select(name, doc, id, rule, saveAs) {
  const params = { input: doc, selector: rule.value, mode: 'ALL' }
  if (rule.attribute) params.attribute = rule.attribute
  return { op: 'select', id: `${name}_${id}`, params, saveAs }
}

// Variabel hasil: pakai saveAs dari user bila sah & unik, selain itu default `${name}_${role}`.
function makeNamer(name, taken) {
  return (role, rule) => {
    const wanted = rule?.saveAs
    const v = wanted && SAFE_VAR.test(wanted) && !taken.has(wanted) ? wanted : `${name}_${role}`
    taken.add(v)
    return v
  }
}

function fail(reasons) {
  return { workflow: null, status: INFERENCE_STATUS.NEEDS_REVIEW, reasons }
}

function frame(name, description, http, fields, output, reasons) {
  const doc = `${name}_document`
  const taken = new Set([`${name}_response`, doc])
  const nameOf = makeNamer(name, taken)
  const steps = [
    { op: 'http', id: `${name}_fetch`, params: http, saveAs: `${name}_response` },
    { op: 'parse_html', id: `${name}_parse`, params: { input: `{{${name}_response.body}}` }, saveAs: doc },
  ]
  const mapping = {}
  for (const f of fields) {
    const saveAs = nameOf(f.role, f.rule)
    steps.push(select(name, doc, f.role, f.rule, saveAs))
    mapping[f.key] = saveAs
  }
  return {
    workflow: { name, version: '1.0', description, steps, output: { type: output, mapping } },
    status: INFERENCE_STATUS.READY,
    reasons,
  }
}

function resolveSearchUrl(action, endpoint) {
  try {
    return new URL(action || '', endpoint || undefined).href
  } catch {
    return null
  }
}

/**
 * @param {'search'|'episodes'|'sources'|'servers'} name
 * @param {Array<object>} rules rule analyzer (boleh ditambah `enabled`, `saveAs`, `value` hasil edit user)
 * @param {{ endpoint?: string }} ctx
 * @returns {{ workflow: object|null, status: 'READY'|'NEEDS_REVIEW', reasons: string[] }}
 */
export function buildInferredWorkflow(name, rules = [], { endpoint = '' } = {}) {
  const get = (id) => rules.find((r) => r.id === id)

  if (name === 'search') {
    const title = get('search-title')
    const url = get('search-url')
    const query = get('search-query')
    const image = get('search-image')
    const reasons = []
    if (!active(title)) reasons.push('Judul hasil pencarian (search-title) belum terbukti/aktif.')
    if (!active(url)) reasons.push('URL detail (search-url, atribut href) belum terbukti/aktif.')
    if (!active(query)) reasons.push('Parameter query pencarian (form GET) tidak ditemukan — URL search tidak dikarang.')
    else if ((query.meta?.method || 'GET').toUpperCase() !== 'GET') reasons.push('Form pencarian bukan GET — butuh konfigurasi manual.')
    if (reasons.length) return fail(reasons)
    const href = resolveSearchUrl(query.meta?.action, endpoint)
    if (!href) return fail(['Provider Endpoint belum valid — URL search tidak dapat disusun dari action form.'])
    const fields = [{ key: 'titles', role: 'titles', rule: title }, { key: 'urls', role: 'urls', rule: url }]
    const note = []
    if (active(image)) fields.push({ key: 'images', role: 'images', rule: image })
    else note.push('images tidak dipetakan (tidak ada bukti gambar per item).')
    return frame(
      name,
      'Cari judul (dihasilkan otomatis dari bukti sampel)',
      { method: 'GET', url: href, query: { [query.value]: '{{query}}' } },
      fields,
      'SEARCH',
      note
    )
  }

  if (name === 'episodes') {
    const url = get('episode-url')
    if (!active(url)) return fail(['URL episode (episode-url, atribut href) belum terbukti/aktif.'])
    const fields = [{ key: 'episodeUrls', role: 'urls', rule: url }]
    const note = []
    const number = get('episode-number')
    const title = get('episode-title')
    if (active(number)) fields.push({ key: 'episodeNumbers', role: 'numbers', rule: number })
    else note.push('episodeNumbers tidak dipetakan — parser memakai indeks+1 sebagai nomor.')
    if (active(title)) fields.push({ key: 'episodeTitles', role: 'titles', rule: title })
    return frame(name, 'Daftar episode (dihasilkan otomatis dari bukti sampel)', { method: 'GET', url: '{{url}}' }, fields, 'EPISODES', note)
  }

  if (name === 'sources') {
    const direct = [get('stream-iframe'), get('stream-source')].find(active)
    const mirror = get('server-selector')
    const note = []
    if (direct) {
      if (active(mirror)) note.push('server-selector diabaikan: iframe/media langsung dipakai sebagai streams.')
      return frame(name, 'Sumber stream (dihasilkan otomatis dari bukti sampel)', { method: 'GET', url: '{{url}}' }, [{ key: 'streams', role: 'streams', rule: direct }], 'SOURCES', note)
    }
    if (active(mirror)) {
      const label = { ...mirror, attribute: null }
      return frame(
        name,
        'Mirror dropdown (dihasilkan otomatis dari bukti sampel)',
        { method: 'GET', url: '{{url}}' },
        [{ key: 'streams', role: 'streams', rule: mirror }, { key: 'servers', role: 'servers', rule: label }],
        'SOURCES',
        ['Nilai opsi dipakai apa adanya sebagai URL stream — verifikasi lewat gerbang parser engine.']
      )
    }
    return fail(['Tidak ada iframe/media/dropdown mirror yang terbukti pada sampel Watch.'])
  }

  // servers: parser engine butuh list Map; op `select` hanya menghasilkan string → tidak dapat disusun otomatis.
  return fail(['Workflow servers tidak dibuat otomatis: engine butuh list Map dan tidak ada bukti struktur server per item.'])
}

/** Bangun semua workflow dari rule per kelompok; kembalikan workflows + status/alasan per workflow. */
export function inferWorkflows(rulesByGroup, ctx) {
  const groups = {
    search: rulesByGroup?.search || [],
    episodes: rulesByGroup?.episodes || [],
    sources: rulesByGroup?.stream || [],
    servers: rulesByGroup?.stream || [],
  }
  const workflows = {}
  const report = {}
  for (const [name, rules] of Object.entries(groups)) {
    const r = buildInferredWorkflow(name, rules, ctx)
    report[name] = { status: r.status, reasons: r.reasons }
    if (r.workflow) workflows[name] = r.workflow
  }
  return { workflows, report }
}
