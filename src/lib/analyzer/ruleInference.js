// ruleInference.js — inferensi aturan berbasis BUKTI struktur DOM (bukan tebakan root/heading pertama).
// Prinsip:
//  - Kandidat = grup elemen berulang (struktur sama). Skor dari bukti: href unik & navigable, title (atribut/heading/teks),
//    gambar, daftar episode (pola href/teks), bukan area navigasi. Tidak ada nama class/situs yang di-hardcode.
//  - Rule memuat selector + atribut DOM (href/src/title) yang benar-benar ada di sampel, plus contoh nilai (evidence).
//  - Confidence rendah / ambigu → NEEDS_MANUAL_REVIEW (bukan DETECTED). Tidak ada bukti → NOT_DETECTED, value null.

import {
  describeSelector,
  groupRepeated,
  inBoilerplate,
  isNavigableHref,
  ratio,
  selectorForSet,
  textOf,
  tokens,
  uniqueRatio,
} from './domStructure.js'

export const RULE_STATUS = {
  DETECTED: 'DETECTED',
  NOT_DETECTED: 'NOT_DETECTED',
  NEEDS_MANUAL_REVIEW: 'NEEDS_MANUAL_REVIEW',
}

const DETECT_MIN = 0.65
const REVIEW_MIN = 0.4
const AMBIGUITY_GAP = 0.08

/** Rule standar. `extra`: attribute, mode, role, meta, sample (contoh nilai hasil replay). */
function makeRule(id, type, value, confidence, evidence, status, extra = {}) {
  return { id, type, value, confidence, evidence, status, validated: false, attribute: null, ...extra }
}

const statusFor = (score) => (score >= DETECT_MIN ? RULE_STATUS.DETECTED : score >= REVIEW_MIN ? RULE_STATUS.NEEDS_MANUAL_REVIEW : RULE_STATUS.NOT_DETECTED)
const round = (n) => Math.round(Math.min(1, n) * 100) / 100
const clip = (s, n = 70) => (s.length > n ? `${s.slice(0, n)}…` : s)

/** Rule kosong (tanpa bukti) yang tetap tampil di UI sebagai NOT_DETECTED. */
const absent = (id, why, extra) => makeRule(id, 'css-selector', null, 0.3, [why], RULE_STATUS.NOT_DETECTED, extra)

// ───────────────────────── Search ─────────────────────────

/** Medan hasil per item: title/href/image — hanya jika SETIAP item punya (list paralel harus sejajar). */
function searchFields(group) {
  const { selector: S, elements: els } = group
  const n = els.length
  const fields = { urls: { selector: S, attribute: 'href' } }
  let titleEvidence = 0

  if (els.every((a) => (a.getAttribute('title') || '').trim())) {
    fields.titles = { selector: S, attribute: 'title' }
    titleEvidence = 0.2
  } else {
    const headingTag = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6'].find((t) => els.every((a) => a.querySelectorAll(t).length === 1 && textOf(a.querySelector(t))))
    if (headingTag) {
      fields.titles = { selector: `${S} ${headingTag}`, attribute: null }
      titleEvidence = 0.15
    } else if (els.every((a) => textOf(a) && !Array.from(a.children).some((c) => textOf(c)))) {
      // Teks anchor hanya sah bila murni teks: anchor berisi elemen bersarang bertulisan (rating, status…) menghasilkan judul sampah.
      fields.titles = { selector: S, attribute: null }
      titleEvidence = 0.1
    }
  }

  const withImg = els.every((a) => a.querySelectorAll('img').length === 1)
  let imgEvidence = 0
  if (withImg) {
    const attr = ['src', 'data-src'].find((k) => els.every((a) => (a.querySelector('img').getAttribute(k) || '').trim()))
    if (attr) {
      fields.images = { selector: `${S} img`, attribute: attr }
      imgEvidence = 0.2
    }
  }
  return { fields, titleEvidence, imgEvidence, n }
}

