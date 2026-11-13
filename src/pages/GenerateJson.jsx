// GenerateJson.jsx — Langkah 5 wizard: generate provider.json dari workflow yang sudah dikonfigurasi.
// Menampilkan preview JSON, validasi schema, dan opsi download/export.

import { useState, useCallback, useEffect } from 'react'
import { generateProviderJson } from '../lib/generator/providerGenerator.js'
import { validateSchema } from '../lib/tester/schemaValidator.js'
import { getBlockerStatus } from '../lib/contract/engineContract.js'
import { projectShape } from '../lib/projectPropTypes.js'

export default function GenerateJson({ project }) {
  const [providerJson, setProviderJson] = useState(null)
  const [schemaResult, setSchemaResult] = useState(null)
  const [isGenerating, setIsGenerating] = useState(false)
  const [error, setError] = useState(null)

  const generate = useCallback(() => {
    if (!project.workflows || Object.keys(project.workflows).length === 0) {
      setError('Belum ada workflow. Konfigurasikan workflow di langkah "Tinjau Aturan" terlebih dahulu.')
      setProviderJson(null)
      setSchemaResult(null)
      return
    }

    setIsGenerating(true)
    setError(null)

    try {
      const result = generateProviderJson(project)

      if (!result.providerJson) {
        setError(result.meta.errors.join('; ') || 'Generate gagal')
        setProviderJson(null)
        setSchemaResult(null)
      } else {
        setProviderJson(result.providerJson)
        const schema = validateSchema(result.providerJson)
        setSchemaResult(schema)
      }
    } catch (err) {
      setError(err.message || 'Generate gagal')
      setProviderJson(null)
      setSchemaResult(null)
    } finally {
      setIsGenerating(false)
    }
  }, [project])

  // Auto-generate when project/workflows change
  useEffect(() => {
    if (project.workflows && Object.keys(project.workflows).length > 0) {
      generate()
    }
  }, [project.workflows, project.endpoint, project.name, project.source, generate])

  const handleDownload = useCallback((filename, content) => {
    const blob = new Blob([JSON.stringify(content, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }, [])

  const hasWorkflows = project.workflows && Object.keys(project.workflows).length > 0
  const valid = providerJson ? schemaResult?.valid : false
  const blockerStatus = getBlockerStatus()
  const criticalBlockerCount = blockerStatus.critical.length

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Generate JSON</h2>
          <p className="text-slate-500 mt-1">
            Hasilkan provider.json dari konfigurasi proyek dan workflow. Validasi schema otomatis
            dan peringatan blocker engine ditampilkan.
          </p>
        </div>
        {hasWorkflows && (
          <button
            onClick={generate}
            className="btn-primary"
            disabled={isGenerating}
          >
            {isGenerating ? 'Menghasilkan...' : 'Generate Ulang'}
          </button>
        )}
      </div>

      {!hasWorkflows && (
        <div className="card text-center py-12 border-slate-200">
          <p className="text-slate-500">Belum ada workflow untuk di-generate.</p>
          <p className="text-sm text-slate-500 mt-2">
            Lengkapi langkah &ldquo;Tinjau Aturan&rdquo; untuk menambahkan langkah workflow search, episodes, servers, dan sources.
          </p>
        </div>
      )}

      {hasWorkflows && isGenerating && (
        <div className="card text-center py-8">
          <div className="text-brand-700 animate-spin inline-block text-3xl mb-2" aria-hidden="true">⟳</div>
          <p className="text-slate-700">Menghasilkan provider.json...</p>
        </div>
      )}

      {error && (
        <div className="card bg-red-50 border-red-100">
          <p className="text-sm text-red-700">Error: {error}</p>
        </div>
      )}

      {providerJson && (
        <>
          {/* Validasi Schema */}
          <div className="card space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                Validasi Schema
              </h3>
              <span className={`badge ${valid ? 'badge-passed' : 'badge-failed'}`}>
                {valid ? 'VALID' : 'INVALID'}
              </span>
            </div>

            {schemaResult && (
              <div className="space-y-2 text-sm">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                  <div className="bg-green-50 border border-green-100 rounded-lg p-3">
                    <p className="text-xs text-green-800 font-medium">Passed</p>
                    <p className="text-2xl font-bold text-green-700">{schemaResult.summary.passed}</p>
                  </div>
                  <div className="bg-red-50 border border-red-100 rounded-lg p-3">
                    <p className="text-xs text-red-800 font-medium">Failed</p>
                    <p className="text-2xl font-bold text-red-700">{schemaResult.summary.failed}</p>
                  </div>
                  <div className="bg-yellow-50 border border-yellow-100 rounded-lg p-3">
                    <p className="text-xs text-yellow-800 font-medium">Warnings</p>
                    <p className="text-2xl font-bold text-yellow-700">{schemaResult.summary.warnings}</p>
                  </div>
                </div>

                {schemaResult.failed.length > 0 && (
                  <details className="bg-red-50 border border-red-100 rounded-lg p-3">
                    <summary className="cursor-pointer text-sm font-medium text-red-800">
                      Lihat {schemaResult.failed.length} error validasi
                    </summary>
                    <ul className="list-disc list-inside text-xs text-red-700 mt-2 space-y-1">
                      {schemaResult.failed.map((f, i) => (
                        <li key={i}>{f.path}: {f.message}</li>
                      ))}
                    </ul>
                  </details>
                )}

                {schemaResult.warnings.length > 0 && (
                  <details className="bg-yellow-50 border border-yellow-100 rounded-lg p-3">
                    <summary className="cursor-pointer text-sm font-medium text-yellow-800">
                      Lihat {schemaResult.warnings.length} peringatan
                    </summary>
                    <ul className="list-disc list-inside text-xs text-yellow-700 mt-2 space-y-1">
                      {schemaResult.warnings.map((w, i) => (
                        <li key={i}>{w.path}: {w.message}</li>
                      ))}
                    </ul>
                  </details>
                )}
              </div>
            )}
          </div>

          {/* Blocker Engine */}
          {criticalBlockerCount > 0 && (
            <div className="card bg-amber-50 border-amber-100 space-y-3">
              <h3 className="text-sm font-semibold text-amber-800 uppercase tracking-wider">
                Blocker Engine Kritis ({criticalBlockerCount})
              </h3>
              <p className="text-xs text-amber-700">
                Blocker ini berasal dari engine Anilili (bukan generator) dan tidak dapat diperbaiki di sini.
                Provider.json valid tetapi ANILILI READY tidak tercapai sampai engine diperbaiki.
              </p>
              <ul className="text-xs text-amber-700 list-disc list-inside space-y-1">
                {blockerStatus.critical.map((b) => (
                  <li key={b.id}>
                    <strong>{b.id}</strong>: {b.message}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {/* Preview JSON */}
          <div className="card space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                Preview provider.json
              </h3>
              <div className="flex gap-2">
                <button
                  onClick={() => handleDownload('provider.json', providerJson)}
                  className="btn-secondary text-xs px-3 py-1.5"
                  disabled={!valid}
                >
                  Download provider.json
                </button>
                <button
                  onClick={() => handleDownload('validation-report.json', {
                    valid: schemaResult?.valid,
                    errors: schemaResult?.failed,
                    warnings: schemaResult?.warnings,
                    blockers: blockerStatus.critical.map((b) => b.id),
                    generatedAt: Date.now(),
                  })}
                  className="btn-secondary text-xs px-3 py-1.5"
                >
                  Download validation-report.json
                </button>
              </div>
            </div>

            <div className="bg-slate-900 rounded-lg p-4 max-h-96 overflow-auto" role="region" aria-label="Preview provider.json" tabIndex={0}>
              <pre className="text-green-300 text-xs font-mono whitespace-pre-wrap break-words">
                {JSON.stringify(providerJson, null, 2)}
              </pre>
            </div>
          </div>

          {/* Workflow Summary */}
          <div className="card space-y-3">
            <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
              Ringkasan Workflow
            </h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              {Object.entries(project.workflows).map(([name, wf]) => (
                <div key={name} className="border border-slate-200 rounded-lg p-3 bg-white">
                  <p className="font-medium text-slate-900 capitalize">{name}</p>
                  <p className="text-xs text-slate-500 mt-1">
                    {wf.steps?.length || 0} langkah · Output: {wf.output?.type || 'RAW'}
                  </p>
                  <div className="mt-2 space-y-1">
                    {wf.steps?.map((step, i) => (
                      <div key={i} className="text-xs text-slate-600 flex items-center gap-1">
                        <span className="badge badge-passed text-[10px]">{step.op}</span>
                        <span>{step.saveAs || '—'}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}

      {hasWorkflows && !providerJson && !isGenerating && !error && (
        <div className="card text-center py-12 border-slate-200">
          <p className="text-slate-500">Klik &ldquo;Generate Ulang&rdquo; untuk memproduksi provider.json.</p>
        </div>
      )}
    </div>
  )
}

GenerateJson.propTypes = {
  project: projectShape.isRequired,
}
