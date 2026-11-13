// RuleEditor.jsx — Tinjau aturan hasil inferensi analyzer, edit selector, dan
// hasilkan konfigurasi workflow step yang siap digunakan WorkflowBuilder.
// Mendukung: edit selector, saveAs, pilih op (select/http/parse_html/etc),
// input context, preview workflow steps dengan validasi.
// Preview aman: tidak ada iframe dengan allow-scripts + allow-same-origin.

import { useState, useMemo, useCallback, useEffect, useRef } from 'react'
import PropTypes from 'prop-types'
import { inferWorkflows } from '../lib/generator/workflowInference.js'
import { validateWorkflowStep } from '../lib/contract/engineContract.js'
import { inferredRulesShape } from '../lib/projectPropTypes.js'

// Rule yang hanya informatif (tidak menjadi step): wadah/daftar & pagination. Step dibangun dari rule per-field.
const INFO_ONLY = new Set(['search-container', 'episode-list', 'episode-pagination'])

const STATUS_BADGE = {
  DETECTED: 'badge-passed',
  NOT_DETECTED: 'badge-not-tested',
  NEEDS_MANUAL_REVIEW: 'badge-partial',
}

function confidenceLabel(c) {
  if (c >= 0.7) return 'high'
  if (c >= 0.4) return 'medium'
  return 'low'
}

const CONFIDENCE_COLOR = { high: 'text-green-700', medium: 'text-yellow-700', low: 'text-slate-500' }

// ruleId -> { enabled, selector, attribute, saveAs }. Nilai tersimpan (edit user) menimpa default.
function buildRuleValues(inferredRules, saved) {
  const next = {}
  const allRules = [
    ...(inferredRules?.search || []),
    ...(inferredRules?.episodes || []),
    ...(inferredRules?.stream || []),
  ]
  allRules.forEach((rule) => {
    next[rule.id] = saved?.[rule.id] || {
      enabled: rule.status === 'DETECTED' && !!rule.value,
      selector: rule.value || '',
      attribute: rule.attribute || '',
      saveAs: '',
    }
  })
  return next
}

// Terapkan edit user ke rule analyzer → input inferWorkflows.
function effectiveRules(rules, values) {
  return (rules || []).map((rule) => {
    const rv = values[rule.id]
    if (!rv) return rule
    return { ...rule, enabled: !!rv.enabled && !INFO_ONLY.has(rule.id), value: rv.selector || null, attribute: rv.attribute || rule.attribute || null, saveAs: rv.saveAs || undefined }
  })
}