function scoreSearchGroup(group) {
  const hrefs = group.elements.map((a) => a.getAttribute('href') || '')
  const uniq = uniqueRatio(hrefs)
  const nav = ratio(hrefs, isNavigableHref)
  if (uniq < 0.9 || nav < 0.9) return null
  const f = searchFields(group)
  if (!f.fields.titles) return null
  const sizeFactor = Math.min(1, Math.log2(f.n) / 4) // daftar lebih panjang = lebih mungkin konten utama
  const clean = group.elements.some((a) => inBoilerplate(a)) ? 0 : 0.15
  const score = 0.3 * uniq + 0.1 * nav + f.titleEvidence + f.imgEvidence + 0.1 * sizeFactor + clean
  return { ...group, ...f, score: round(score), uniq }
}

/** Contoh hasil (title/url/image) dari tiga selector — bukti bahwa field sejajar. */
function sampleItems(dom, fields, limit = 2) {
  const pick = (f) => (f ? Array.from(dom.querySelectorAll(f.selector)).map((e) => (f.attribute ? e.getAttribute(f.attribute) || '' : textOf(e))) : [])
  const [t, u, i] = [pick(fields.titles), pick(fields.urls), pick(fields.images)]
  return Array.from({ length: Math.min(limit, u.length) }, (_, k) => ({ title: t[k] ?? '', url: u[k] ?? '', image: i[k] ?? null }))
}

/** Parameter query dari form pencarian (bukti: input teks bernama/placeholder pencarian di dalam <form>). */
function analyzeSearchQuery(dom) {
  const NAME = /^(s|q|query|search|keyword|keywords|term|cari|k)$/i
  const HINT = /search|cari|query/i
  let best = null
  for (const form of dom.querySelectorAll('form')) {
    for (const input of form.querySelectorAll('input[name]')) {
      const type = (input.getAttribute('type') || 'text').toLowerCase()
      if (!['text', 'search'].includes(type)) continue
      const name = input.getAttribute('name')
      let score = 0
      if (type === 'search') score += 0.3
      if (NAME.test(name)) score += 0.3
      if (HINT.test(`${input.id} ${input.className} ${input.getAttribute('placeholder') || ''}`)) score += 0.2
      if (form.getAttribute('role') === 'search' || /SearchAction/i.test(form.getAttribute('itemtype') || '') || HINT.test(`${form.id} ${form.className}`)) score += 0.3
      if (!best || score > best.score) best = { form, name, score }
    }
  }
  if (!best || best.score < 0.5) {
    return absent('search-query', 'Form pencarian (input teks/search di <form>) tidak ditemukan — parameter query harus diisi manual', { type: 'query-param' })
  }
  const method = (best.form.getAttribute('method') || 'GET').toUpperCase()
  const action = best.form.getAttribute('action') || ''
  const get = method === 'GET'
  const conf = round(Math.min(1, best.score) * (get ? 1 : 0.5))
  return makeRule('search-query', 'query-param', best.name, conf, [
    `Form ${method} action="${action || '(halaman ini)'}" dengan input name="${best.name}"`,
    ...(get ? [] : ['Method POST — body belum diinferensi, perlu review manual']),
  ], get ? statusFor(conf) : RULE_STATUS.NEEDS_MANUAL_REVIEW, { meta: { action, method } })
}

