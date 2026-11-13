// offlineTester.js — replay workflow terhadap fixture HTML/JSON di browser.
//
// Paritas dengan engine Kotlin Anilili (WorkflowExecutor.kt, TemplateEvaluator.kt, Base64Codec.kt,
// ref commit di engineContract.ENGINE_REFERENCE_COMMIT). Ini BUKAN eksekusi JVM:
// - Jsoup diganti DOMParser + querySelectorAll; regex Java diganti RegExp JS; JSON lenient diganti JSON.parse.
// - http step TIDAK menyentuh jaringan; ia me-replay fixture sebagai HttpResponse(200, {}, body).
//   Jaringan, redirect, policy, dan Cloudflare tetap NOT_TESTED.
// Perilaku yang sengaja mengikuti engine (bukan dugaan):
// - Tidak ada chaining implisit: input = nama variabel (HttpResponse → body) atau `{{template}}`.
// - params hanya di-evaluasi template pada http (url/headers/query/body) dan normalize url_resolve.baseUrl.
// - Step gagal menghentikan workflow; output null tidak disimpan ke saveAs (`output?.let`).
// Status per step: PASSED / FAILED / PARTIAL / BLOCKED / NOT_TESTED / NOT_DETECTED

import { SUPPORTED_OUTPUT_TYPES, TEMPLATE_REGEX } from '../contract/engineContract.js'
import { RUNTIME_PARSERS } from '../contract/runtimeParsers.js'

const STATUS = {
  PASSED: 'PASSED',
  FAILED: 'FAILED',
  PARTIAL: 'PARTIAL',
  BLOCKED: 'BLOCKED',
  NOT_TESTED: 'NOT_TESTED',
  NOT_DETECTED: 'NOT_DETECTED',
}

import { JsonEl, HttpResponse, ParsedDocument, isPrim, primContent, jsonToString, kString } from '../contract/workflowValues.js'

const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k)

// --- TemplateEvaluator -----------------------------------------------------------------------
/** TemplateEvaluator.resolveRawVariable. `context` = Map nama → nilai. */
function resolveRawVariable(path, context) {
  const clean = path.replace(/^\{\{/, '').replace(/\}\}$/, '').trim()
  const parts = clean.split('.')
  let current = context.has(parts[0]) ? context.get(parts[0]) : null
  if (current === null || current === undefined) return null // metadata engine tidak dimodelkan
  for (let i = 1; i < parts.length; i++) {
    const part = parts[i]
    let next = null
    if (current instanceof HttpResponse) {
      const lower = part.toLowerCase()
      if (lower === 'body') next = current.body
      else if (lower === 'statuscode' || lower === 'status') next = current.statusCode
      else if (lower === 'headers') next = current.headers
      else {
        const h = Object.entries(current.headers).find(([k]) => k.toLowerCase() === part.toLowerCase())
        next = hasOwn(current.headers, part) ? current.headers[part] : h ? h[1] : null
      }
    } else if (current instanceof JsonEl) {
      const v = current.v
      if (Array.isArray(v)) {
        const idx = /^-?\d+$/.test(part) ? Number(part) : NaN
        next = idx >= 0 && idx < v.length ? new JsonEl(v[idx]) : null
      } else if (v !== null && typeof v === 'object') {
        next = hasOwn(v, part) ? new JsonEl(v[part]) : null
      }
    } else if (Array.isArray(current)) {
      const idx = /^-?\d+$/.test(part) ? Number(part) : NaN
      next = idx >= 0 && idx < current.length ? current[idx] : null
    } else if (current !== null && typeof current === 'object' && !(current instanceof ParsedDocument)) {
      next = hasOwn(current, part) ? current[part] : null
    }
    if (next === null || next === undefined) return null
    current = next
  }
  return current
}

/** TemplateEvaluator.resolveVariable: JsonPrimitive → content, selainnya toString. */
function resolveVariable(path, context) {
  const raw = resolveRawVariable(path, context)
  if (raw === null) return null
  if (raw instanceof JsonEl) return isPrim(raw.v) ? primContent(raw.v) : jsonToString(raw.v)
  return typeof raw === 'string' ? raw : kString(raw)
}

