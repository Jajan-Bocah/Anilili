// domStructure.js — helper DOM murni (tanpa global document) untuk analyzer berbasis bukti struktur.
// Tidak mengenal provider tertentu: hanya struktur (tag/class/atribut) dan hitungan elemen berulang.

const STATE_CLASS = /^(active|current|selected|first|last|odd|even|open|show|hide|hidden|clearfix|disabled|focus|hover|visible|in|out)$/i
/** Class yang aman dipakai di selector: tanpa digit, bukan token status/state. */
export function stableClasses(el, max = 2) {
  return Array.from(el.classList || [])
    .filter((c) => /^[A-Za-z_][\w-]*$/.test(c) && !/\d/.test(c) && !STATE_CLASS.test(c))
    .slice(0, max)
}

/** tag + class stabil, mis. `div.listupd`. id hanya dipakai jika stabil (tanpa digit). */
export function elementSig(el) {
  const tag = el.tagName.toLowerCase()
  const id = el.getAttribute('id')
  if (id && /^[A-Za-z_][\w-]*$/.test(id) && !/\d/.test(id)) return `${tag}#${id}`
  return tag + stableClasses(el).map((c) => `.${c}`).join('')
}

/** Rantai signature dari elemen ke atas (maks `depth` level, berhenti sebelum body/html). */
export function ancestorChain(el, depth) {
  const chain = [elementSig(el)]
  let p = el.parentElement
  while (p && chain.length < depth && !['BODY', 'HTML'].includes(p.tagName)) {
    chain.unshift(elementSig(p))
    p = p.parentElement
  }
  return chain
}

export const NAV_TAGS = new Set(['NAV', 'HEADER', 'FOOTER', 'ASIDE'])
const NAV_CLASS = /(^|[-_])(menu|nav|navbar|footer|breadcrumb|pagination|dropdown|sidebar|widget|aside)([-_]|$)/i

/** true bila elemen berada di area navigasi/boilerplate (tag nav/header/footer/aside atau class menu/nav/…). */
export function inBoilerplate(el) {
  for (let p = el; p && p.nodeType === 1; p = p.parentElement) {
    if (NAV_TAGS.has(p.tagName) || p.getAttribute('role') === 'navigation') return true
    if (Array.from(p.classList || []).some((c) => NAV_CLASS.test(c))) return true
  }
  return false
}

export const textOf = (el) => (el.textContent || '').replace(/\s+/g, ' ').trim()

/** href yang bisa dinavigasi ke halaman lain (bukan #, javascript:, mailto:, tel:). */
export function isNavigableHref(href) {
  const h = (href || '').trim()
  return h !== '' && !h.startsWith('#') && !/^(javascript|mailto|tel|data):/i.test(h)
}

/** Cari selector terpendek (≥ minDepth level) yang cocok persis dengan himpunan `members`. */
function shortestExactSelector(dom, chain, members, minDepth) {
  const set = new Set(members)
  for (let k = Math.min(minDepth, chain.length); k <= chain.length; k++) {
    const selector = chain.slice(chain.length - k).join(' > ')
    let found
    try {
      found = Array.from(dom.querySelectorAll(selector))
    } catch {
      continue
    }
    if (found.length === set.size && found.every((e) => set.has(e))) return { selector, elements: found }
  }
  const selector = chain.join(' > ')
  return { selector, elements: Array.from(dom.querySelectorAll(selector)) }
}

/**
 * Kelompokkan elemen (default anchor[href]) berdasarkan rantai ancestor struktural.
 * @returns {{ selector: string, elements: Element[] }[]} grup berukuran ≥ minSize
 */
export function groupRepeated(dom, { query = 'a[href]', depth = 4, minSize = 2, minDepth = 3 } = {}) {
  const buckets = new Map()
  for (const el of dom.querySelectorAll(query)) {
    const chain = ancestorChain(el, depth)
    const key = chain.join(' > ')
    if (!buckets.has(key)) buckets.set(key, { chain, elements: [] })
    buckets.get(key).elements.push(el)
  }
  const groups = []
  for (const { chain, elements } of buckets.values()) {
    if (elements.length < minSize) continue
    const { selector, elements: matched } = shortestExactSelector(dom, chain, elements, minDepth)
    if (matched.length >= minSize) groups.push({ selector, elements: matched })
  }
  return groups
}

export const uniqueRatio = (values) => (values.length ? new Set(values).size / values.length : 0)
export const ratio = (items, pred) => (items.length ? items.filter(pred).length / items.length : 0)

/** Token alfanumerik huruf kecil (panjang ≥ 2) dari string/URL. */
export function tokens(s) {
  return String(s || '').toLowerCase().split(/[^a-z0-9]+/).filter((t) => t.length >= 2)
}

/** Selector deskriptif tunggal untuk elemen (dipakai untuk evidence), tanpa `html > body`. */
export function describeSelector(el, depth = 3) {
  return ancestorChain(el, depth).join(' > ')
}

/** Selector terpendek (≥ minDepth level) yang cocok persis dengan `elements`; jatuh ke rantai penuh bila tak ada. */
export function selectorForSet(dom, elements, minDepth = 2) {
  const chain = ancestorChain(elements[0], 6)
  return shortestExactSelector(dom, chain, elements, minDepth)
}
