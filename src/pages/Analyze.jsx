// Analyze.jsx — Langkah 3 wizard: analisis sampel untuk inferensi aturan.
// Menghubungkan hasil parsing (project.samples) ke ruleInference, menghasilkan inferredRules.

import { useState, useMemo, useCallback, useEffect } from 'react'
import { inferRules } from '../lib/analyzer/ruleInference.js'
import { mergeAnalysis, autoAssignSamples } from '../lib/samples/sampleMap.js'
import PropTypes from 'prop-types'
import { projectShape } from '../lib/projectPropTypes.js'

function createDom(html) {
  if (!html) return null
  const parser = new DOMParser()
  return parser.parseFromString(html, 'text/html')
}

export default function Analyze({ project, onProjectChange }) {
  const [inferredRules, setInferredRules] = useState(project.inferredRules || null)
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [error, setError] = useState(null)
  const [selectedSampleIndex, setSelectedSampleIndex] = useState(0)
  const [analysisResults, setAnalysisResults] = useState([])

  // Analisis satu sampel
  const analyzeSample = useCallback(async(sample, index) => {
    // Defensive: handle undefined sample
    if (!sample || !sample.html) {
      return { index, success: false, error: 'Sampel tidak memiliki HTML', rules: null }
    }

    const dom = createDom(sample.html)
    if (!dom) {
      return { index, success: false, error: 'Gagal parse HTML ke DOM', rules: null }
    }

    const parseResult = {
      html: sample.html,
      htmlUrl: sample.htmlUrl,
      resources: sample.resources || [],
    }

    const rules = inferRules(parseResult)
    return { index, success: true, rules, sampleName: sample.fileName }
  }, [])

  // Jalankan analisis semua sampel
  const runAnalysis = useCallback(async() => {
    if (!project.samples || project.samples.length === 0) {
      setError('Tidak ada sampel untuk dianalisis. Unggah sampel terlebih dahulu.')
      return
    }

    setIsAnalyzing(true)
    setError(null)
    setAnalysisResults([])

    try {
      const results = await Promise.all(
        (project.samples || []).map((sample, i) => analyzeSample(sample, i))
      )
      setAnalysisResults(results)

      // Gabungkan aturan (satu sampel terbaik per kelompok) + pilih sampel otomatis per workflow.
      const perSample = results
        .filter((r) => r.success)
        .map((r) => {
          const smp = project.samples[r.index]
          return { fileName: smp.fileName, htmlUrl: smp.htmlUrl, title: smp.title, rules: r.rules }
        })
      const mergedRules = mergeAnalysis(perSample)
      const sampleMap = autoAssignSamples(perSample, mergedRules)

      setInferredRules(mergedRules)
      onProjectChange({ ...project, inferredRules: mergedRules, ruleValues: null, sampleMap })
    } catch (err) {
      setError(err.message || 'Analisis gagal')
    } finally {
      setIsAnalyzing(false)
    }
  }, [project, analyzeSample, onProjectChange])

  // Auto-run saat samples berubah
  useEffect(() => {
    if (project.samples && project.samples.length > 0 && !inferredRules) {
      runAnalysis()
    }
  }, [project.samples, inferredRules, runAnalysis])

  const hasSamples = project.samples && project.samples.length > 0
  const hasRules = inferredRules && (
    (inferredRules.search?.length > 0) ||
    (inferredRules.episodes?.length > 0) ||
    (inferredRules.stream?.length > 0)
  )

  const summary = useMemo(() => {
    if (!inferredRules) return { total: 0, detected: 0, notDetected: 0, needsReview: 0 }
    const search = inferredRules.search || []
    const episodes = inferredRules.episodes || []
    const stream = inferredRules.stream || []
    const all = [...search, ...episodes, ...stream]
    return {
      total: all.length,
      detected: all.filter((r) => r.status === 'DETECTED').length,
      notDetected: all.filter((r) => r.status === 'NOT_DETECTED').length,
      needsReview: all.filter((r) => r.status === 'NEEDS_MANUAL_REVIEW').length,
    }
  }, [inferredRules])

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

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Analisis Sampel</h2>
          <p className="text-slate-500 mt-1">
            Jalankan inferensi aturan CSS selector dari HTML sampel. Hasil: container search,
            judul, daftar episode, pagination, iframe player, source media, dan server selector.
          </p>
        </div>
        {hasSamples && !isAnalyzing && (
          <button
            onClick={runAnalysis}
            className="btn-primary"
            disabled={isAnalyzing}
          >
            {inferredRules ? 'Analisis Ulang' : 'Jalankan Analisis'}
          </button>
        )}
      </div>

      {!hasSamples && (
        <div className="card text-center py-12 border-slate-200">
          <p className="text-slate-500">Belum ada sampel untuk dianalisis.</p>
          <p className="text-sm text-slate-500 mt-2">Kembali ke langkah &ldquo;Unggah Sampel&rdquo; dan unggah file HTML/MHTML/HAR.</p>
        </div>
      )}

      {hasSamples && (
        <>
          {/* Daftar sampel */}
          <div className="card space-y-3">
            <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
              Sampel Tersedia ({project.samples?.length || 0})
            </h3>
            <div className="space-y-2">
              {(project.samples || []).map((sample, i) => (
                <div
                  key={i}
                  className={`border rounded-lg p-3 ${
                    i === selectedSampleIndex ? 'border-brand-500 bg-brand-50' : 'border-slate-200 bg-white'
                  } cursor-pointer`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={i === selectedSampleIndex}
                  onClick={() => setSelectedSampleIndex(i)}
                  onKeyDown={(e) => {
                    if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) {
                      e.preventDefault()
                      setSelectedSampleIndex(i)
                    }
                  }}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="badge badge-passed">{sample.type?.toUpperCase()}</span>
                      <span className="text-sm font-medium text-slate-900">{sample.fileName}</span>
                      {sample.html && <span className="text-xs text-slate-600">({sample.html.length} chars)</span>}
                    </div>
                    {analysisResults[i] && (
                      <span className={`badge ${analysisResults[i].success ? 'badge-passed' : 'badge-failed'}`}>
                        {analysisResults[i].success ? 'Dianalisis' : 'Gagal'}
                      </span>
                    )}
                  </div>
                  {sample.errors && sample.errors.length > 0 && (
                    <p className="text-xs text-yellow-700 mt-1">{sample.errors.join('; ')}</p>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Progress / Status */}
          {isAnalyzing && (
            <div className="card text-center py-8">
              <div className="text-brand-700 animate-spin inline-block text-3xl mb-2" aria-hidden="true">⟳</div>
              <p className="text-slate-700">Menganalisis {project.samples?.length || 0} sampel...</p>
            </div>
          )}

          {error && (
            <div className="card bg-red-50 border-red-100">
              <p className="text-sm text-red-700">Error: {error}</p>
            </div>
          )}

          {/* Hasil Analisis per Sampel */}
          {analysisResults.length > 0 && !isAnalyzing && (
            <div className="card space-y-4">
              <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                Detail Hasil per Sampel
              </h3>
              <div className="space-y-3 max-h-96 overflow-y-auto" role="region" aria-label="Detail hasil per sampel" tabIndex={0}>
                {analysisResults.map((result) => (
                  <div
                    key={result.index}
                    className={`border rounded-lg p-4 ${
                      result.success ? 'bg-white border-slate-200' : 'bg-red-50 border-red-200'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-3 mb-2">
                      <div className="flex items-center gap-2">
                        <span className={`badge ${result.success ? 'badge-passed' : 'badge-failed'}`}>
                          {result.success ? 'Berhasil' : 'Gagal'}
                        </span>
                        <span className="text-sm font-medium text-slate-900">{result.sampleName}</span>
                      </div>
                    </div>

                    {result.error && (
                      <p className="text-sm text-red-700">{result.error}</p>
                    )}

                    {result.success && result.rules && (
                      <div className="space-y-2">
                        {[
                          { key: 'search', label: 'Search', rules: result.rules?.search || [] },
                          { key: 'episodes', label: 'Episodes', rules: result.rules?.episodes || [] },
                          { key: 'stream', label: 'Sources', rules: result.rules?.stream || [] },
                        ].map((group) => (
                          group.rules.length > 0 && (
                            <div key={group.key} className="border-t border-slate-100 pt-2">
                              <p className="text-xs font-medium text-slate-700 uppercase">{group.label}</p>
                              <div className="space-y-1 ml-4">
                                {group.rules.map((rule) => (
                                  <div key={rule.id} className="flex items-center gap-2 text-xs">
                                    <span className={`badge ${STATUS_BADGE[rule.status]}`}>{rule.status}</span>
                                    <span className="font-mono text-slate-700">{rule.id}</span>
                                    <span className={`font-medium ${CONFIDENCE_COLOR[confidenceLabel(rule.confidence)]}`}>
                                      {Math.round(rule.confidence * 100)}%
                                    </span>
                                    {rule.value && (
                                      <span className="font-mono text-slate-500 truncate max-w-xs">{rule.value}</span>
                                    )}{' '}
                                  </div>
                                ))}
                              </div>
                            </div>
                          )
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Ringkasan Aturan Tergabung */}
          {hasRules && (
            <div className="card bg-green-50 border-green-100 space-y-4">
              <h3 className="text-sm font-semibold text-green-800">Ringkasan Aturan Tergabung</h3>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
                <div>
                  <p className="font-medium text-green-800">Total: {summary.total}</p>
                  <p className="text-green-700">Detected: {summary.detected}</p>
                  <p className="text-yellow-700">Needs Review: {summary.needsReview}</p>
                  <p className="text-slate-500">Not Detected: {summary.notDetected}</p>
                </div>
                <div>
                  <p className="font-medium text-slate-700">Search Rules:</p>
                  <ul className="list-disc list-inside text-slate-600 space-y-1">
                    {(inferredRules.search || []).map((r) => (
                      <li key={r.id}><span className={`font-medium ${CONFIDENCE_COLOR[confidenceLabel(r.confidence)]}`}>{r.id}</span>: {r.status}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <p className="font-medium text-slate-700">Episodes Rules:</p>
                  <ul className="list-disc list-inside text-slate-600 space-y-1">
                    {(inferredRules.episodes || []).map((r) => (
                      <li key={r.id}><span className={`font-medium ${CONFIDENCE_COLOR[confidenceLabel(r.confidence)]}`}>{r.id}</span>: {r.status}</li>
                    ))}
                  </ul>
                </div>
                <div className="sm:col-span-3">
                  <p className="font-medium text-slate-700">Stream Rules:</p>
                  <ul className="list-disc list-inside text-slate-600 space-y-1">
                    {(inferredRules.stream || []).map((r) => (
                      <li key={r.id}><span className={`font-medium ${CONFIDENCE_COLOR[confidenceLabel(r.confidence)]}`}>{r.id}</span>: {r.status}</li>
                    ))}
                  </ul>
                </div>
              </div>
              <p className="text-xs text-slate-500">
                Aturan DETECTED siap digunakan. Aturan NEEDS_MANUAL_REVIEW perlu verifikasi selector.{' '}
                Lanjut ke &ldquo;Tinjau Aturan&rdquo; untuk mengonfigurasi workflow.
              </p>
            </div>
          )}

          {hasSamples && !hasRules && !isAnalyzing && analysisResults.length > 0 && (
            <div className="card bg-amber-50 border-amber-100">
              <p className="text-sm text-amber-800">Tidak ada aturan yang terdeteksi dari sampel yang dianalisis.</p>
              <p className="text-xs text-amber-700 mt-1">Coba unggah sampel yang lebih lengkap (halaman search, detail episode, player) atau buat workflow manual di Tinjau Aturan.</p>
            </div>
          )}
        </>
      )}
    </div>
  )
}

Analyze.propTypes = {
  project: projectShape.isRequired,
  onProjectChange: PropTypes.func.isRequired,
}