/**
 * TemplateEvaluator.evaluate. Variabel tak terselesaikan → "" (diam-diam seperti engine).
 * @param {string} str
 * @param {Map<string, any>} context
 */
function evaluateTemplate(str, context) {
  if (typeof str !== 'string') return str
  if (str.trim() === '') return str
  return str.replace(new RegExp(TEMPLATE_REGEX.source, 'g'), (_m, name) => resolveVariable(name.trim(), context) ?? '')
}

// --- Resolusi input (resolveInputString / resolveInputRaw) -------------------------------------
const isTemplateExpr = (e) => e.startsWith('{{') && e.endsWith('}}')

function resolveInputString(expr, context) {
  if (isTemplateExpr(expr)) {
    const v = evaluateTemplate(expr, context)
    return v.trim() === '' ? null : v
  }
  if (context.has(expr) && context.get(expr) !== null && context.get(expr) !== undefined) {
    return kString(context.get(expr))
  }
  const resolved = resolveVariable(expr, context)
  if (resolved !== null) return resolved
  const evald = evaluateTemplate(expr, context)
  return evald.trim() === '' ? null : evald
}

function resolveInputRaw(expr, context) {
  if (isTemplateExpr(expr)) return resolveRawVariable(expr, context)
  if (context.has(expr) && context.get(expr) !== null && context.get(expr) !== undefined) {
    const d = context.get(expr)
    return d instanceof HttpResponse ? d.body : d
  }
  return resolveRawVariable(expr, context)
}

// --- Regex Java → JS -----------------------------------------------------------------------------
// shortcut: hanya flag inline di awal pola ((?i) (?s) (?m)); fitur Java lain (lookbehind variabel,
// possessive, \p{..} dsb) bisa gagal di JS → dilaporkan NOT_TESTED, bukan FAILED.
function compileRegex(pattern, { global = false, full = false } = {}) {
  let flags = global ? 'g' : ''
  let src = pattern
  const m = src.match(/^\(\?([ims]+)\)/)
  if (m) {
    flags += m[1]
    src = src.slice(m[0].length)
  }
  return new RegExp(full ? `^(?:${src})$` : src, flags)
}

class UnsupportedRegexError extends Error {}
function safeRegex(pattern, opts) {
  try {
    return compileRegex(pattern, opts)
  } catch (e) {
    throw new UnsupportedRegexError(
      `Pola tidak bisa dikompilasi di JS (mungkin valid di Java Regex, tidak teruji): ${e.message}`
    )
  }
}

// --- Jsoup-ish helper ------------------------------------------------------------------------------
const BLOCK_TAGS = new Set(['ADDRESS', 'ARTICLE', 'ASIDE', 'BLOCKQUOTE', 'BODY', 'BR', 'DD', 'DIV', 'DL', 'DT', 'FIELDSET', 'FIGURE', 'FOOTER', 'FORM', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'HEADER', 'HR', 'LI', 'MAIN', 'NAV', 'OL', 'P', 'PRE', 'SECTION', 'TABLE', 'TBODY', 'TD', 'TFOOT', 'TH', 'THEAD', 'TR', 'UL', 'OPTION', 'SELECT'])
const SKIP_TEXT_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE'])

/** Jsoup Element.text(): teks node (tanpa script/style), whitespace dinormalkan, di-trim. */
// shortcut: tidak meniru seluruh aturan inline/block Jsoup, hanya spasi di sekitar tag blok.
function jsoupText(el) {
  let out = ''
  const walk = (node) => {
    for (const c of node.childNodes) {
      if (c.nodeType === 3) out += c.nodeValue
      else if (c.nodeType === 1) {
        if (SKIP_TEXT_TAGS.has(c.tagName.toUpperCase())) continue
        const block = BLOCK_TAGS.has(c.tagName.toUpperCase())
        if (block) out += ' '
        walk(c)
        if (block) out += ' '
      }
    }
  }
  walk(el)
  return out.replace(/\s+/g, ' ').trim()
}