function analyzeSearch(dom) {
  const scored = groupRepeated(dom).map(scoreSearchGroup).filter(Boolean).sort((a, b) => b.score - a.score)
  const rules = []
  const best = scored[0]
  if (!best) {
    rules.push(
      absent('search-container', 'Tidak ada grup link detail berulang (href unik, ber-title) — minimal 2 item'),
      absent('search-title', 'Judul hasil tidak ditemukan dengan konsisten'),
      absent('search-url', 'URL detail hasil tidak ditemukan'),
      absent('search-image', 'Gambar hasil tidak ditemukan'),
    )
  } else {
    const rival = scored.find((s) => s.selector !== best.selector)
    const ambiguous = rival && best.score - rival.score < AMBIGUITY_GAP
    let status = statusFor(best.score)
    const ev = [
      `${best.n} item berulang (${best.selector}), href unik ${Math.round(best.uniq * 100)}%`,
      ...sampleItems(dom, best.fields).map((s) => `contoh: "${clip(s.title)}" → ${clip(s.url)}${s.image ? ` | ${clip(s.image)}` : ''}`),
    ]
    if (ambiguous) {
      status = RULE_STATUS.NEEDS_MANUAL_REVIEW
      ev.push(`Ambigu: grup lain ${rival.selector} (${rival.elements.length} item) berskor setara — pilih manual`)
    } else if (best.n < 3 && status === RULE_STATUS.DETECTED) {
      ev.push(`Hanya ${best.n} item di sampel — pola belum teruji pada banyak hasil`)
    }
    const conf = best.score
    rules.push(
      makeRule('search-container', 'css-selector', best.selector, conf, ev, status, { role: 'item', sample: sampleItems(dom, best.fields) }),
      makeRule('search-title', 'css-selector', best.fields.titles.selector, conf, [`Judul dari ${best.fields.titles.attribute ? `atribut ${best.fields.titles.attribute}` : 'teks'} (${best.n}/${best.n} item)`], status, { attribute: best.fields.titles.attribute, role: 'titles' }),
      makeRule('search-url', 'css-selector', best.fields.urls.selector, conf, [`URL dari atribut href (${best.n}/${best.n} item, unik)`], status, { attribute: 'href', role: 'urls' }),
      best.fields.images
        ? makeRule('search-image', 'css-selector', best.fields.images.selector, conf, [`Gambar dari atribut ${best.fields.images.attribute} (${best.n}/${best.n} item)`], status, { attribute: best.fields.images.attribute, role: 'images' })
        : absent('search-image', 'Tidak semua item punya tepat satu gambar — field images tidak dipetakan (tidak mengarang)'),
    )
  }
  rules.push(analyzeSearchQuery(dom))
  return rules
}

// ───────────────────────── Episodes ─────────────────────────

const EPISODE_HINT = /(episode|\beps?\b|\bep[-_. ]?\d|ova\b|movie)/i
const NUMBER_TEXT = /^\D{0,15}\d+(\.\d+)?\D{0,3}$/
const DATE_LIKE = /\b(19|20)\d{2}\b/

/** Sub-elemen anak (signature relatif) yang muncul tepat sekali per item → kandidat field nomor/judul. */
function childFields(group) {
  const { selector: S, elements: els } = group
  const n = els.length
  const sigs = new Map()
  for (const a of els) {
    for (const c of a.querySelectorAll('*')) {
      if (c.children.length > 1) continue
      const cls = Array.from(c.classList).filter((x) => /^[A-Za-z_][\w-]*$/.test(x) && !/\d/.test(x)).slice(0, 2)
      if (!cls.length) continue
      const sig = c.tagName.toLowerCase() + cls.map((x) => `.${x}`).join('')
      sigs.set(sig, (sigs.get(sig) || 0) + 1)
    }
  }
  const out = []
  for (const [sig, count] of sigs) {
    if (count !== n) continue
    const sel = `${S} ${sig}`
    let matched
    try {
      matched = Array.from(els[0].ownerDocument.querySelectorAll(sel))
    } catch {
      continue
    }
    if (matched.length !== n) continue
    const texts = matched.map(textOf)
    out.push({ selector: sel, texts, matched })
  }
  return out
}

