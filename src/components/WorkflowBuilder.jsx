// WorkflowBuilder.jsx — Buat dan edit langkah-langkah workflow (search, episodes,
// servers, sources) dengan validasi real-time terhadap engineContract.js.
// Mendukung: input context, HTTP params (method, headers, body template),
// nested JSON params (jsonPath, mappings object), output mapping per workflow,
// per-op validation.

import { useState, useCallback } from 'react'
import {
  SUPPORTED_OPS,
  SUPPORTED_OUTPUT_TYPES,
  validateWorkflowStep,
  validateTemplateString,
} from '../lib/contract/engineContract.js'
import PropTypes from 'prop-types'

const WORKFLOW_NAMES = ['search', 'episodes', 'servers', 'sources']

// Param field yang ditampilkan untuk setiap op.
const OP_PARAMS = {
  http: ['url', 'method', 'headers', 'body'],
  parse_html: [],
  select: ['selector', 'mode', 'attribute'],
  parse_json: ['jsonPath'],
  regex: ['pattern', 'group', 'mode'],
  map: ['mappings'],
  normalize: ['operations'],
  assert: ['condition', 'message'],
  filter: ['predicate'],
}

const MODE_OPTIONS = ['ALL', 'FIRST', 'TEXT', 'HTML', 'ATTRIBUTE']
const REGEX_MODE_OPTIONS = ['FIRST', 'ALL', 'REPLACE']
const HTTP_METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

function emptyParams(op) {
  const defaults = {
    select: { mode: 'ALL' },
    regex: { group: '0', mode: 'FIRST' },
    http: { method: 'GET' },
  }
  return { ...(defaults[op] || {}) }
}