const JSOUP_ONLY_SELECTOR = /:(contains|containsOwn|matches|matchesOwn|containsData|eq|lt|gt)\(/

function parseHtmlDoc(html) {
  return new DOMParser().parseFromString(html, 'text/html')
}

// --- JSON path (KotlinxJsonParser.extractByPath) ---------------------------------------------------
/** @returns {string|string[]|null} null = path tidak ditemukan. */
function extractByPath(jsonString, path) {
  let current = JSON.parse(jsonString)
  for (const part of path.split('.')) {
    if (part.startsWith('[') && part.endsWith(']')) {
      const raw = part.slice(1, -1)
      if (!/^[+-]?\d+$/.test(raw)) return null
      const idx = Number(raw)
      if (!Array.isArray(current) || idx < 0 || idx >= current.length) return null
      current = current[idx]
    } else if (current !== null && typeof current === 'object' && !Array.isArray(current)) {
      if (!hasOwn(current, part)) return null
      current = current[part]
    } else {
      return null
    }
  }
  if (isPrim(current)) return primContent(current)
  if (Array.isArray(current)) return current.map((x) => (isPrim(x) ? primContent(x) : JSON.stringify(x)))
  return JSON.stringify(current)
}

// --- Normalize helpers -----------------------------------------------------------------------------
/** Base64Codec.decodeToString: alfabet standar; gagal → string asli. */
function base64Decode(value) {
  const i = value.indexOf('base64,')
  const clean = (i >= 0 ? value.slice(i + 'base64,'.length) : value).trim()
  if (clean === '') return ''
  try {
    const bin = atob(clean)
    return new TextDecoder('utf-8').decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
  } catch {
    return value
  }
}

/** Urutan regex persis seperti engine: "Episode 5" menjadi "isode 5" (lihat OPERATIONAL_LIMITATIONS). */
function normalizeEpisodeNumber(value) {
  return value
    .trim()
    .replace(/^ep\.?\s*/i, '')
    .replace(/^episode\s*/i, '')
    .trim()
}

function normalizeLanguage(value) {
  const k = value.toLowerCase().trim()
  if (['en', 'eng', 'english'].includes(k)) return 'eng'
  if (['ja', 'jpn', 'japanese'].includes(k)) return 'jpn'
  if (['id', 'ind', 'indonesia', 'indonesian'].includes(k)) return 'ind'
  return k
}

function resolveUrl(value, baseUrl, context) {
  try {
    return new URL(value, evaluateTemplate(baseUrl, context)).href
  } catch {
    return value
  }
}

const jsonParam = (v) => {
  if (v === undefined || v === null) return undefined
  if (typeof v !== 'string') return v
  try {
    return JSON.parse(v)
  } catch {
    return undefined
  }
}

const fail = (error, evidence = []) => ({ status: STATUS.FAILED, output: null, error, evidence })
const ok = (output, evidence = [], status = STATUS.PASSED, error = null) => ({ status, output, error, evidence })
/** success(null) di engine: tidak disimpan, workflow lanjut. */
const nothing = (error, evidence = []) => ({ status: STATUS.NOT_DETECTED, output: null, error, evidence })

// --- Eksekusi satu step ----------------------------------------------------------------------------
/**
 * @param {object} step — { op, params: Map|object, saveAs }
 * @param {Map<string, any>} context — variabel workflow (dimutasi: saveAs disimpan di sini)
 * @param {Document|null} dom — DOM fixture; dipakai http step bila options.fixtureHtml tidak ada
 * @param {string} baseUrl — tidak dipakai engine (url_resolve memakai op.baseUrl); dipertahankan demi kompatibilitas API
 * @param {{fixtures?: string[], fixtureHtml?: string, state?: {httpCalls: number}}} [options]
 * @returns {{ status, output, error, evidence, replayed?: boolean }}
 */
export function executeStep(step, context, dom, baseUrl = '', options = {}) {
  const raw = step.params
  const params = raw instanceof Map ? raw : new Map(Object.entries(raw || {}))
  const result = runStep(step.op, params, context, dom, baseUrl, options)
  // StepResult.output?.let { step.saveAs?.let { context.put } }
  if (result.status !== STATUS.FAILED && result.output !== null && result.output !== undefined && step.saveAs) {
    context.set(step.saveAs, result.output)
  }
  return result
}

function runStep(op, params, context, dom, _baseUrl, options) {
  const input = params.get('input') ?? ''
  try {
    switch (op) {
      case 'http': {
        const state = options.state || (options.state = { httpCalls: 0 })
        const idx = state.httpCalls++
        const fixtures = options.fixtures || (options.fixtureHtml !== undefined ? [options.fixtureHtml] : null)
        const body = fixtures && fixtures.length
          ? fixtures[Math.min(idx, fixtures.length - 1)]
          : dom
            ? dom.documentElement.outerHTML
            : null
        const url = evaluateTemplate(params.get('url') ?? '', context)
        if (body === null) {
          return { status: STATUS.NOT_TESTED, output: null, error: null, evidence: [`HTTP ${url} tidak bisa dijalankan offline (tidak ada fixture)`] }
        }
        return {
          status: STATUS.PASSED,
          output: new HttpResponse(200, {}, body),
          error: null,
          replayed: true,
          evidence: [`http ${(params.get('method') || 'GET').toUpperCase()} ${url} — REPLAY fixture sebagai HttpResponse(200); jaringan/redirect/policy NOT_TESTED`],
        }
      }

      case 'parse_html': {
        const html = resolveInputString(input, context)
        if (html === null || html.trim() === '') {
          return fail(`ParseHtmlStep: input '${input}' is empty or not resolved`)
        }
        return ok(new ParsedDocument(html), ['HTML dimuat'])
      }

      case 'select': {
        const rawIn = resolveInputRaw(input, context)
        if (rawIn === null || rawIn === undefined) return fail(`SelectStep: input variable '${input}' not found`)
        const html = rawIn instanceof ParsedDocument ? rawIn.html : kString(rawIn)
        const selector = params.get('selector') ?? ''
        const attribute = params.has('attribute') && params.get('attribute') !== null ? params.get('attribute') : null
        const mode = String(params.get('mode') ?? 'FIRST').toUpperCase()
        let els
        try {
          els = Array.from(parseHtmlDoc(html).querySelectorAll(selector))
        } catch (e) {
          if (JSOUP_ONLY_SELECTOR.test(selector)) {
            return { status: STATUS.NOT_TESTED, output: null, error: null, evidence: [`Selector Jsoup-spesifik tidak bisa dites di browser: ${selector}`] }
          }
          return fail(`Selection failed: ${e.message}`)
        }
        const text = (el) => (attribute !== null ? el.getAttribute(attribute) ?? '' : jsoupText(el))
        const ev = [`"${selector}" → ${els.length} elemen`]
        switch (mode) {
          case 'ALL': {
            const out = els.map(text)
            return out.length
              ? ok(out, ev)
              : { status: STATUS.NOT_DETECTED, output: out, error: `Tidak ada elemen untuk selector: ${selector}`, evidence: ev }
          }
          case 'FIRST':
          case 'TEXT':
          case 'HTML':
          case 'ATTRIBUTE': {
            const el = els[0]
            if (!el) return nothing(`Elemen tidak ditemukan dengan selector: ${selector}`, ev)
            const out = mode === 'FIRST'
              ? text(el)
              : mode === 'TEXT'
                ? jsoupText(el)
                : mode === 'HTML'
                  ? el.innerHTML
                  : el.getAttribute(attribute ?? '') ?? ''
            return ok(out, ev)
          }
          default:
            return fail(`Selection failed: mode tidak dikenal ${mode}`)
        }
      }

      case 'parse_json': {
        const text = resolveInputString(input, context)
        if (text === null) return fail(`ParseJsonStep: input variable '${input}' not found or empty`)
        let parsed
        try {
          parsed = JSON.parse(text)
        } catch (e) {
          return fail(`JSON parsing failed: ${e.message}`)
        }
        const path = params.get('jsonPath')
        const extracted = path !== undefined && path !== null ? extractByPath(text, path) : null
        // Kotlin: `jsonPath?.let { extractByPath } ?: parse(input)` → path tidak ketemu = seluruh JSON.
        if (extracted === null) {
          return ok(new JsonEl(parsed), [path ? `jsonPath "${path}" tidak ketemu → engine mengembalikan SELURUH JSON` : 'JSON diparse'],
            path ? STATUS.PARTIAL : STATUS.PASSED, path ? `jsonPath tidak ditemukan: ${path}` : null)
        }
        return ok(extracted, [`jsonPath "${path}" diekstrak`])
      }

      case 'regex': {
        const text = resolveInputString(input, context)
        if (text === null) return fail(`RegexStep: input variable '${input}' not found or empty`)
        const pattern = params.get('pattern') ?? ''
        const group = parseInt(params.get('group') ?? '0', 10) || 0
        const mode = String(params.get('mode') ?? 'FIRST').toUpperCase()
        let re
        try {
          re = safeRegex(pattern, { global: mode !== 'FIRST' })
        } catch (e) {
          if (e instanceof UnsupportedRegexError) {
            return { status: STATUS.NOT_TESTED, output: null, error: null, evidence: [e.message] }
          }
          throw e
        }
        const grp = (m) => (group >= 0 && group < m.length ? m[group] ?? '' : null)
        if (mode === 'REPLACE') return ok(text.replace(re, ''), ['replace → ""'])
        if (mode === 'FIRST') {
          const m = re.exec(text)
          const v = m ? grp(m) : null
          return v === null ? nothing(`Tidak ada cocokan untuk pola: ${pattern}`) : ok(v, ['regex FIRST'])
        }
        if (mode !== 'ALL') return fail(`Regex execution failed: mode tidak dikenal ${mode}`)
        const all = Array.from(text.matchAll(re), grp).filter((x) => x !== null)
        return all.length
          ? ok(all, [`${all.length} cocokan`])
          : { status: STATUS.NOT_DETECTED, output: all, error: `Tidak ada cocokan untuk pola: ${pattern}`, evidence: [] }
      }

      case 'map': {
        const mappings = jsonParam(params.get('mappings')) || {}
        const rawIn = resolveInputRaw(input, context)
        if (rawIn === null || rawIn === undefined) return fail(`MapStep: input variable '${input}' not found`)
        let source = rawIn
        if (typeof rawIn === 'string') {
          try {
            source = new JsonEl(JSON.parse(rawIn))
          } catch {
            source = rawIn
          }
        }
        const rename = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [hasOwn(mappings, k) ? mappings[k] : k, v]))
        const mapFields = (s) => {
          if (s instanceof JsonEl) {
            const v = s.v
            if (Array.isArray(v)) return new JsonEl(v.map((e) => (e !== null && typeof e === 'object' && !Array.isArray(e) ? rename(e) : e)))
            if (v !== null && typeof v === 'object') return new JsonEl(rename(v))
            return s
          }
          if (Array.isArray(s)) return s.map((i) => (i === null ? null : mapFields(i)))
          if (s !== null && typeof s === 'object' && !(s instanceof ParsedDocument)) return rename(s)
          return s
        }
        return ok(mapFields(source), [`map ${Object.keys(mappings).length} kunci (sumber→nama baru)`])
      }

      case 'normalize': {
        let value = resolveInputString(input, context)
        if (value === null) return fail(`NormalizeStep: input variable '${input}' not found`)
        const ops = jsonParam(params.get('operations'))
        if (!Array.isArray(ops)) return fail('operations wajib berupa array JSON untuk normalize step')
        const ev = []
        for (const o of ops) {
          ev.push(o.type)
          switch (o.type) {
            case 'trim': value = value.trim(); break
            case 'lowercase': value = value.toLowerCase(); break
            case 'uppercase': value = value.toUpperCase(); break
            case 'url_resolve': value = resolveUrl(value, o.baseUrl ?? '', context); break
            case 'episode_number': value = normalizeEpisodeNumber(value); break
            case 'language_normalize': value = normalizeLanguage(value); break
            case 'join': {
              const r = resolveInputRaw(input, context)
              const list = Array.isArray(r) ? r : Array.isArray(context.get(input)) ? context.get(input) : null
              // Engine: join langsung return, operasi sesudahnya diabaikan.
              return ok(list ? list.map(kString).join(o.separator ?? '') : value, [...ev, 'join (operasi setelahnya diabaikan engine)'])
            }
            case 'default': if (value.trim() === '') value = o.value ?? ''; break
            case 'base64_decode': value = base64Decode(value); break
            default: return fail(`Operasi normalize tidak dikenal: ${o.type}`)
          }
        }
        return ok(value, ev)
      }

      case 'assert': {
        const c = jsonParam(params.get('condition'))
        if (!c || typeof c !== 'object') return fail('condition wajib berupa objek JSON untuk assert step')
        const text = resolveInputString(input, context) ?? ''
        let passed
        switch (c.type) {
          case 'not_null': passed = text.length > 0; break
          case 'not_empty': passed = text.trim().length > 0; break
          case 'equals': passed = text === c.value; break
          case 'contains': passed = text.toLowerCase().includes(String(c.value).toLowerCase()); break
          case 'matches': passed = safeRegex(c.pattern, { full: true }).test(text); break
          case 'size': passed = (c.min == null || text.length >= c.min) && (c.max == null || text.length <= c.max); break
          default: return fail(`Kondisi assert tidak dikenal: ${c.type}`)
        }
        return passed ? ok(true, ['assert lolos']) : fail(params.get('message') || 'Assertion failed', [JSON.stringify(c)])
      }

      case 'filter': {
        const p = jsonParam(params.get('predicate'))
        if (!p || typeof p !== 'object') return fail('predicate wajib berupa objek JSON untuk filter step')
        const r = resolveInputRaw(input, context)
        let list = null
        if (Array.isArray(r)) list = r
        else if (r instanceof JsonEl && Array.isArray(r.v)) list = r.v.map((x) => (isPrim(x) ? primContent(x) : JSON.stringify(x)))
        else if (Array.isArray(context.get(input))) list = context.get(input)
        if (list === null) return fail(`FilterStep: input variable '${input}' not found or not a list`)
        const keep = (item) => {
          const s = kString(item)
          switch (p.type) {
            case 'not_null':
            case 'not_empty': return s.trim() !== ''
            case 'contains': return s.toLowerCase().includes(String(p.value).toLowerCase())
            case 'matches': return safeRegex(p.pattern, { full: true }).test(s)
            default: throw new Error(`Predicate filter tidak dikenal: ${p.type}`)
          }
        }
        const out = list.filter(keep)
        return ok(out, [`${out.length}/${list.length} lolos`], out.length ? STATUS.PASSED : STATUS.PARTIAL)
      }

      default:
        return { status: STATUS.BLOCKED, output: null, error: `Op tidak didukung: ${op}`, evidence: [] }
    }
  } catch (err) {
    if (err instanceof UnsupportedRegexError) {
      return { status: STATUS.NOT_TESTED, output: null, error: null, evidence: [err.message] }
    }
    return fail(err.message || 'Eksekusi step gagal')
  }
}

