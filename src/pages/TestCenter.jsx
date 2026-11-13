// TestCenter.jsx — Langkah "Uji Provider" wizard.
// Jalankan Uji Offline terhadap workflow project menggunakan offlineTester,
// dan tampilkan status kompatibilitas 4-tier (JSON VALID / OFFLINE PASSED /
// LIVE PASSED / ANILILI READY). Live Test memerlukan Cloudflare Worker
// yang dikonfigurasi (SECRET_ALLOWED_DOMAINS) — ditampilkan sebagai nonaktif
// dengan alasan jika belum diatur.

import { useState, useMemo, useCallback } from 'react'
import PropTypes from 'prop-types'
import { runWorkflowOffline } from '../lib/tester/offlineTester.js'
import { runMappedOffline } from '../lib/samples/runMapped.js'
import { SAMPLE_CATEGORIES, effectiveSampleMap, analyzeSamples, autoAssignSamples } from '../lib/samples/sampleMap.js'
import {
  validateProviderJson,
  getBlockerStatus,
  BLOCKER_STATUSES,
} from '../lib/contract/engineContract.js'
import { generateProviderJson } from '../lib/generator/providerGenerator.js'
import { projectShape } from '../lib/projectPropTypes.js'

const STATUS_BADGE = {
  PASSED: 'badge-passed',
  FAILED: 'badge-failed',
  PARTIAL: 'badge-partial',
  BLOCKED: 'badge-blocked',
  NOT_TESTED: 'badge-not-tested',
  NOT_DETECTED: 'badge-not-tested',
}

const FIX_RECOMMENDATION = {
  FAILED: (step, err) =>
    `Periksa step "${step.op}": ${err || 'eksekusi gagal'}. Pastikan selector dan params benar.`,
  NOT_DETECTED: (step) =>
    `Elemen tidak ditemukan untuk "${step.op}". Periksa selector CSS atau pastikan sampel HTML mengandung elemen yang cocok.`,
  NOT_TESTED: () =>
    'HTTP step dilewati dalam mode offline. Aktifkan Live Test (Cloudflare Worker) untuk memverifikasi langkah jaringan.',
  BLOCKED: (step) => `Op "${step.op}" tidak didukung engine. Gunakan op lain dari daftar yang didukung.`,
  PARTIAL: () =>
    'Hasil parsial. Periksa parameter filter/assert dan pastikan input mengandung data yang diharapkan.',
  PASSED: () => 'Step lolos. Tidak ada tindakan diperlukan.',
}

// Konversi objek params biasa ke Map (offlineTester mengharapkan Map).
// RemoteSourceTrust dari project (opsional) — jika user sudah grant trust.
function getProjectTrust(project) {
  return project && project.trust ? project.trust : null
}

