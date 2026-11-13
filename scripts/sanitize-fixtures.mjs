/* global console, process, Buffer */
// Usage: node scripts/sanitize-fixtures.mjs <path-ke-klon-anilili>
// Membaca 4 fixture HTML nyata dari klon repo Android Anilili (read-only), menyanitasi,
// lalu menulis tests/fixtures/real/** + MANIFEST.json. Deterministik (tanpa timestamp).
// Exit 1 bila selector count berubah atau masih ada pola sensitif.
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const cloneRoot = process.argv[2]
if (!cloneRoot) {
  console.error('Usage: node scripts/sanitize-fixtures.mjs <path-ke-klon-anilili>')
  process.exit(1)
}
const outRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../tests/fixtures/real')
const SRC_DIR = 'app/src/test/resources/fixtures'
const FILES = ['anichin/search.htm', 'anichin/series.htm', 'anichin/watch.htm', 'kuramanime/search.htm']

const SELECTORS = [
  '.listupd article.bs a.tip',
  '.eplister li a',
  '.eplister li .epl-num',
  '.eplister li .epl-title',
  'select.mirror option',
  'select.mirror option[value]',
  'a.ep-button[href*="/episode/"]',
  'img[src]',
  'title',
  '[href]',
]
// Nilai atribut yang HARUS utuh (base64 mirror) — tidak disentuh redaksi apa pun.
const MIRROR = 'select.mirror option'

const sha256 = (b) => createHash('sha256').update(b).digest('hex')
const R = '[REDACTED]'
const KEYS = 'access_token|refresh_token|id_token|api[_-]?key|apikey|auth[_-]?token|csrf[_-]?token|xsrf[_-]?token|_token|token|nonce|secret|sessionid|session'
const IP = String.raw`\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|127\.0\.0\.1)\b`