/** Nilai untuk tampilan/JSON: bungkus internal → nilai biasa. */
export function toPlain(v) {
  if (v instanceof JsonEl) return v.v
  if (v instanceof HttpResponse) return { statusCode: v.statusCode, headers: v.headers, body: v.body }
  if (v instanceof ParsedDocument) return v.html
  if (Array.isArray(v)) return v.map(toPlain)
  return v
}

// --- Eksekusi workflow -----------------------------------------------------------------------------
/** Variabel yang di-inject engine saat runtime (nilai contoh untuk uji offline replay; bukan data nyata). */
export const SAMPLE_VARIABLES = { query: 'test', anilistId: '1', title: 'Test', episodeNumber: '1', category: 'sub' }

/**
 * @param {object} workflow — { name, steps, output }
 * @param {Document|null} dom — fixture DOM (dipakai http step bila tidak ada options.fixtures/fixtureHtml)
 * @param {string} baseUrl
 * @param {{variables?: object, fixtures?: string[], fixtureHtml?: string}} [options]
 *   variables = variabel yang di-inject runtime (query, url, episodeUrl, ...). Yang tidak diisi = tidak ada,
 *   persis seperti engine saat cache slug kosong.
 * @returns {{ stepResults: Array, finalOutput: any, rawOutput: any, overallStatus: string, variables: object, replayed: boolean }}
 *   rawOutput = output dengan tipe internal (JsonEl dsb), masukan untuk runtimeParsers.
 */