// Per-op validators untuk validasi detail per operasi
function validateStepPerOp(step) {
  const errors = []
  const op = step.op
  const params = step.params || {}

  switch (op) {
    case 'http': {
      if (!params.url || typeof params.url !== 'string' || !params.url.trim()) {
        errors.push('http: url wajib diisi')
      } else {
        // Validasi template syntax di url
        const tmpl = validateTemplateString(params.url)
        if (!tmpl.valid) {
          errors.push(`http: url mengandung syntax tidak didukung: ${tmpl.unsupported.join(', ')}`)
        }
      }
      if (!HTTP_METHOD_OPTIONS.includes(params.method)) {
        errors.push(`http: method harus salah satu dari: ${HTTP_METHOD_OPTIONS.join(', ')}`)
      }
      if (params.headers !== undefined) {
        try {
          const headers = JSON.parse(params.headers)
          if (typeof headers !== 'object' || headers === null || Array.isArray(headers)) {
            errors.push('http: headers harus berupa objek JSON (key-value)')
          } else {
            // Validasi template di header values
            Object.values(headers).forEach((v) => {
              if (typeof v === 'string') {
                const tmpl = validateTemplateString(v)
                if (!tmpl.valid) {
                  errors.push(`http: header value mengandung syntax tidak didukung: ${tmpl.unsupported.join(', ')}`)
                }
              }
            })
          }
        } catch {
          errors.push('http: headers harus berupa JSON valid')
        }
      }
      if (params.body !== undefined && params.body !== '') {
        const tmpl = validateTemplateString(params.body)
        if (!tmpl.valid) {
          errors.push(`http: body mengandung syntax tidak didukung: ${tmpl.unsupported.join(', ')}`)
        }
      }
      break
    }
    case 'parse_html': {
      // Tidak ada param wajib
      break
    }
    case 'select': {
      if (!params.selector || typeof params.selector !== 'string' || !params.selector.trim()) {
        errors.push('select: selector wajib diisi')
      } else {
        const tmpl = validateTemplateString(params.selector)
        if (!tmpl.valid) {
          errors.push(`select: selector mengandung syntax tidak didukung: ${tmpl.unsupported.join(', ')}`)
        }
      }
      if (!MODE_OPTIONS.includes(params.mode)) {
        errors.push(`select: mode harus salah satu dari: ${MODE_OPTIONS.join(', ')}`)
      }
      if (params.mode === 'ATTRIBUTE' && (!params.attribute || !params.attribute.trim())) {
        errors.push('select: attribute wajib diisi saat mode=ATTRIBUTE')
      }
      break
    }
    case 'parse_json': {
      if (!params.jsonPath || typeof params.jsonPath !== 'string' || !params.jsonPath.trim()) {
        errors.push('parse_json: jsonPath wajib diisi')
      }
      // Validasi format jsonPath (dot notation + [N])
      if (params.jsonPath && !/^[a-zA-Z_][a-zA-Z0-9_.]*(\[\d+\])*(\.[a-zA-Z_][a-zA-Z0-9_.]*(\[\d+\])*)*$/.test(params.jsonPath)) {
        errors.push('parse_json: jsonPath harus dot-notation + [N] index (contoh: data.items[0].title)')
      }
      break
    }
    case 'regex': {
      if (!params.pattern || typeof params.pattern !== 'string' || !params.pattern.trim()) {
        errors.push('regex: pattern wajib diisi')
      }
      if (params.group !== undefined && (isNaN(parseInt(params.group)) || parseInt(params.group) < 0)) {
        errors.push('regex: group harus integer >= 0')
      }
      if (!REGEX_MODE_OPTIONS.includes(params.mode)) {
        errors.push(`regex: mode harus salah satu dari: ${REGEX_MODE_OPTIONS.join(', ')}`)
      }
      break
    }
    case 'map': {
      if (!params.mappings || typeof params.mappings !== 'string' || !params.mappings.trim()) {
        errors.push('map: mappings wajib diisi')
      } else {
        try {
          const mappings = JSON.parse(params.mappings)
          if (typeof mappings !== 'object' || mappings === null || Array.isArray(mappings)) {
            errors.push('map: mappings harus berupa objek JSON')
          } else {
            Object.entries(mappings).forEach(([outKey, inKey]) => {
              if (typeof inKey !== 'string') {
                errors.push(`map: mapping "${outKey}" harus berupa string (variabel context atau literal)`)
              }
            })
          }
        } catch {
          errors.push('map: mappings harus berupa JSON valid')
        }
      }
      break
    }
    case 'normalize': {
      if (!params.operations || typeof params.operations !== 'string' || !params.operations.trim()) {
        errors.push('normalize: operations wajib diisi')
      } else {
        try {
          const ops = JSON.parse(params.operations)
          if (!Array.isArray(ops)) {
            errors.push('normalize: operations harus berupa array JSON')
          } else {
            const validOps = ['trim', 'lowercase', 'uppercase', 'url_resolve', 'episode_number', 'language_normalize', 'join', 'default', 'base64_decode']
            ops.forEach((op, idx) => {
              if (!op.type || !validOps.includes(op.type)) {
                errors.push(`normalize[${idx}]: type harus salah satu dari: ${validOps.join(', ')}`)
              }
              if (op.type === 'url_resolve' && !op.baseUrl) {
                errors.push(`normalize[${idx}]: url_resolve membutuhkan baseUrl`)
              }
              if (op.type === 'join' && op.separator === undefined) {
                errors.push(`normalize[${idx}]: join membutuhkan separator`)
              }
              if (op.type === 'default' && op.value === undefined) {
                errors.push(`normalize[${idx}]: default membutuhkan value`)
              }
            })
          }
        } catch {
          errors.push('normalize: operations harus berupa JSON array valid')
        }
      }
      break
    }
    case 'assert': {
      if (!params.condition || typeof params.condition !== 'string' || !params.condition.trim()) {
        errors.push('assert: condition wajib diisi')
      } else {
        try {
          const cond = JSON.parse(params.condition)
          if (typeof cond !== 'object' || cond === null) {
            errors.push('assert: condition harus berupa objek JSON')
          } else if (!['not_null', 'not_empty', 'equals', 'contains', 'matches', 'size'].includes(cond.type)) {
            errors.push('assert: condition.type harus salah satu dari: not_null, not_empty, equals, contains, matches, size')
          } else if (cond.type === 'equals' && cond.value === undefined) {
            errors.push('assert: condition type equals membutuhkan value')
          } else if (cond.type === 'contains' && cond.value === undefined) {
            errors.push('assert: condition type contains membutuhkan value')
          } else if (cond.type === 'matches' && !cond.pattern) {
            errors.push('assert: condition type matches membutuhkan pattern')
          } else if (cond.type === 'size' && cond.min === undefined && cond.max === undefined) {
            errors.push('assert: condition type size membutuhkan min atau max')
          }
        } catch {
          errors.push('assert: condition harus berupa JSON valid')
        }
      }
      if (!params.message || !params.message.trim()) {
        errors.push('assert: message wajib diisi')
      }
      break
    }
    case 'filter': {
      if (!params.predicate || typeof params.predicate !== 'string' || !params.predicate.trim()) {
        errors.push('filter: predicate wajib diisi')
      } else {
        try {
          const pred = JSON.parse(params.predicate)
          if (typeof pred !== 'object' || pred === null) {
            errors.push('filter: predicate harus berupa objek JSON')
          } else if (!['not_null', 'not_empty', 'contains', 'matches'].includes(pred.type)) {
            errors.push('filter: predicate.type harus salah satu dari: not_null, not_empty, contains, matches')
          } else if (pred.type === 'contains' && pred.value === undefined) {
            errors.push('filter: predicate type contains membutuhkan value')
          } else if (pred.type === 'matches' && !pred.pattern) {
            errors.push('filter: predicate type matches membutuhkan pattern')
          }
        } catch {
          errors.push('filter: predicate harus berupa JSON valid')
        }
      }
      break
    }
    default:
      errors.push(`Op tidak dikenal: ${op}`)
  }

  return { valid: errors.length === 0, errors }
}