// Pola redaksi: [kategori, regex, pengganti]
const RULES = [
  ['email', /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g, '[REDACTED_EMAIL]'],
  ['bearer', /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi, `Bearer ${R}`],
  ['cookie', /\b(set-cookie|cookie)(\s*[:=]\s*)[^\s;"'<>]{4,}/gi, `$1$2${R}`],
  ['tokenQuery', new RegExp(`([?&;](?:amp;)?(?:${KEYS}|sig|signature|auth|key)=)[^&\\s"'#<>]+`, 'gi'), `$1${R}`],
  ['token', new RegExp(`\\b(${KEYS})(["']?\\s*[:=]\\s*["']?)(?!\\[REDACTED)[A-Za-z0-9._~+/=-]{8,}`, 'gi'), `$1$2${R}`],
  ['privateIp', new RegExp(IP, 'g'), '[REDACTED_IP]'],
]
const SENSITIVE_NAME = new RegExp(`^(?:${KEYS}|csrf-token|csrf|xsrf-token|authenticity_token)$`, 'i')

const newDom = (html) => new JSDOM(html, { runScripts: 'outside-only' }) // noscript = teks mentah, seperti browser

function bump(counts, k, n = 1) {
  counts[k] = (counts[k] ?? 0) + n
}

function redactString(s, counts) {
  let out = s
  for (const [cat, re, rep] of RULES) {
    out = out.replace(re, (...m) => {
      bump(counts, cat)
      return rep.replace(/\$(\d)/g, (_, i) => m[Number(i)] ?? '')
    })
  }
  return out
}

function sanitize(html) {
  const dom = newDom(html)
  const { document } = dom.window
  const counts = {}
  const remove = (sel, cat) => {
    const els = [...document.querySelectorAll(sel)]
    els.forEach((e) => e.remove())
    if (els.length) bump(counts, cat, els.length)
  }
  remove('script', 'script')
  remove('noscript', 'noscript')
  remove('style', 'style')

  const mirrorOpts = new Set(document.querySelectorAll(MIRROR))
  for (const el of document.querySelectorAll('*')) {
    for (const a of [...el.attributes]) {
      if (/^on/i.test(a.name)) {
        el.removeAttribute(a.name)
        bump(counts, 'eventHandler')
      } else if (el.hasAttribute('nonce') && a.name === 'nonce') {
        el.removeAttribute('nonce')
        bump(counts, 'token')
      } else if (mirrorOpts.has(el) && a.name === 'value') {
        continue
      } else {
        const v = redactString(a.value, counts)
        if (v !== a.value) el.setAttribute(a.name, v)
      }
    }
    // <meta name="csrf-token" content=..> dan <input name="_token" value=..>
    const name = el.getAttribute('name') ?? ''
    const valAttr = el.tagName === 'META' ? 'content' : el.tagName === 'INPUT' ? 'value' : null
    if (valAttr && SENSITIVE_NAME.test(name) && (el.getAttribute(valAttr) ?? '') !== '' && el.getAttribute(valAttr) !== R) {
      el.setAttribute(valAttr, R)
      bump(counts, 'token')
    }
  }
  const walker = document.createTreeWalker(document, 0x80 | 0x4) // COMMENT | TEXT
  const nodes = []
  while (walker.nextNode()) nodes.push(walker.currentNode)
  for (const n of nodes) {
    if (n.nodeType === 8) {
      n.remove()
      bump(counts, 'htmlComment')
    } else {
      const v = redactString(n.nodeValue, counts)
      if (v !== n.nodeValue) n.nodeValue = v
    }
  }
  return { html: dom.serialize(), counts }
}

function measure(html) {
  const { document } = newDom(html).window
  // Isi <noscript> dihapus oleh sanitasi, jadi tidak dihitung (jsoup/jsdom mem-parse-nya sebagai elemen).
  const all = (s) => [...document.querySelectorAll(s)]
  const outside = (s) => all(s).filter((e) => !e.closest('noscript'))
  const sel = Object.fromEntries(SELECTORS.map((s) => [s, outside(s).length]))
  const inNoscript = Object.fromEntries(SELECTORS.map((s) => [s, all(s).length - sel[s]]).filter(([, n]) => n))
  const attrs = (s, a) => [...document.querySelectorAll(s)].filter((e) => !e.closest('noscript')).map((e) => e.getAttribute(a))
  const fp = {
    mirror: attrs(`${MIRROR}[value]`, 'value'),
    tip: attrs('.listupd article.bs a.tip', 'href'),
    ep: attrs('a.ep-button[href*="/episode/"]', 'href'),
  }
  return { sel, fp, inNoscript }
}

// Pola sensitif yang tidak boleh tersisa (dicek pada output akhir, di luar value base64 mirror).
function leftovers(html) {
  const { document } = newDom(html).window
  document.querySelectorAll(MIRROR).forEach((o) => o.removeAttribute('value'))
  const text = document.documentElement.outerHTML
  const bad = []
  const checks = {
    email: /[\w.+-]+@[\w-]+(?:\.[\w-]+)*\.[A-Za-z]{2,}/,
    cookie: /document\.cookie|set-cookie|\bcookie\s*[:=]/i,
    bearer: /\bBearer\s+(?!\[REDACTED)[A-Za-z0-9._~+/=-]{8,}/i,
    token: new RegExp(`\\b(?:${KEYS})["']?\\s*[:=]\\s*["']?(?!\\[REDACTED)[A-Za-z0-9._~+/=-]{8,}`, 'i'),
    tokenQuery: new RegExp(`[?&;](?:amp;)?(?:${KEYS}|sig|signature|auth|key)=(?!\\[REDACTED)[^&\\s"'#<>]+`, 'i'),
    privateIp: new RegExp(IP),
    eventHandler: /\son[a-z]+\s*=/i,
    commentOrScript: /<!--|<script|<noscript|<style/i,
  }
  for (const [k, re] of Object.entries(checks)) {
    const m = text.match(re)
    if (m) bad.push(`${k}: ${m[0].slice(0, 80)}`)
  }
  for (const el of document.querySelectorAll('meta[name],input[name]')) {
    const v = el.getAttribute(el.tagName === 'META' ? 'content' : 'value') ?? ''
    if (SENSITIVE_NAME.test(el.getAttribute('name')) && v !== '' && v !== R) bad.push(`field-token: ${el.getAttribute('name')}`)
  }
  return bad
}

const commit = execFileSync('git', ['-C', cloneRoot, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
const manifest = { anililiCommit: commit, files: {} }
const errors = []

for (const rel of FILES) {
  const sourcePath = `${SRC_DIR}/${rel}`
  const orig = readFileSync(join(cloneRoot, sourcePath))
  const { html, counts } = sanitize(orig.toString('utf8'))
  const out = Buffer.from(html, 'utf8')
  const before = measure(orig.toString('utf8'))
  const after = measure(html)

  const selectorCounts = {}
  for (const s of SELECTORS) {
    selectorCounts[s] = { original: before.sel[s], sanitized: after.sel[s] }
    if (before.sel[s] !== after.sel[s]) errors.push(`${rel}: selector "${s}" ${before.sel[s]} -> ${after.sel[s]}`)
  }
  for (const k of Object.keys(before.fp)) {
    if (JSON.stringify(before.fp[k]) !== JSON.stringify(after.fp[k])) errors.push(`${rel}: nilai atribut ${k} berubah`)
  }
  for (const b of leftovers(html)) errors.push(`${rel}: sisa pola sensitif -> ${b}`)

  const dest = join(outRoot, rel)
  mkdirSync(dirname(dest), { recursive: true })
  writeFileSync(dest, out)
  manifest.files[`tests/fixtures/real/${rel}`] = {
    sourcePath,
    anililiCommit: commit,
    sha256Original: sha256(orig),
    sha256Sanitized: sha256(out),
    bytesOriginal: orig.length,
    bytesSanitized: out.length,
    redactions: Object.fromEntries(Object.entries(counts).sort(([a], [b]) => a.localeCompare(b))),
    selectorCounts,
    selectorMatchesInsideRemovedNoscript: before.inNoscript,
  }
  console.log(`${rel}: ${orig.length} -> ${out.length} bytes`, counts)
}

writeFileSync(join(outRoot, 'MANIFEST.json'), JSON.stringify(manifest, null, 2) + '\n')
if (errors.length) {
  console.error('GAGAL:\n' + errors.map((e) => '  - ' + e).join('\n'))
  process.exit(1)
}
console.log('OK')
