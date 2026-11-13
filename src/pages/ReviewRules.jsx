// ReviewRules.jsx — Halaman "Tinjau Aturan" (wizard step 4).
// Menggabungkan RuleEditor (review hasil inferensi analyzer) dan WorkflowBuilder
// (edit langkah workflow) ke dalam satu alur yang menghasilkan konfigurasi workflow.

import { useState, useMemo, useCallback } from 'react'
import RuleEditor from '../components/RuleEditor.jsx'
import WorkflowBuilder from '../components/WorkflowBuilder.jsx'
import PropTypes from 'prop-types'
import { inferredRulesShape } from '../lib/projectPropTypes.js'
import { manualConflicts } from '../lib/generator/workflowSync.js'

// `workflows`/`workflowOrigin` = project.workflows & project.workflowOrigin (sumber kebenaran tunggal).
// Inferensi (RuleEditor) hanya memperbarui workflow otomatis; workflow hasil edit manual tidak ditimpa
// kecuali pengguna memilih regenerasi dan mengonfirmasi peringatan.
export default function ReviewRules({
  endpoint, inferredRules, workflows, workflowOrigin, ruleValues,
  onInferred, onEditWorkflow, onRegenerate, onRuleValuesChange,
}) {
  const [inferred, setInferred] = useState({})
  const [confirming, setConfirming] = useState(null)
  const handleInferred = useCallback((next) => {
    setInferred(next)
    onInferred?.(next)
  }, [onInferred])
  const conflicts = useMemo(
    () => manualConflicts({ workflows, workflowOrigin }, inferred),
    [workflows, workflowOrigin, inferred]
  )

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Tinjau Aturan & Workflow</h2>
          <p className="text-slate-500 mt-1">
            Verifikasi aturan yang terdeteksi dari analisis sampel, lalu susun langkah-langkah
            workflow untuk search, episodes, servers, dan sources.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="col-span-1 lg:col-span-1">
          <RuleEditor endpoint={endpoint} inferredRules={inferredRules} onChange={handleInferred} savedValues={ruleValues} onValuesChange={onRuleValuesChange} />
        </div>
        <div className="col-span-1 lg:col-span-1">
          <WorkflowBuilder workflows={workflows} origin={workflowOrigin} onEdit={onEditWorkflow} />
        </div>
      </div>

      {conflicts.length > 0 && (
        <div className="card bg-yellow-50 border-yellow-200 space-y-3" role="region" aria-label="Workflow diedit manual">
          <h3 className="text-sm font-semibold text-yellow-900">Workflow diedit manual berbeda dari inferensi terbaru</h3>
          <p className="text-xs text-yellow-900">
            Edit manual dipertahankan dan tidak ditimpa otomatis. Regenerasi hanya terjadi bila Anda memintanya di sini.
          </p>
          <ul className="space-y-2">
            {conflicts.map((name) => (
              <li key={name} className="text-sm text-yellow-900" data-testid={`conflict-${name}`}>
                <strong>{name}</strong>
                {confirming === name ? (
                  <span role="alert" className="block mt-1">
                    Perubahan manual pada workflow {name} akan hilang dan diganti hasil inferensi. Timpa?{' '}
                    <button type="button" className="btn-secondary text-xs px-2 py-1 ml-1" onClick={() => { onRegenerate?.(name, inferred[name]); setConfirming(null) }}>
                      Ya, timpa {name}
                    </button>
                    <button type="button" className="btn-secondary text-xs px-2 py-1 ml-1" onClick={() => setConfirming(null)}>
                      Batal
                    </button>
                  </span>
                ) : (
                  <button type="button" className="btn-secondary text-xs px-2 py-1 ml-2" onClick={() => setConfirming(name)}>
                    Regenerasi {name} dari aturan
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {Object.keys(workflows || {}).length > 0 && (
        <div className="card bg-green-50 border-green-100">
          <h3 className="text-sm font-semibold text-green-800 mb-2">Workflow siap di-generate</h3>
          <ul className="text-sm text-green-700 space-y-1 list-disc list-inside">
            {Object.entries(workflows || {}).map(([name, wf]) => (
              <li key={name}>
                <strong>{name}:</strong> {wf.steps.length} langkah{' '}
                <span className="badge badge-passed ml-2">valid</span>
              </li>
            ))}
          </ul>
          <p className="text-xs text-slate-500 mt-3">
            Lanjut ke langkah &quot;Generate JSON&quot; untuk membuat provider.json.
          </p>
        </div>
      )}
    </div>
  )
}

ReviewRules.propTypes = {
  endpoint: PropTypes.string,
  inferredRules: inferredRulesShape,
  workflows: PropTypes.object,
  workflowOrigin: PropTypes.object,
  onInferred: PropTypes.func,
  onEditWorkflow: PropTypes.func,
  onRegenerate: PropTypes.func,
  ruleValues: PropTypes.object,
  onRuleValuesChange: PropTypes.func,
}