// Terkontrol: `workflows` = project.workflows. Setiap edit pengguna dilaporkan lewat onEdit(nama, workflow)
// sehingga induk menandainya "manual" dan inferensi tidak menimpanya.
export default function WorkflowBuilder({ workflows, origin, onEdit }) {
  const data = workflows || {}
  const [expandedWorkflow, setExpandedWorkflow] = useState(null)

  const edit = useCallback((wfName, fn) => {
    const wf = (workflows || {})[wfName] || { name: wfName, steps: [] }
    const next = fn(wf)
    if (next && next !== wf) onEdit?.(wfName, next)
  }, [workflows, onEdit])

  const replaceStep = (wfName, stepIndex, patch) =>
    edit(wfName, (wf) => {
      if (!wf.steps[stepIndex]) return null
      const nextSteps = [...wf.steps]
      nextSteps[stepIndex] = patch(nextSteps[stepIndex])
      return { ...wf, steps: nextSteps }
    })

  const setStepParam = (wfName, i, key, value) => replaceStep(wfName, i, (s) => ({ ...s, params: { ...s.params, [key]: value } }))
  const setStepSaveAs = (wfName, i, value) => replaceStep(wfName, i, (s) => ({ ...s, saveAs: value || null }))
  const setStepOp = (wfName, i, op) => replaceStep(wfName, i, (s) => ({ ...s, op, params: emptyParams(op) }))
  const addStep = (wfName, op = 'select') =>
    edit(wfName, (wf) => ({ ...wf, steps: [...wf.steps, { op, id: `step-${Date.now()}`, params: emptyParams(op), saveAs: null }] }))
  const removeStep = (wfName, stepIndex) =>
    edit(wfName, (wf) => ({ ...wf, steps: wf.steps.filter((_, i) => i !== stepIndex) }))
  const setWorkflowOutput = (wfName, output) => edit(wfName, (wf) => (data[wfName] ? { ...wf, output } : null))

  return (
    <div className="space-y-6">
      {WORKFLOW_NAMES.map((wfName) => {
        const wf = data[wfName] || { name: wfName, steps: [] }
        const isExpanded = expandedWorkflow === wfName
        return (
          <div key={wfName} data-testid={`wf-card-${wfName}`} className="card space-y-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                  {wfName}
                  {data[wfName] && (
                    <span
                      data-testid={`origin-${wfName}`}
                      className={`badge ml-2 normal-case ${origin?.[wfName] === 'auto' ? 'badge-passed' : 'badge-partial'}`}
                    >
                      {origin?.[wfName] === 'auto' ? 'otomatis' : 'diedit manual'}
                    </span>
                  )}
                </h3>
                <p className="text-xs text-slate-500 mt-1">
                  Langkah: {wf.steps.length} ·
                  {wf.steps.filter((s) => validateWorkflowStep(s).valid && validateStepPerOp(s).valid).length} valid
                </p>
              </div>
              <div className="flex items-center gap-2">
                {SUPPORTED_OPS.map((op) => (
                  <button
                    key={op}
                    type="button"
                    onClick={() => addStep(wfName, op)}
                    className="btn-secondary text-xs px-3 py-1.5 hover:bg-brand-50"
                    disabled={op === 'parse_html' && wf.steps.some((s) => s.op === 'parse_html')}
                  >
                    + {op}
                  </button>
                ))}
                <button
                  type="button"
                  onClick={() => setExpandedWorkflow(isExpanded ? null : wfName)}
                  className="btn-secondary text-xs px-3 py-1.5"
                >
                  {isExpanded ? 'Tutup' : 'Buka'}
                </button>
              </div>
            </div>

            {isExpanded && (
              <div className="space-y-3">
                {/* Workflow Output Configuration */}
                <div className="border border-slate-200 rounded-lg p-3 bg-slate-50">
                  <h4 className="text-xs font-semibold text-slate-700 mb-3">Output Mapping</h4>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label htmlFor={`output-type-${wfName}`} className="text-xs font-semibold text-slate-700 block mb-1">
                        Output Type
                      </label>
                      <select
                        id={`output-type-${wfName}`}
                        value={wf.output?.type || 'RAW'}
                        onChange={(e) => setWorkflowOutput(wfName, { ...wf.output, type: e.target.value })}
                        className="input-field text-xs"
                      >
                        {SUPPORTED_OUTPUT_TYPES.map((t) => (
                          <option key={t} value={t}>{t}</option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label htmlFor={`output-mapping-${wfName}`} className="text-xs font-semibold text-slate-700 block mb-1">
                        Mapping (JSON)
                      </label>
                      <textarea
                        id={`output-mapping-${wfName}`}
                        className="input-field min-h-[60px] font-mono text-xs"
                        value={JSON.stringify(wf.output?.mapping || {}, null, 2)}
                        onChange={(e) => {
                          try {
                            const mapping = JSON.parse(e.target.value)
                            setWorkflowOutput(wfName, { ...wf.output, mapping })
                          } catch {
                            // ignore invalid JSON during typing
                          }
                        }}
                        placeholder='{"items": "searchResults", "title": "searchTitle"}'
                      />
                    </div>
                  </div>
                </div>

                {wf.steps.length === 0 && (
                  <p className="text-xs text-slate-500 text-center py-4">Belum ada langkah. Tambahkan langkah di atas.</p>
                )}
                {wf.steps.map((step, i) => (
                  <div
                    key={step.id || `step-${i}`}
                    className="border border-slate-200 rounded-lg p-3 bg-white"
                  >
                    <div className="flex items-start justify-between gap-3 mb-2">
                      <div className="flex items-center gap-2">
                        <select
                          value={step.op}
                          onChange={(e) => setStepOp(wfName, i, e.target.value)}
                          className="block text-sm text-slate-900 rounded-lg border border-slate-300 bg-white px-2 py-1 focus:ring-1 focus:ring-brand-500"
                        >
                          {SUPPORTED_OPS.map((op) => (
                            <option key={op} value={op}>
                              {op}
                            </option>
                          ))}
                        </select>
                        <button
                          type="button"
                          onClick={() => removeStep(wfName, i)}
                          className="text-xs text-red-700 hover:text-red-800 underline"
                        >
                          Hapus
                        </button>
                      </div>
                      {/* Dual validation: engineContract + per-op */}
                      <span className={`text-xs font-medium ${validateWorkflowStep(step).valid && validateStepPerOp(step).valid ? 'text-green-700' : 'text-red-700'}`}>
                        {validateWorkflowStep(step).valid && validateStepPerOp(step).valid ? 'valid' : 'invalid'}
                      </span>
                    </div>

                    {/* Param fields */}
                    <div className="space-y-2">
                      {OP_PARAMS[step.op]?.map((paramKey) => {
                        const stepValidation = validateStepPerOp(step)
                        const paramError = stepValidation.errors.find(e => e.startsWith(`${step.op}:`) && e.includes(paramKey))
                        return (
                          <div key={paramKey} className="flex items-start gap-2">
                            <label
                              htmlFor={`param-${wfName}-${i}-${paramKey}`}
                              className="text-xs font-semibold text-slate-700 w-24 shrink-0"
                            >
                              {paramKey}
                            </label>
                            <div className="flex-1">
                              {renderParamField(wfName, i, step.op, paramKey, step.params[paramKey] || '', setStepParam, paramError)}
                            </div>
                          </div>
                        )
                      })}

                      {/* saveAs */}
                      <div className="flex items-start gap-2 pt-1">
                        <label
                          htmlFor={`saveas-${wfName}-${i}`}
                          className="text-xs font-semibold text-slate-700 w-24 shrink-0"
                        >
                          saveAs
                        </label>
                        <input
                          id={`saveas-${wfName}-${i}`}
                          className="input-field flex-1"
                          value={step.saveAs || ''}
                          onChange={(e) => setStepSaveAs(wfName, i, e.target.value)}
                          placeholder="namaVariabel"
                        />
                      </div>

                      {/* Input context hint */}
                      <div className="text-xs text-slate-500 pl-6">
                        Input context: gunakan <code className="text-slate-500">{'{{variabel.path}}'}</code> di parameter untuk merujuk variabel sebelumnya (saveAs)
                      </div>

                      {/* Validation errors - both engineContract and per-op */}
                      {(() => {
                        const engineErrors = validateWorkflowStep(step).errors
                        const perOpErrors = validateStepPerOp(step).errors
                        const allErrors = [...engineErrors, ...perOpErrors]
                        if (allErrors.length > 0) {
                          return (
                            <p className="text-xs text-red-700 bg-red-50 px-2 py-1 rounded border border-red-100">
                              {allErrors.join('; ')}
                            </p>
                          )
                        }
                        return null
                      })()}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

function renderParamField(wfName, stepIndex, op, paramKey, value, setStepParam, paramError) {
  // Render param field berdasarkan tipe parameter
  if (paramKey === 'mode' || paramKey === 'group') {
    return (
      <select
        id={`param-${wfName}-${stepIndex}-${paramKey}`}
        value={value || ''}
        onChange={(e) => setStepParam(wfName, stepIndex, paramKey, e.target.value)}
        className="input-field"
      >
        {(paramKey === 'mode' ? MODE_OPTIONS : REGEX_MODE_OPTIONS).map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    )
  }

  if (paramKey === 'method') {
    return (
      <select
        id={`param-${wfName}-${stepIndex}-${paramKey}`}
        value={value || 'GET'}
        onChange={(e) => setStepParam(wfName, stepIndex, paramKey, e.target.value)}
        className="input-field"
      >
        {HTTP_METHOD_OPTIONS.map((m) => (
          <option key={m} value={m}>{m}</option>
        ))}
      </select>
    )
  }

  if (paramKey === 'attribute') {
    return (
      <input
        id={`param-${wfName}-${stepIndex}-${paramKey}`}
        className="input-field"
        value={value || ''}
        onChange={(e) => setStepParam(wfName, stepIndex, paramKey, e.target.value)}
        placeholder="contoh: href, src"
      />
    )
  }

  // JSON textarea fields (headers, body, mappings, operations, condition, predicate)
  const isJsonField = ['headers', 'body', 'mappings', 'operations', 'condition', 'predicate'].includes(paramKey)
  if (isJsonField) {
    return (
      <textarea
        id={`param-${wfName}-${stepIndex}-${paramKey}`}
        className={`input-field min-h-[80px] font-mono text-xs ${paramError ? 'border-red-300' : ''}`}
        value={value || ''}
        onChange={(e) => setStepParam(wfName, stepIndex, paramKey, e.target.value)}
        placeholder={paramPlaceholder(paramKey)}
      />
    )
  }

  // Default text input/textarea
  const isUrl = paramKey === 'url'
  return (
    <textarea
      id={`param-${wfName}-${stepIndex}-${paramKey}`}
      className={`input-field min-h-[60px] ${isUrl ? '' : 'font-mono text-xs'} ${paramError ? 'border-red-300' : ''}`}
      value={value || ''}
      onChange={(e) => setStepParam(wfName, stepIndex, paramKey, e.target.value)}
      placeholder={paramPlaceholder(paramKey)}
    />
  )
}

function paramPlaceholder(key) {
  const placeholders = {
    url: 'https://example.com/api/{{searchQuery}}',
    method: 'GET',
    headers: '{"Accept": "application/json", "User-Agent": "Anilili/1.0"}',
    body: '{"query": "{{searchQuery}}", "page": 1}',
    selector: '.anime-item',
    jsonPath: 'data.results[0].title',
    pattern: 'Episode (\\d+)',
    mappings: '{"title": "searchTitle", "url": "searchUrl"}',
    operations: '[{"type": "trim"}, {"type": "lowercase"}, {"type": "url_resolve", "baseUrl": "https://example.com"}]',
    condition: '{"type": "not_empty"}',
    predicate: '{"type": "not_empty"}',
    message: 'Validation failed',
  }
  return placeholders[key] || ''
}

WorkflowBuilder.propTypes = {
  workflows: PropTypes.object,
  origin: PropTypes.object,
  onEdit: PropTypes.func,
}