export function executeWorkflow(workflow, dom, baseUrl = '', options = {}) {
  const context = new Map(Object.entries(options.variables || {}))
  const opts = { ...options, state: { httpCalls: 0 } }
  const stepResults = []
  let overallStatus = STATUS.PASSED
  let replayed = false
  const steps = workflow.steps || []

  for (let i = 0; i < steps.length; i++) {
    const step = steps[i]
    const result = executeStep(step, context, dom, baseUrl, opts)
    if (result.replayed) replayed = true
    stepResults.push({
      stepIndex: i,
      op: step.op,
      id: step.id || `step-${i}`,
      saveAs: step.saveAs,
      status: result.status,
      output: toPlain(result.output),
      error: result.error,
      evidence: result.evidence,
    })
    if (result.status === STATUS.FAILED) {
      overallStatus = STATUS.FAILED
      break // WorkflowExecutor.execute: step gagal menghentikan workflow
    }
    if (result.status === STATUS.NOT_TESTED) {
      // Step tak bisa dijalankan → step berikutnya bergantung pada variabelnya; jangan berpura-pura lanjut.
      if (overallStatus === STATUS.PASSED || overallStatus === STATUS.PARTIAL) overallStatus = STATUS.NOT_TESTED
      break
    }
    if (result.status === STATUS.BLOCKED && overallStatus !== STATUS.FAILED) overallStatus = STATUS.BLOCKED
    else if ((result.status === STATUS.NOT_DETECTED || result.status === STATUS.PARTIAL) && overallStatus === STATUS.PASSED) {
      overallStatus = STATUS.PARTIAL
    }
  }

  // extractOutput: RAW → semua variabel; selain itu mapping nama→variabel (tanpa penelusuran path).
  const variables = Object.fromEntries([...context].map(([k, v]) => [k, toPlain(v)]))
  let finalOutput = null
  let rawOutput = null
  const type = workflow.output?.type || 'RAW'
  if (type === 'RAW') {
    finalOutput = variables
    rawOutput = Object.fromEntries(context)
  }
  else if (SUPPORTED_OUTPUT_TYPES.includes(type)) {
    finalOutput = {}
    rawOutput = {}
    for (const [k, varName] of Object.entries(workflow.output.mapping || {})) {
      rawOutput[k] = context.has(varName) ? context.get(varName) : null
      finalOutput[k] = toPlain(rawOutput[k])
    }
  }

  return { stepResults, finalOutput, rawOutput, overallStatus, variables, replayed }
}