function episodeFields(group) {
  const { selector: S, elements: els } = group
  const fields = { urls: { selector: S, attribute: 'href' } }
  const notes = []
  const kids = childFields(group)
  const hrefs = els.map((a) => a.getAttribute('href') || '')

  // Nomor: child numerik tepat sekali per item (≥90% teks berbentuk angka), atau teks anchor bila numerik.
  const numeric = kids.map((k) => ({ ...k, r: ratio(k.texts, (t) => NUMBER_TEXT.test(t) && t.length <= 14) })).filter((k) => k.r >= 0.9).sort((a, b) => b.r - a.r)
  if (numeric[0]) {
    fields.numbers = { selector: numeric[0].selector, attribute: null, texts: numeric[0].texts }
    const dirty = numeric[0].texts.filter((t) => !/^\d+(\.\d+)?$/.test(t))
    if (dirty.length) notes.push(`${dirty.length} label nomor tidak bersih (mis. "${clip(dirty[0], 20)}") — parser engine membuang non-numerik / menyatukan digit`)
  } else if (ratio(els.map(textOf), (t) => NUMBER_TEXT.test(t)) >= 0.9) {
    fields.numbers = { selector: S, attribute: null, texts: els.map(textOf) }
  } else {
    notes.push('Nomor episode tidak dapat diinferensi dengan bersih — engine akan memakai urutan (1..n)')
  }

  // Peringatan kualitas data: nomor duplikat dan lonjakan ≥50.
  if (fields.numbers) {
    const nums = fields.numbers.texts.map((t) => {
      const m = String(t).match(/\d+(\.\d+)?/)
      return m ? parseFloat(m[0]) : NaN
    }).filter((n) => !Number.isNaN(n))
    const seen = new Set()
    const dupes = nums.filter((n) => { const dup = seen.has(n); seen.add(n); return dup })
    if (dupes.length > 0) {
      notes.push(`${dupes.length} nomor episode duplikat — engine memakai indeks slug sebagai key; duplikat dapat menimpa cache`)
    }
    const sorted = [...nums].sort((a, b) => a - b)
    for (let i = 1; i < sorted.length; i++) {
      if (sorted[i] - sorted[i - 1] >= 50) {
        notes.push(`Lonjakan nomor episode ≥50 (mis. ${sorted[i - 1]}→${sorted[i]}) — verifikasi apakah nomor episode bercampur dengan nomor seri`)
        break
      }
    }
  }

  // Judul: child non-numerik, unik, bukan tanggal, paling banyak berbagi token dengan slug href.
  const slugTokens = hrefs.map((h) => new Set(tokens(h)))
  const titleCands = kids
    .filter((k) => k.selector !== fields.numbers?.selector)
    .map((k) => {
      const overlap = k.texts.reduce((s, t, i) => {
        const tk = tokens(t)
        return s + (tk.length ? tk.filter((x) => slugTokens[i].has(x)).length / tk.length : 0)
      }, 0) / k.texts.length
      return { ...k, uniq: uniqueRatio(k.texts), overlap, dated: ratio(k.texts, (t) => DATE_LIKE.test(t)) }
    })
    .filter((k) => k.uniq >= 0.8 && k.dated < 0.5 && k.overlap >= 0.5)
    .sort((a, b) => b.overlap - a.overlap)
  if (titleCands[0]) fields.titles = { selector: titleCands[0].selector, attribute: null }
  return { fields, notes }
}

function scoreEpisodeGroup(group) {
  const els = group.elements
  if (els.length < 3) return null
  const hrefs = els.map((a) => a.getAttribute('href') || '')
  const uniq = uniqueRatio(hrefs)
  const nav = ratio(hrefs, isNavigableHref)
  if (uniq < 0.9 || nav < 0.9) return null
  const episodeness = ratio(els, (a) => EPISODE_HINT.test(a.getAttribute('href') || '') || EPISODE_HINT.test(textOf(a)))
  if (episodeness < 0.6) return null
  const { fields, notes } = episodeFields(group)
  const clean = els.some((a) => inBoilerplate(a)) ? 0 : 0.15
  const sizeFactor = Math.min(1, Math.log2(els.length) / 4)
  // Nomor murni angka (mis. "218") = bukti daftar episode satu seri; label bebas ("Episode 18") bukti lebih lemah.
  const numberClean = fields.numbers ? ratio(fields.numbers.texts, (t) => /^\d+(\.\d+)?$/.test(t)) : 0
  const score = 0.25 * episodeness + 0.2 * uniq + 0.05 * nav + 0.1 * sizeFactor + (fields.numbers ? 0.05 : 0) + 0.15 * numberClean + clean
  return { ...group, fields, notes, score: round(score), episodeness, uniq }
}

function analyzePagination(dom) {
  const links = Array.from(dom.querySelectorAll('a[href]')).filter((a) => {
    const t = textOf(a).toLowerCase()
    return /^(next|selanjutnya|lanjut|›|»|→|>>)/.test(t) || /[?&](page|p)=\d+/i.test(a.getAttribute('href') || '')
  })
  if (!links.length) return absent('episode-pagination', 'Pagination tidak ditemukan')
  return makeRule('episode-pagination', 'css-selector', selectorForSet(dom, [links[0]], 2).selector, 0.4,
    [`Ditemukan ${links.length} link pagination — pagination belum didukung generator otomatis (NOT_TESTED), review manual`],
    RULE_STATUS.NEEDS_MANUAL_REVIEW)
}

