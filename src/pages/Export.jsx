// Export.jsx — Langkah 7 wizard: generate provider.json, laporan validasi, dan
// unduh berkas. Juga mendukung impor/ekspor bundel proyek (IndexedDB),
// test-report.json dengan hasil offline test aktual.
// provider.json diekspor TANPA _generatorMeta (baca providerGenerator.js).

import { useState, useCallback, useEffect, useMemo } from 'react'
import { generateProviderJson, generateValidationReport } from '../lib/generator/providerGenerator.js'
import { validateSchema } from '../lib/tester/schemaValidator.js'
import { projectStore } from '../lib/db/projectStore.js'
import { normalizeImportedProject } from '../lib/import/importProject.js'
import PropTypes from 'prop-types'
import { runMappedOffline, offlinePassedFlag } from '../lib/samples/runMapped.js'
import { effectiveSampleMap } from '../lib/samples/sampleMap.js'
import { projectShape } from '../lib/projectPropTypes.js'

const BUNDLE_VERSION = 1

function createProjectBundle(project, offlineResults = null) {
  return {
    version: BUNDLE_VERSION,
    exportedAt: new Date().toISOString(),
    project: {
      ...project,
      // Pemetaan sampel per workflow (berbasis fileName) ikut tersimpan; proyek lama dihitung otomatis.
      sampleMap: effectiveSampleMap(project),
      // Remove samples HTML content to keep bundle small (samples are large)
      samples: project.samples?.map((s) => ({
        fileName: s.fileName,
        type: s.type,
        htmlUrl: s.htmlUrl,
        entriesCount: s.entries?.length,
        apiCallsCount: s.apiCalls?.length,
        htmlPagesCount: s.htmlPages?.length,
        mediaUrlsCount: s.mediaUrls?.length,
        redirectChainsCount: s.redirectChains?.length,
        errors: s.errors,
      })) || [],
    },
    offlineTestResults: offlineResults,
  }
}

function createTestReport(project, providerJson, schema, validation, offlineResults) {
  return {
    version: BUNDLE_VERSION,
    generatedAt: new Date().toISOString(),
    provider: {
      id: providerJson?.providers?.[0]?.id,
      name: providerJson?.providers?.[0]?.name,
      endpoint: providerJson?.providers?.[0]?.endpoint,
      source: providerJson?.providers?.[0]?.source,
    },
    schemaValidation: {
      valid: schema.valid,
      errors: schema.failed.filter((f) => f.severity === 'error').map((f) => f.message),
      warnings: schema.warnings.map((w) => w.message),
      criticalBlockers: validation.criticalBlockers.map((b) => b.id),
      operationalLimitations: validation.operationalLimitations.map((b) => b.id),
    },
    compatibility: {
      status: validation.status,
      jsonValid: schema.valid,
      offlinePassed: offlineResults ? offlinePassedFlag(offlineResults) : false,
      livePassed: false, // requires live test
    },
    offlineTestResults: offlineResults?.map((r) => ({
      workflow: r.name,
      overallStatus: r.overallStatus,
      engineParser: r.engine || null,
      steps: r.stepResults.map((s) => ({
        index: s.stepIndex,
        op: s.op,
        status: s.status,
        error: s.error,
        evidence: s.evidence,
      })),
    })) || [],
  }
}