export default function RuleEditor({ endpoint, inferredRules, onChange, savedValues, onValuesChange }) {
  const [ruleValues, setRuleValues] = useState(() => buildRuleValues(inferredRules, savedValues))
  const valuesRef = useRef(ruleValues)
  const seededFor = useRef(inferredRules)

  // Analisis ulang menghasilkan inferredRules baru → reset ke default (bukan saat remount).
  useEffect(() => {
    if (seededFor.current === inferredRules) return
    seededFor.current = inferredRules
    const next = buildRuleValues(inferredRules)
    valuesRef.current = next
    setRuleValues(next)
  }, [inferredRules])

  const handleChange = useCallback((ruleId, field, value) => {
    const next = { ...valuesRef.current, [ruleId]: { ...valuesRef.current[ruleId], [field]: value } }
    valuesRef.current = next
    setRuleValues(next)
    onValuesChange?.(next)
  }, [onValuesChange])

  // Workflow (steps + output) disusun dari rule aktif; bukti kurang → tanpa workflow + alasan.
  const { workflows, report } = useMemo(() => {
    const eff = {
      search: effectiveRules(inferredRules?.search, ruleValues),
      episodes: effectiveRules(inferredRules?.episodes, ruleValues),
      stream: effectiveRules(inferredRules?.stream, ruleValues),
    }
    return inferWorkflows(eff, { endpoint })
  }, [inferredRules, ruleValues, endpoint])

  // Panggil onChange setiap kali workflows berubah.
  useEffect(() => {
    onChange?.(workflows)
  }, [workflows, onChange])

  const allRules = useMemo(
    () => [...(inferredRules?.search || []), ...(inferredRules?.episodes || []), ...(inferredRules?.stream || [])],
    [inferredRules]
  )
  const hasRules = allRules.length > 0

  return (
    <div className="space-y-6">
      {!hasRules && (
        <div className="card text-center py-12">
          <p className="text-slate-500">
            Belum ada aturan terdeteksi. Unggah sampel HTML terlebih dahulu di langkah Analisis.
          </p>
          <p className="text-sm text-slate-500 mt-2">
            Anda masih dapat membuat workflow secara manual di bawah.
          </p>
        </div>
      )}

      <div className="card space-y-4">
        <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
          Hasil Inferensi Aturan
        </h3>
        <p className="text-xs text-slate-500">
          Aturan <span className="font-medium">DETECTED</span> dapat langsung digunakan.{' '}
          <span className="font-medium">NOT_DETECTED</span> perlu review manual.{' '}
          <span className="font-medium">NEEDS_MANUAL_REVIEW</span> perlu verifikasi selector.
        </p>

        <div className="space-y-3">
          {allRules.map((rule) => {
            const rv = ruleValues[rule.id] || { enabled: false, selector: rule.value || '', attribute: '', saveAs: '' }
            const info = INFO_ONLY.has(rule.id)
            const isQuery = rule.type === 'query-param'
            const badgeClass = STATUS_BADGE[rule.status] || 'badge-not-tested'
            const confLabel = confidenceLabel(rule.confidence)

            return (
              <div key={rule.id} className="border border-slate-200 rounded-lg p-4 bg-white">
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div>
                    <div className="flex items-center gap-1.5 mb-1">
                      <span className="text-xs font-semibold text-slate-700 uppercase">{rule.id}</span>
                      <span className={`badge px-2 py-1 ${badgeClass}`}>{rule.status}</span>
                    </div>
                    <p className="text-xs text-slate-500">{rule.evidence?.[0] || ''}</p>
                  </div>
                  <span className={`text-xs font-medium ${CONFIDENCE_COLOR[confLabel]}`}>
                    {Math.round(rule.confidence * 100)}%
                  </span>
                  <span className="text-xs text-slate-500">{confLabel}</span>
                </div>

                <div className="space-y-2">
                  {info ? (
                    <p className="text-xs text-slate-500">
                      Informasi struktur (tidak menjadi langkah workflow); field di bawahnya yang dipakai.
                    </p>
                  ) : (
                    <label className="flex items-center gap-2 cursor-pointer text-sm">
                      <input
                        type="checkbox"
                        checked={rv.enabled}
                        onChange={(e) => handleChange(rule.id, 'enabled', e.target.checked)}
                      />
                      <span className={`${rv.enabled ? 'text-slate-900' : 'text-slate-500'} font-medium`}>
                        Aktifkan aturan ini
                      </span>
                    </label>
                  )}

                  {rv.enabled && !info && (
                    <div className="space-y-2">
                      <label htmlFor={`selector-${rule.id}`} className="text-xs font-semibold text-slate-700 block">
                        {isQuery ? 'Nama parameter query' : 'CSS Selector'}
                      </label>
                      <input
                        id={`selector-${rule.id}`}
                        className="input-field font-mono text-xs"
                        value={rv.selector}
                        onChange={(e) => handleChange(rule.id, 'selector', e.target.value)}
                        placeholder={rule.value || ''}
                      />
                      {!isQuery && (
                        <>
                          <label htmlFor={`attr-${rule.id}`} className="text-xs font-semibold text-slate-700 block">
                            Atribut DOM (kosong = teks elemen)
                          </label>
                          <input
                            id={`attr-${rule.id}`}
                            className="input-field font-mono text-xs"
                            value={rv.attribute || ''}
                            onChange={(e) => handleChange(rule.id, 'attribute', e.target.value.trim())}
                            placeholder="href / src / title"
                          />
                        </>
                      )}
                      {!isQuery && (
                        <>
                          <label htmlFor={`saveas-${rule.id}`} className="text-xs font-semibold text-slate-700 block mt-2">
                            Simpan sebagai variabel (saveAs, opsional)
                          </label>
                          <input
                            id={`saveas-${rule.id}`}
                            className="input-field font-mono text-xs"
                            value={rv.saveAs}
                            onChange={(e) => handleChange(rule.id, 'saveAs', e.target.value.trim())}
                            placeholder="otomatis"
                          />
                        </>
                      )}
                    </div>
                  )}

                  {rule.evidence && rule.evidence.length > 1 && (
                    <details className="text-xs text-slate-500">
                      <summary className="cursor-pointer underline underline-offset-2 hover:text-slate-700">
                        Lihat evidence lengkap
                      </summary>
                      <ul className="list-disc list-inside mt-1 space-y-0.5">
                        {rule.evidence.map((e, i) => (
                          <li key={i}>{e}</li>
                        ))}
                      </ul>
                    </details>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <div className="card">
        <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider mb-4">
          Preview Workflow Steps
        </h3>
        <ul className="space-y-1 mb-4 text-xs" aria-label="Status inferensi workflow">
          {Object.entries(report).map(([name, r]) => (
            <li key={name} data-testid={`inference-${name}`}>
              <span className={`badge ${r.status === 'READY' ? 'badge-passed' : 'badge-partial'}`}>
                {r.status === 'READY' ? 'READY' : 'NEEDS_REVIEW'}
              </span>{' '}
              <strong>{name}</strong>
              {workflows[name] && (
                <code className="text-slate-700"> output {workflows[name].output.type} {JSON.stringify(workflows[name].output.mapping)}</code>
              )}
              {r.reasons.length > 0 && <span className="text-slate-500"> — {r.reasons.join(' ')}</span>}
            </li>
          ))}
        </ul>
        <div className="overflow-x-auto" role="region" aria-label="Tabel hasil validasi" tabIndex={0}>
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200">
                <th className="pb-2 text-slate-500 font-semibold">Workflow</th>
                <th className="pb-2 text-slate-500 font-semibold">Op</th>
                <th className="pb-2 text-slate-500 font-semibold">Selector / Params</th>
                <th className="pb-2 text-slate-500 font-semibold">Save As</th>
                <th className="pb-2 text-slate-500 font-semibold">Validasi</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {Object.entries(workflows).length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-4 text-center text-slate-500 text-sm">
                    Belum ada langkah workflow. Aktifkan minimal satu aturan di atas.
                  </td>
                </tr>
              ) : (
                Object.entries(workflows).flatMap(([groupName, { steps }]) =>
                  steps.map((step, i) => {
                    const validation = validateWorkflowStep(step)
                    return (
                      <tr key={`${groupName}-${i}`}>
                        <td className="py-2 text-slate-700 font-medium">{groupName}</td>
                        <td className="py-2 text-slate-700">{step.op}</td>
                        <td className="py-2 text-slate-700 font-mono">{step.op === 'select' ? step.params.selector : JSON.stringify(step.params)}</td>
                        <td className="py-2 text-slate-700 font-mono">{step.saveAs}</td>
                        <td className="py-2">
                          {validation.valid ? (
                            <span className="badge badge-passed">valid</span>
                          ) : (
                            <span className="badge badge-failed">{validation.errors?.[0] || 'invalid'}</span>
                          )}
                        </td>
                      </tr>
                    )
                  })
                )
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

RuleEditor.propTypes = {
  endpoint: PropTypes.string,
  inferredRules: inferredRulesShape,
  onChange: PropTypes.func,
  savedValues: PropTypes.object,
  onValuesChange: PropTypes.func,
}