function analyzeEpisodes(dom) {
  const scored = groupRepeated(dom, { minSize: 3 }).map(scoreEpisodeGroup).filter(Boolean).sort((a, b) => b.score - a.score)
  const rules = []
  const best = scored[0]
  if (!best) {
    rules.push(
      absent('episode-list', 'Tidak ada grup link episode berulang (href unik dengan pola episode, minimal 3)'),
      absent('episode-url', 'URL episode tidak ditemukan'),
      absent('episode-number', 'Nomor episode tidak ditemukan'),
      absent('episode-title', 'Judul episode tidak ditemukan'),
    )
  } else {
    const rival = scored.find((s) => s.selector !== best.selector)
    const ambiguous = rival && best.score - rival.score < AMBIGUITY_GAP
    const status = ambiguous ? RULE_STATUS.NEEDS_MANUAL_REVIEW : statusFor(best.score)
    const n = best.elements.length
    const ev = [
      `${n} link episode berulang (${best.selector}), href unik ${Math.round(best.uniq * 100)}%, pola episode ${Math.round(best.episodeness * 100)}%`,
      ...best.notes,
      ...(ambiguous ? [`Ambigu: grup ${rival.selector} (${rival.elements.length} item) berskor setara — pilih manual`] : []),
    ]
    const c = best.score
    const f = best.fields
    rules.push(
      makeRule('episode-list', 'css-selector', best.selector, c, ev, status, { role: 'item' }),
      makeRule('episode-url', 'css-selector', f.urls.selector, c, [`URL episode dari atribut href (${n} item)`], status, { attribute: 'href', role: 'episodeUrls' }),
      f.numbers
        ? makeRule('episode-number', 'css-selector', f.numbers.selector, c, [`Nomor dari teks ${f.numbers.selector === best.selector ? 'link' : 'elemen anak'} (${n} item sejajar)`], status, { role: 'episodeNumbers' })
        : absent('episode-number', best.notes[0] || 'Nomor episode tidak ditemukan'),
      f.titles
        ? makeRule('episode-title', 'css-selector', f.titles.selector, c, [`Judul dari teks elemen anak (${n} item sejajar, selaras dengan slug href)`], status, { role: 'episodeTitles' })
        : absent('episode-title', 'Judul episode tidak dapat diinferensi dengan bukti (opsional)'),
    )
  }
  rules.push(analyzePagination(dom))
  return rules
}

// ───────────────────────── Stream ─────────────────────────