export default function Export({ project, onProjectChange, onImportSuccess }) {
  const [baseReport, setReport] = useState(null)
  const [providerJson, setProviderJson] = useState(null)
  const [needsInput, setNeedsInput] = useState([])
  const [configPrompt, setConfigPrompt] = useState(false)
  const [importError, setImportError] = useState(null)
  const [offlineResults, setOfflineResults] = useState(null)
  // Status kompatibilitas memuat hasil uji offline (gerbang parser engine), bukan hanya validasi struktur.
  const report = useMemo(() => {
    if (!baseReport || !providerJson || !offlineResults) return baseReport
    const passed = offlinePassedFlag(offlineResults)
    return {
      ...baseReport,
      validation: generateValidationReport(providerJson, { offline: { passed }, live: { passed: null } }),
    }
  }, [baseReport, providerJson, offlineResults])
  const [isRunningTests, setIsRunningTests] = useState(false)
  const [saveStatus, setSaveStatus] = useState({ type: null, message: '' })

  const generate = useCallback(() => {
    const generated = generateProviderJson(project)
    setProviderJson(generated.providerJson || null)
    setNeedsInput(generated.meta.needsInput || [])
    if (generated.meta.needsInput?.includes('configSourceUrl')) setConfigPrompt(true)
    setOfflineResults(null) // project berubah → uji offline harus diulang (effect di bawah)

    if (generated.providerJson) {
      const schema = validateSchema(generated.providerJson)
      const validation = generateValidationReport(generated.providerJson, {
        offline: { passed: null },
        live: { passed: null },
      })
      setReport({ schema, validation })
    } else {
      setReport(null)
    }
  }, [project])

  useEffect(() => {
    generate()
  }, [generate])

  const handleDownload = (filename, content) => {
    const blob = new Blob([JSON.stringify(content, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const handleImport = (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => {
      try {
        const imported = JSON.parse(ev.target.result)
        const projectData = normalizeImportedProject(imported)
        onProjectChange(projectData)
        onImportSuccess?.()
        setImportError(null)
      } catch (err) {
        setImportError(`Gagal mengimpor bundel proyek: ${err.message || 'format tidak dikenal'}`)
      }
    }
    reader.readAsText(file)
    // Re-selecting the same filename should trigger another change event.
    e.target.value = ''
  }

  const runOfflineTestsForReport = useCallback(async() => {
    if (!project.workflows || Object.keys(project.workflows).length === 0) return

    setIsRunningTests(true)
    try {
      // Tiap workflow diuji dengan sampel yang dipetakan untuknya (tanpa sampel → NOT_TESTED, bukan HTML demo).
      const results = runMappedOffline(project).perWorkflow
      setOfflineResults(results)
    } catch (err) {
      // eslint-disable-next-line no-console
      console.error('Offline test for report failed:', err)
    } finally {
      setIsRunningTests(false)
    }
  }, [project])

  // Run offline tests when providerJson is ready
  useEffect(() => {
    if (providerJson && !offlineResults) {
      runOfflineTestsForReport()
    }
  }, [providerJson, offlineResults, runOfflineTestsForReport])

  const handleDownloadBundle = () => {
    const bundle = createProjectBundle(project, offlineResults)
    handleDownload(`${project.id || 'project'}-bundle.json`, bundle)
  }

  const handleDownloadTestReport = () => {
    if (!providerJson || !report) return
    const testReport = createTestReport(project, providerJson, report.schema, report.validation, offlineResults)
    handleDownload(`${project.id || 'project'}-test-report.json`, testReport)
  }

  const handleSaveToIndexedDB = async() => {
    try {
      const saved = await projectStore.save(project)
      setSaveStatus({ type: 'success', message: `Proyek disimpan ke IndexedDB (ID: ${saved.id})` })
      setTimeout(() => setSaveStatus({ type: null, message: '' }), 3000)
    } catch (err) {
      setSaveStatus({ type: 'error', message: `Gagal menyimpan: ${err.message}` })
      setTimeout(() => setSaveStatus({ type: null, message: '' }), 3000)
    }
  }

  if (!project.name || !project.endpoint) {
    return (
      <div className="card space-y-4 max-w-3xl">
        {project.name ? (
          <div className="bg-yellow-50 border border-yellow-300 text-yellow-900 p-3 rounded space-y-2">
            <p role="alert" className="text-sm font-semibold">BUTUH INPUT: Provider Endpoint (base URL situs/API) belum diisi — status NEEDS_INPUT, provider.json belum dapat dibuat</p>
            <label htmlFor="export-endpoint" className="text-sm block">Provider Endpoint (harus https://)</label>
            <input
              id="export-endpoint"
              name="endpoint"
              className="input-field"
              value={project.endpoint || ''}
              onChange={(e) => onProjectChange({ ...project, endpoint: e.target.value.trim() })}
              placeholder="https://example.com"
            />
          </div>
        ) : (
          <div className="text-center py-8">
            <p className="text-slate-500">Belum ada data proyek. Lengkapi langkah Buat Proyek terlebih dahulu.</p>
          </div>
        )}
        {/* Impor tetap tersedia agar proyek lama bisa dipulihkan tanpa mengisi form */}
        <div className="border-t border-slate-200 pt-4">
          <h3 className="text-xs font-semibold text-slate-500 uppercase mb-3">Impor Bundel Proyek</h3>
          <label htmlFor="import-bundle" className="block w-full text-sm text-slate-500 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-sm file:font-semibold file:bg-brand-50 file:text-brand-700 hover:file:bg-brand-100">
            Impor bundel proyek (.json) — mendukung format lama (provider.json) & baru (bundle)
            <input
              id="import-bundle"
              type="file"
              accept=".json"
              onChange={handleImport}
              className="sr-only"
            />
          </label>
          {importError && (
            <p className="text-xs text-red-700 mt-1">{importError}</p>
          )}
        </div>
      </div>
    )
  }

  const hasWorkflows = project.workflows && Object.keys(project.workflows).length > 0
  const valid = providerJson ? report.schema.valid : false
  const ready = valid && report.validation.status === 'ANILILI_READY'

  return (
    <div className="card space-y-5 max-w-3xl">
      <div>
        <h2 className="text-lg font-semibold text-slate-900">Ekspor & Impor Proyek</h2>
        <p className="text-sm text-slate-500">
          Generate provider.json, unduh berkas, simpan ke IndexedDB, dan lihat status kompatibilitas.
        </p>
      </div>

      <div className="bg-slate-50 p-3 rounded border border-slate-200 text-sm space-y-1">
        <p><span className="text-slate-500">Provider Endpoint:</span> <span className="text-slate-900 break-all">{project.endpoint}</span></p>
        {project.configSourceUrl && (
          <p><span className="text-slate-500">Config Source URL (metadata.sourceUrl):</span> <span className="text-slate-900 break-all">{project.configSourceUrl}</span></p>
        )}
      </div>

      {/* Input tetap tampil setelah terisi (configPrompt di-latch) agar fokus tidak hilang saat mengetik. */}
      {configPrompt && (
        <div className="bg-yellow-50 border border-yellow-300 text-yellow-900 p-3 rounded space-y-2">
          {needsInput.includes('configSourceUrl') && (
            <p role="alert" className="text-sm font-semibold">BUTUH INPUT: Config Source URL (metadata.sourceUrl) belum diisi</p>
          )}
          <label htmlFor="export-config-source-url" className="text-sm block">Config Source URL (lokasi hosting provider.json)</label>
          <input
            id="export-config-source-url"
            name="configSourceUrl"
            className="input-field"
            value={project.configSourceUrl || ''}
            onChange={(e) => onProjectChange({ ...project, configSourceUrl: e.target.value })}
            placeholder="https://example.com/provider.json"
          />
        </div>
      )}

      <div className="space-y-4">
        <div className="flex items-center justify-between bg-slate-50 p-3 rounded border border-slate-200">
          <div>
            <p className="text-xs text-slate-500 uppercase font-semibold">Berkas utama</p>
            <p className="text-sm font-medium text-slate-900">provider.json</p>
          </div>
          <button
            onClick={() => handleDownload('provider.json', providerJson)}
            className="btn-secondary text-xs px-3 py-1.5"
            disabled={!providerJson || !valid}
          >
            Unduh provider.json
          </button>
        </div>

        <div className="flex items-center justify-between bg-slate-50 p-3 rounded border border-slate-200">
          <div>
            <p className="text-xs text-slate-500 uppercase font-semibold">Laporan validasi</p>
            <p className="text-sm font-medium text-slate-900">validation-report.json</p>
          </div>
          <button
            onClick={() => handleDownload('validation-report.json', report?.validation || {})}
            className="btn-secondary text-xs px-3 py-1.5"
            disabled={!report}
          >
            Unduh Laporan
          </button>
        </div>

        <div className="flex items-center justify-between bg-slate-50 p-3 rounded border border-slate-200">
          <div>
            <p className="text-xs text-slate-500 uppercase font-semibold">Bundel proyek (untuk edit ulang)</p>
            <p className="text-sm font-medium text-slate-900">project-bundle.json</p>
          </div>
          <button
            onClick={handleDownloadBundle}
            className="btn-secondary text-xs px-3 py-1.5"
            disabled={!providerJson}
          >
            Unduh Bundel
          </button>
        </div>

        <div className="flex items-center justify-between bg-slate-50 p-3 rounded border border-slate-200">
          <div>
            <p className="text-xs text-slate-500 uppercase font-semibold">Laporan uji offline</p>
            <p className="text-sm font-medium text-slate-900">test-report.json</p>
          </div>
          <button
            onClick={handleDownloadTestReport}
            className="btn-secondary text-xs px-3 py-1.5"
            disabled={!providerJson || !offlineResults || isRunningTests}
          >
            {isRunningTests ? 'Menguji...' : 'Unduh Test Report'}
          </button>
        </div>

        <div className="flex items-center justify-between bg-slate-50 p-3 rounded border border-slate-200">
          <div>
            <p className="text-xs text-slate-500 uppercase font-semibold">Simpan ke browser (IndexedDB)</p>
            <p className="text-sm font-medium text-slate-900">Persisten lokal</p>
          </div>
          <button
            onClick={handleSaveToIndexedDB}
            className="btn-primary text-xs px-3 py-1.5"
            disabled={!providerJson}
          >
            Simpan Proyek
          </button>
        </div>

        {saveStatus.type && (
          <div
            className={`text-xs px-2 py-1 rounded ${
              saveStatus.type === 'success'
                ? 'bg-green-100 text-green-800 border border-green-200'
                : 'bg-red-100 text-red-800 border border-red-200'
            }`}
            role="alert"
          >
            {saveStatus.message}
          </div>
        )}

        <div className="border-t border-slate-200 pt-4">
          <h3 className="text-xs font-semibold text-slate-500 uppercase mb-3">Impor Bundel Proyek</h3>
          <label htmlFor="import-bundle" className="block w-full text-sm text-slate-500 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-sm file:font-semibold file:bg-brand-50 file:text-brand-700 hover:file:bg-brand-100">
            Impor bundel proyek (.json) — mendukung format lama (provider.json) & baru (bundle)
            <input
              id="import-bundle"
              type="file"
              accept=".json"
              onChange={handleImport}
              className="sr-only"
            />
          </label>
          {importError && (
            <p className="text-xs text-red-700 mt-1">{importError}</p>
          )}
        </div>
      </div>

      <div className="bg-slate-50 p-4 rounded border border-slate-200">
        <h3 className="text-xs font-semibold text-slate-500 uppercase mb-3">Status Kompatibilitas (4-tier)</h3>
        <div className="flex items-center gap-3">
          <span
            className={`px-2 py-1 rounded text-xs font-semibold ${
              ready
                ? 'bg-green-100 text-green-800 border border-green-200'
                : valid
                  ? 'bg-blue-100 text-blue-800 border border-blue-200'
                  : 'bg-slate-100 text-slate-900 border border-slate-200'
            }`}
          >
            {report?.validation.status || 'Belum divalidasi'}
          </span>
          <p className="text-xs text-slate-500">
            {valid
              ? 'Struktur provider.json valid.'
              : providerJson
                ? 'Perbaiki error di atas sebelum mengekspor.'
                : 'Generate provider.json terlebih dahulu.'}
          </p>
        </div>
        {report && report.validation.criticalBlockers.length > 0 && (
          <p className="text-xs text-orange-700 mt-2">
            Ada {report.validation.criticalBlockers.length} blocker engine (tidak dapat diperbaiki generator).
            Status ANILILI READY tidak akan dicapai hingga blocker diatasi di sisi engine Anilili.
          </p>
        )}
        {!hasWorkflows && (
          <p className="text-xs text-yellow-700 mt-2">
            Belum ada workflow. Tambahkan langkah di halaman Tinjau Aturan agar provider.json lengkap.
          </p>
        )}
      </div>
    </div>
  )
}

Export.propTypes = {
  project: projectShape.isRequired,
  onProjectChange: PropTypes.func.isRequired,
  onImportSuccess: PropTypes.func,
}