/**
 * Replay satu workflow terhadap fixture lalu terapkan GERBANG PARSER ENGINE: step PASSED belum berarti
 * engine mendapat hasil. Output yang di-parse engine menjadi 0 item → FAILED; sources tanpa satu pun
 * URL (mis. blob base64) → BLOCKED. Tetap BUKAN bukti jaringan/Android.
 * @param {string} name search|episodes|sources|servers
 * @param {{steps?: Array, output?: object}} wf step dengan params berupa objek polos
 * @param {{html: string, endpoint?: string}} ctx
 */
export function runWorkflowOffline(name, wf, { html, endpoint = '' }) {
  const steps = (wf.steps || []).map((st) => ({ ...st, params: new Map(Object.entries(st.params || {})) }))
  const dom = new DOMParser().parseFromString(html, 'text/html')
  const result = executeWorkflow({ ...wf, steps }, dom, endpoint, {
    fixtureHtml: html,
    variables: { ...SAMPLE_VARIABLES, url: endpoint },
  })
  const parse = RUNTIME_PARSERS[name]
  if (!parse || result.overallStatus !== STATUS.PASSED) return { name, ...result, engine: null }
  const parsed = parse(result.rawOutput, { endpoint })
  const engine = { items: parsed.items.length, warnings: parsed.warnings, error: null }
  if (parsed.items.length === 0) {
    engine.error = `Parser engine (${name}) menghasilkan 0 item dari output workflow — engine akan memberi hasil kosong. Atur output.type/mapping agar sesuai kunci yang dibaca parser.`
    return { name, ...result, overallStatus: STATUS.FAILED, engine }
  }
  if (name === 'sources' && parsed.urlLike === 0) {
    engine.error = 'Semua stream bukan URL (mis. blob base64): engine tidak men-decode per item (no-per-item-base64-decode); playback tidak mungkin.'
    return { name, ...result, overallStatus: STATUS.BLOCKED, engine }
  }
  return { name, ...result, engine }
}

export { STATUS, evaluateTemplate }