const NOISE_SRC = /(facebook|doubleclick|googletag|google\.com\/recaptcha|disqus|twitter\.com|platform\.|adsbygoogle)/i
const PLAYER_HINT = /(player|embed|video|stream|watch)/i
const MEDIA_EXT = /\.(m3u8|mpd|mp4|webm)(\?|#|$)/i
const BASE64_BLOB = /^[A-Za-z0-9+/=\s]{40,}$/

function scoreIframe(el) {
  const src = (el.getAttribute('src') || '').trim()
  if (!/^(https?:)?\/\//i.test(src) || NOISE_SRC.test(src)) return null
  const size = (v) => /%$/.test(v || '') || parseInt(v || '0', 10) >= 300
  let s = 0.5
  if (el.hasAttribute('allowfullscreen')) s += 0.15
  if (PLAYER_HINT.test(src) || PLAYER_HINT.test(`${el.className} ${el.parentElement?.className || ''} ${el.parentElement?.id || ''}`)) s += 0.15
  if (size(el.getAttribute('width')) || size(el.getAttribute('height'))) s += 0.1
  if (!inBoilerplate(el)) s += 0.1
  return round(s)
}

function analyzeStream(dom) {
  const rules = []
  const iframes = Array.from(dom.querySelectorAll('iframe[src]')).map((el) => ({ el, score: scoreIframe(el) })).filter((x) => x.score !== null).sort((a, b) => b.score - a.score)
  if (iframes.length) {
    const top = iframes[0]
    const { selector, elements } = selectorForSet(dom, [top.el], 2)
    const rivals = iframes.filter((x) => x.el !== top.el && top.score - x.score < AMBIGUITY_GAP)
    const status = rivals.length ? RULE_STATUS.NEEDS_MANUAL_REVIEW : statusFor(top.score)
    rules.push(makeRule('stream-iframe', 'css-selector', selector, top.score, [
      `iframe src nyata di sampel: ${clip(top.el.getAttribute('src'), 90)}`,
      `selector cocok ${elements.length} elemen`,
      ...(rivals.length ? [`Ambigu: ${rivals.length} iframe lain berskor setara`] : []),
    ], status, { attribute: 'src', role: 'streams', sample: [top.el.getAttribute('src')] }))
  } else {
    rules.push(absent('stream-iframe', 'Iframe player (src http/https) tidak ditemukan'))
  }

  const media = Array.from(dom.querySelectorAll('video[src], source[src]')).filter((el) => MEDIA_EXT.test(el.getAttribute('src') || ''))
  if (media.length) {
    const { selector } = selectorForSet(dom, media, 2)
    rules.push(makeRule('stream-source', 'css-selector', selector, 0.85, [`${media.length} sumber media langsung (m3u8/mpd/mp4/webm) di HTML statis`], RULE_STATUS.DETECTED,
      { attribute: 'src', role: 'streams', sample: media.slice(0, 2).map((m) => m.getAttribute('src')) }))
  } else {
    rules.push(absent('stream-source', 'Sumber media (m3u8/mpd/mp4) tidak ditemukan dalam HTML statis'))
  }

  // Dropdown server: hanya didukung engine bila value = URL (streams+servers sejajar). Blob base64 → tidak.
  // Bukti "ini daftar server": nama/id/class/aria-label/opsi pertama menyebut server|mirror|player|stream|host (bukan filter).
  const SERVER_HINT = /(server|mirror|player|stream|host)/i
  const select = Array.from(dom.querySelectorAll('select')).find((s) =>
    s.options.length >= 2 && SERVER_HINT.test(`${s.name} ${s.id} ${s.className} ${s.getAttribute('aria-label') || ''} ${s.options[0]?.textContent || ''}`))
  if (select) {
    const opts = Array.from(select.options).filter((o) => (o.getAttribute('value') || '').trim())
    const urlLike = opts.filter((o) => /^(https?:)?\/\//i.test(o.getAttribute('value')))
    const blobs = opts.filter((o) => BASE64_BLOB.test(o.getAttribute('value')))
    const { selector } = selectorForSet(dom, [select], 2)
    const allUrls = opts.length >= 2 && urlLike.length === opts.length
    const hasPlaceholder = select.options.length !== opts.length
    rules.push(makeRule('server-selector', 'css-selector', `${selector} option${hasPlaceholder ? ':not([value=""])' : ''}`,
      allUrls ? 0.75 : 0.45, [
        `${select.options.length} opsi di ${selector}; ${urlLike.length} value berupa URL, ${blobs.length} blob base64`,
        ...(allUrls ? [] : ['Value bukan URL (mis. base64 iframe) — engine TIDAK men-decode per item; servers/streams tidak dipetakan otomatis (BLOCKED)']),
      ], allUrls ? RULE_STATUS.DETECTED : RULE_STATUS.NEEDS_MANUAL_REVIEW, { attribute: 'value', role: 'mirrors' }))
  } else {
    rules.push(absent('server-selector', 'Server selector tidak ditemukan'))
  }
  return rules
}

// ───────────────────────── Koordinator ─────────────────────────

/**
 * @param {{html: string|null}} parseResult
 * @returns {{search: object[], episodes: object[], stream: object[], summary: object}}
 */
export function inferRules(parseResult) {
  if (!parseResult.html) {
    return { search: [], episodes: [], stream: [], summary: { total: 0, detected: 0, notDetected: 0, needsReview: 0 } }
  }
  const dom = new DOMParser().parseFromString(parseResult.html, 'text/html')
  const search = analyzeSearch(dom)
  const episodes = analyzeEpisodes(dom)
  const stream = analyzeStream(dom)
  const all = [...search, ...episodes, ...stream]
  const count = (s) => all.filter((r) => r.status === s).length
  return {
    search,
    episodes,
    stream,
    summary: {
      total: all.length,
      detected: count(RULE_STATUS.DETECTED),
      notDetected: count(RULE_STATUS.NOT_DETECTED),
      needsReview: count(RULE_STATUS.NEEDS_MANUAL_REVIEW),
    },
  }
}

export { describeSelector }