export default function TestCenter({ project, onProjectChange }) {
  // HTML manual (ad-hoc, opsional): bila diisi, menimpa pemetaan sampel untuk SEMUA workflow pada uji ini.
  const [manualHtml, setManualHtml] = useState('')
  const [results, setResults] = useState([])
  const [isRunning, setIsRunning] = useState(false)
  const [error, setError] = useState(null)

  const [liveTestUrl, setLiveTestUrl] = useState('')
  const [liveResult, setLiveResult] = useState(null)
  const [liveIsRunning, setLiveIsRunning] = useState(false)
  const [liveError, setLiveError] = useState(null)

  const hasProject = Boolean(project && project.name)
  const hasWorkflows = hasProject && project.workflows && Object.keys(project.workflows).length > 0
  const workflowNames = useMemo(
    () => (hasWorkflows ? Object.keys(project.workflows) : []),
    [hasWorkflows, project]
  )

  const trust = useMemo(() => getProjectTrust(project), [project])
  const sampleMap = useMemo(() => effectiveSampleMap(project), [project])
  const samples = useMemo(() => project?.samples || [], [project])

  const toggleSample = (cat, fileName, on) => {
    const cur = sampleMap[cat] || []
    const next = on ? [...cur.filter((n) => n !== fileName), fileName] : cur.filter((n) => n !== fileName)
    onProjectChange?.({ ...project, sampleMap: { ...sampleMap, [cat]: next } })
  }
  const resetAuto = () => {
    onProjectChange?.({ ...project, sampleMap: autoAssignSamples(analyzeSamples(samples), project.inferredRules) })
  }

  // --- Uji Offline ---
  const runOfflineTests = useCallback(() => {
    if (!hasWorkflows) return
    setIsRunning(true)
    setError(null)
    try {
      if (manualHtml.trim()) {
        setResults(workflowNames.map((name) => ({
          ...runWorkflowOffline(name, project.workflows[name], { html: manualHtml, endpoint: project.endpoint || '' }),
          sampleName: 'HTML manual (ad-hoc)',
        })))
      } else {
        const { perSample, perWorkflow } = runMappedOffline(project, sampleMap)
        setResults(workflowNames.flatMap((name) => {
          const own = perSample.filter((r) => r.name === name)
          return own.length ? own : perWorkflow.filter((r) => r.name === name)
        }))
      }
    } catch (err) {
      setError(err.message || 'Uji offline gagal')
    } finally {
      setIsRunning(false)
    }
  }, [hasWorkflows, workflowNames, project, manualHtml, sampleMap])

  // --- Uji Live (placeholder — memerlukan worker yang dikonfigurasi) ---
  const runLiveTest = useCallback(async() => {
    if (!liveTestUrl) {
      setLiveError('Masukkan URL worker Live Test terlebih dahulu.')
      return
    }
    setLiveIsRunning(true)
    setLiveError(null)
    setLiveResult(null)
    try {
      const res = await fetch(liveTestUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          url: project.endpoint,
          method: 'GET',
        }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || `Live test gagal (${res.status})`)
      setLiveResult(data)
    } catch (err) {
      setLiveError(err.message || 'Live test gagal')
    } finally {
      setLiveIsRunning(false)
    }
  }, [liveTestUrl, project])

  const canRunLive = Boolean(liveTestUrl.trim())

  // --- Status kompatibilitas 4-tier ---
  const blockerStatus = useMemo(() => getBlockerStatus(), [])
  const criticalBlockerCount = blockerStatus.critical.length

  let jsonValid = false
  let offlinePassed = false
  let livePassed = false

  if (hasProject && project.endpoint) {
    const generated = generateProviderJson(project, { trust })
    if (generated.providerJson) {
      jsonValid = validateProviderJson(generated.providerJson, { trust }).valid
    }
  }

  offlinePassed =
    results.length > 0 && results.every((r) => r.overallStatus === 'PASSED')

  livePassed = Boolean(liveResult && liveResult.ok)

  const anililiReady =
    jsonValid && offlinePassed && livePassed && criticalBlockerCount === 0

  const tierStatus = [
    { id: BLOCKER_STATUSES.JSON_VALID, label: 'JSON VALID', ok: jsonValid },
    { id: BLOCKER_STATUSES.OFFLINE_PASSED, label: 'OFFLINE PASSED', ok: offlinePassed },
    { id: BLOCKER_STATUSES.LIVE_PASSED, label: 'LIVE PASSED', ok: livePassed },
    { id: BLOCKER_STATUSES.ANILILI_READY, label: 'ANILILI READY', ok: anililiReady },
  ]

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Test Center</h2>
          <p className="text-slate-500 mt-1">
            Uji provider JSON Anda secara offline dan live. Hasil tiap workflow step
            ditampilkan dengan status, error, dan rekomendasi koreksi.
          </p>
        </div>
      </div>

      {/* Kondisi empty: tidak ada project/workflow */}
      {!hasProject && (
        <div className="card text-center py-12 border-slate-200">
          <p className="text-slate-500">Belum ada proyek.</p>
          <p className="text-sm text-slate-500 mt-2">
            Lengkapi langkah &quot;Buat Proyek&quot; dan &quot;Tinjau Aturan&quot; terlebih dahulu.
          </p>
        </div>
      )}

      {!hasProject && null}

      {!hasWorkflows && hasProject && (
        <div className="card text-center py-12 border-slate-200">
          <p className="text-slate-500">Belum ada workflow.</p>
          <p className="text-sm text-slate-500 mt-2">
            Tambahkan langkah workflow di halaman &quot;Tinjau Aturan&quot; terlebih dahulu.
          </p>
        </div>
      )}

      {hasWorkflows && (
        <>
          {/* Sampel HTML untuk uji offline */}
          <div className="card space-y-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                  Sampel HTML (Uji Offline)
                </h3>
                <p className="text-xs text-slate-500 mt-1">
                  Masukkan HTML sampel yang akan direplay terhadap workflow Anda.
                  Tanpa jaringan, hanya langkah DOM (select, parse_html) yang diuji.
                </p>
              </div>
              <button
                onClick={runOfflineTests}
                className="btn-primary"
                disabled={isRunning}
              >
                {isRunning ? 'Menguji...' : 'Jalankan Uji Offline'}
              </button>
            </div>

            {samples.length > 0 ? (
              <fieldset className="space-y-3" data-testid="sample-map">
                <legend className="text-xs font-semibold text-slate-700">
                  Pemetaan sampel per workflow
                </legend>
                <p className="text-xs text-slate-500">
                  Dipilih otomatis dari bukti rule dan nama berkas; ubah manual bila perlu. Satu kategori boleh punya beberapa sampel.
                </p>
                {SAMPLE_CATEGORIES.map((cat) => (
                  <div key={cat.key} className="border border-slate-200 rounded-lg p-3" data-testid={`sample-cat-${cat.key}`}>
                    <p className="text-xs font-semibold text-slate-700 mb-1">
                      {cat.label}
                      {!cat.workflow && <span className="font-normal text-slate-500"> (hanya manual, tidak diuji otomatis)</span>}
                    </p>
                    <div className="flex flex-wrap gap-x-4 gap-y-1">
                      {samples.map((smp) => (
                        <label key={smp.fileName} className="flex items-center gap-1.5 text-xs text-slate-700">
                          <input
                            type="checkbox"
                            checked={(sampleMap[cat.key] || []).includes(smp.fileName)}
                            onChange={(e) => toggleSample(cat.key, smp.fileName, e.target.checked)}
                            aria-label={`${cat.label}: ${smp.fileName}`}
                          />
                          <span className="font-mono">{smp.fileName}</span>
                          {!smp.html && <span className="text-slate-500">(html tidak tersedia)</span>}
                        </label>
                      ))}
                    </div>
                    {cat.workflow && (sampleMap[cat.key] || []).length === 0 && (
                      <p className="text-xs text-amber-800 mt-1">Belum ada sampel → workflow {cat.workflow} NOT_TESTED.</p>
                    )}
                  </div>
                ))}
                <button type="button" onClick={resetAuto} className="btn-secondary text-xs">
                  Pilih ulang otomatis
                </button>
              </fieldset>
            ) : (
              <p className="text-xs text-amber-800">
                Tidak ada sampel HTML di proyek ini. Unggah sampel di langkah Sampel; workflow akan berstatus NOT_TESTED.
              </p>
            )}

            <label htmlFor="sample-html" className="text-xs font-semibold text-slate-700 block">
              HTML manual (opsional, ad-hoc — menimpa pemetaan untuk semua workflow)
            </label>
            <textarea
              id="sample-html"
              className="input-field font-mono text-xs h-32"
              value={manualHtml}
              onChange={(e) => setManualHtml(e.target.value)}
              placeholder="Kosongkan untuk memakai sampel yang dipetakan..."
            />

            {error && (
              <div className="bg-red-50 border border-red-100 rounded-lg p-3 text-sm text-red-700">
                Error: {error}
              </div>
            )}
          </div>

          {/* Hasil per workflow */}
          {results.length > 0 && (
            <div className="card space-y-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                    Hasil per Workflow Step
                  </h3>
                  <p className="text-xs text-slate-500 mt-1">
                    Status tiap langkah: PASSED / FAILED / PARTIAL / BLOCKED / NOT_TESTED / NOT_DETECTED
                  </p>
                </div>
              </div>

              {results.map((r) => {
                const workflowOk = r.overallStatus === 'PASSED'

                return (
                  <div key={`${r.name}:${r.sampleName || ''}`} className="border border-slate-200 rounded-lg bg-white p-4">
                    <div className="flex items-start justify-between gap-3 mb-3">
                      <div>
                        <div className="flex items-center gap-2 mb-1">
                          <span className="text-xs font-semibold text-slate-700 uppercase">
                            {r.name}
                          </span>
                          {r.sampleName && (
                            <span className="text-xs text-slate-500 font-mono">sampel: {r.sampleName}</span>
                          )}
                          <span
                            className={`badge ${STATUS_BADGE[r.overallStatus] || 'badge-not-tested'}`}
                            aria-label={`Overall: ${r.overallStatus}`}
                          >
                            {r.overallStatus}
                          </span>
                        </div>
                        <p className="text-xs text-slate-500">
                          {r.stepResults.length} langkah · overall{' '}
                          <span className={`font-medium ${workflowOk ? 'text-green-700' : 'text-red-700'}`}>
                            {r.overallStatus}
                          </span>
                          {r.engine && !r.engine.error && ` · parser engine: ${r.engine.items} item`}
                        </p>
                        {r.noSample && <p className="text-xs text-amber-800 mt-1">{r.note}</p>}
                        {r.engine?.error && (
                          <p className="text-xs text-red-700 mt-1">{r.engine.error}</p>
                        )}
                      </div>
                    </div>

                    <div className="overflow-x-auto" role="region" aria-label="Tabel hasil uji" tabIndex={0}>
                      <table className="w-full text-left text-sm">
                        <thead>
                          <tr className="border-b border-slate-200">
                            <th className="pb-2 text-slate-500 font-semibold">#</th>
                            <th className="pb-2 text-slate-500 font-semibold">Op</th>
                            <th className="pb-2 text-slate-500 font-semibold">Status</th>
                            <th className="pb-2 text-slate-500 font-semibold">Error</th>
                            <th className="pb-2 text-slate-500 font-semibold">Rekomendasi</th>
                            <th className="pb-2 text-slate-500 font-semibold">Evidence</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                          {r.stepResults.map((s, i) => {
                            const rec = FIX_RECOMMENDATION[s.status]
                              ? FIX_RECOMMENDATION[s.status](s.step || { op: s.op }, s.error)
                              : ''
                            return (
                              <tr key={i} className="group">
                                <td className="py-2 text-slate-700 font-mono">{s.stepIndex + 1}</td>
                                <td className="py-2 text-slate-700 font-mono">{s.op}</td>
                                <td className="py-2">
                                  <span
                                    className={`badge ${STATUS_BADGE[s.status] || 'badge-not-tested'}`}
                                    aria-label={`Status: ${s.status}`}
                                  >
                                    {s.status}
                                  </span>
                                </td>
                                <td className="py-2">
                                  {s.error ? (
                                    <span className="text-xs text-red-700 truncate max-w-xs" title={s.error}>
                                      {s.error}
                                    </span>
                                  ) : (
                                    <span className="text-xs text-slate-500">—</span>
                                  )}
                                </td>
                                <td className="py-2">
                                  <span className="text-xs text-slate-600">{rec}</span>
                                </td>
                                <td className="py-2">
                                  <span className="text-xs text-slate-500 font-mono truncate max-w-xs" title={
                                    s.evidence?.join('; ')
                                  }>
                                    {s.evidence?.join('; ') || '—'}
                                  </span>
                                </td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>

                    {r.stepResults.some((s) => s.status === 'FAILED' || s.status === 'BLOCKED') && (
                      <p className="text-xs text-red-700 mt-2">
                        Workflow <strong>{r.name}</strong> memiliki langkah yang gagal atau diblokir.
                        Perbaiki selector/params lalu jalankan uji ulang.
                      </p>
                    )}
                  </div>
                )
              })}
            </div>
          )}

          {/* Uji Live */}
          <div className="card space-y-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
                  Uji Live
                </h3>
                <p className="text-xs text-slate-500 mt-1">
                  Mengirim request ke provider endpoint melalui Cloudflare Worker.
                  Memerlukan worker yang disebarkan dengan SECRET_ALLOWED_DOMAINS.
                </p>
              </div>
            </div>

            <div className="space-y-3">
              <div className="flex items-center gap-3">
                <input
                  id="live-worker-url"
                  className="input-field flex-1"
                  value={liveTestUrl}
                  onChange={(e) => setLiveTestUrl(e.target.value)}
                  placeholder="https://contoh.workers.dev/api/proxy"
                  disabled={!canRunLive}
                />
                <label htmlFor="live-worker-url" className="sr-only">
                  URL worker Live Test
                </label>
                <button
                  onClick={runLiveTest}
                  className="btn-secondary"
                  disabled={!canRunLive || liveIsRunning}
                >
                  {liveIsRunning ? 'Menguji...' : 'Jalankan Uji Live'}
                </button>
              </div>

              {!canRunLive && (
                <div className="bg-amber-50 border border-amber-100 rounded-lg p-3">
                  <div className="flex items-start gap-2">
                    <span className="text-yellow-700 font-medium text-xs">⚠</span>
                    <div className="text-sm text-yellow-700">
                      Live Test belum dikonfigurasi. Isi URL worker Cloudflare yang telah
                      disebarkan dengan SECRET_ALLOWED_DOMAINS di wrangler.toml. Tanpa
                      konfigurasi ini, status LIVE PASSED dan ANILILI READY tidak akan
                      tercapai.
                    </div>
                  </div>
                </div>
              )}

              {liveError && (
                <div className="bg-red-50 border border-red-100 rounded-lg p-3 text-sm text-red-700">
                  Error: {liveError}
                </div>
              )}

              {liveResult && (
                <div className="bg-green-50 border border-green-100 rounded-lg p-3">
                  <p className="text-xs font-semibold text-green-800 mb-1">
                    Hasil Uji Live
                  </p>
                  <p className="text-xs text-green-700">
                    Status: {liveResult.status} {liveResult.ok ? '✓' : '✗'} ·
                    URL: {liveResult.url || '—'}
                  </p>
                </div>
              )}
            </div>
          </div>

          {/* Status kompatibilitas 4-tier */}
          <div className="card bg-slate-50 border-slate-200">
            <h3 className="text-xs font-semibold text-slate-500 uppercase mb-3">
              Status Kompatibilitas (4-tier)
            </h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {tierStatus.map((tier) => {
                const badge = tier.ok ? 'badge-passed' : 'badge-not-tested'
                return (
                  <div
                    key={tier.id}
                    className="flex items-center justify-between p-3 rounded border"
                  >
                    <div>
                      <p className="text-xs font-medium text-slate-700">{tier.label}</p>
                      <p className="text-xs text-slate-500">
                        {tier.ok ? 'Terkapai' : 'Belum tercapai'}
                      </p>
                    </div>
                    <span
                      className={`badge px-3 py-1 ${badge}`}
                      aria-label={`${tier.label}: ${tier.ok ? 'tercapai' : 'belum tercapai'}`}
                    >
                      {tier.ok ? '✓' : '✗'}
                    </span>
                  </div>
                )
              })}
            </div>

            <div className="mt-4 border-t border-slate-200 pt-3">
              <p className="text-xs text-slate-500">
                ANILILI READY hanya tercapai jika JSON valid, offline & live lolos,
                <strong>dan tidak ada critical blocker engine</strong>. Saat ini ada{' '}
                <span className="font-medium">{criticalBlockerCount}</span> critical blocker engine
                (baca engineContract.js), sehingga ANILILI READY tidak akan ditampilkan
                hingga blocker diatasi di sisi engine Anilili.
              </p>
              {criticalBlockerCount > 0 && (
                <ul className="text-xs text-slate-500 mt-2 space-y-0.5 list-disc list-inside">
                  {blockerStatus.critical.map((b) => (
                    <li key={b.id}>{b.id}</li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

TestCenter.propTypes = {
  project: projectShape.isRequired,
  onProjectChange: PropTypes.func,
}
