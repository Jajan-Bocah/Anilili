// UploadSamples.jsx — Langkah 2 wizard: unggah sampel (HTML, MHT/MHTML, HAR).
// Parsing di Web Worker (non-blokir UI). Hasil disimpan ke project.samples.

import { useState, useCallback, useRef, useEffect } from 'react'

const ACCEPT_TYPES = ['.html', '.htm', '.mht', '.mhtml', '.har']

function fileToText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

// Import workers using Vite's native worker import syntax
// This gives factory functions, not URLs
import HarWorker from '../workers/harWorker.js?worker'
import MhtmlWorker from '../workers/mhtmlWorker.js?worker'
import HtmlWorker from '../workers/htmlWorker.js?worker'
import PropTypes from 'prop-types'
import { projectShape } from '../lib/projectPropTypes.js'

export default function UploadSamples({ project, onProjectChange }) {
  const [files, setFiles] = useState([])
  const [parseResults, setParseResults] = useState([])
  const [isParsing, setIsParsing] = useState(false)
  const [error, setError] = useState(null)
  const workersRef = useRef({ har: null, mhtml: null, html: null })

  // Inisialisasi workers
  useEffect(() => {
    const workers = workersRef.current
    workers.har = new HarWorker()
    workers.mhtml = new MhtmlWorker()
    workers.html = new HtmlWorker()

    return () => {
      workers.har?.terminate()
      workers.mhtml?.terminate()
      workers.html?.terminate()
    }
  }, [])

  const handleFilesChange = useCallback((e) => {
    const newFiles = Array.from(e.target.files || [])
    const validFiles = newFiles.filter((f) => ACCEPT_TYPES.some((ext) => f.name.toLowerCase().endsWith(ext)))
    if (validFiles.length !== newFiles.length) {
      setError('Beberapa file tidak didukung. Hanya HTML, MHT, MHTML, dan HAR.')
    }
    setFiles(validFiles)
  }, [])

  const parseFile = useCallback(async(file) => {
    const ext = file.name.toLowerCase().split('.').pop()
    let workerKey = ext === 'har' ? 'har' : ext === 'mhtml' || ext === 'mht' ? 'mhtml' : 'html'
    const worker = workersRef.current[workerKey]

    if (!worker) {
      return { fileName: file.name, success: false, error: `Worker ${workerKey} tidak tersedia` }
    }

    const content = await fileToText(file)
    const baseUrl = project.endpoint || ''

    return new Promise((resolve) => {
      const id = `parse-${Date.now()}-${Math.random().toString(36).slice(2)}`
      const timeout = setTimeout(() => {
        resolve({ fileName: file.name, success: false, error: 'Timeout parsing (30s)' })
      }, 30000)

      const handler = (event) => {
        const { id: msgId, success, error, ...data } = event.data
        if (msgId === id) {
          clearTimeout(timeout)
          worker.removeEventListener('message', handler)
          if (success) {
            resolve({ fileName: file.name, success: true, type: workerKey, ...data })
          } else {
            resolve({ fileName: file.name, success: false, error: error || 'Parsing gagal' })
          }
        }
      }

      // Add error handler
      worker.addEventListener('error', () => {})

      // Add message error handler
      worker.addEventListener('messageerror', () => {})

      worker.addEventListener('message', handler)
      worker.postMessage({ id, content, contentType: file.type, baseUrl })
    })
  }, [project.endpoint])

  const handleParse = useCallback(async() => {
    if (files.length === 0) return
    setIsParsing(true)
    setError(null)
    setParseResults([])

    try {
      const results = await Promise.all(files.map(parseFile))
      setParseResults(results)

      const successful = results.filter((r) => r.success)
      if (successful.length > 0) {
        const samples = successful.map((r) => ({
          fileName: r.fileName,
          type: r.type,
          html: r.html,
          htmlUrl: r.htmlUrl,
          resources: r.resources,
          entries: r.entries,
          apiCalls: r.apiCalls,
          htmlPages: r.htmlPages,
          mediaUrls: r.mediaUrls,
          redirectChains: r.redirectChains,
          errors: r.errors,
        }))
        onProjectChange({ ...project, samples })
      }
    } catch (err) {
      setError(err.message || 'Parsing gagal')
    } finally {
      setIsParsing(false)
    }
  }, [files, parseFile, project, onProjectChange])

  const handleRemoveSample = useCallback((index) => {
    setParseResults((prev) => prev.filter((_, i) => i !== index))
    if (project.samples) {
      const nextSamples = project.samples.filter((_, i) => i !== index)
      onProjectChange({ ...project, samples: nextSamples })
    }
  }, [project, onProjectChange])

  const hasSamples = project.samples && project.samples.length > 0

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Unggah Sampel</h2>
          <p className="text-slate-500 mt-1">
            Unggah file HTML, MHT/MHTML, atau HAR dari target provider. File diparsing di
            Web Worker (tidak memblokir UI). Hasil: HTML bersih, daftar API call, resource, dan chain redirect.
          </p>
        </div>
      </div>

      {/* Dropzone / File Input */}
      <div className="card space-y-4">
        <div className="border-2 border-dashed border-slate-300 rounded-lg p-8 text-center">
          <input
            id="sample-files"
            type="file"
            multiple
            accept={ACCEPT_TYPES.join(',')}
            onChange={handleFilesChange}
            className="sr-only"
            disabled={isParsing}
          />
          <label htmlFor="sample-files" className="cursor-pointer">
            <div className="text-4xl mb-2" aria-hidden="true">📁</div>
            <p className="text-slate-700 font-medium">Klik atau tarik file ke sini</p>
            <p className="text-xs text-slate-500 mt-1">
              HTML (.html, .htm) &middot; MHT/MHTML (.mht, .mhtml) &middot; HAR (.har)
            </p>
          </label>
        </div>

        {files.length > 0 && (
          <div className="space-y-2">
            <p className="text-sm font-medium text-slate-700">
              {files.length} file dipilih:
            </p>
            <ul className="text-sm text-slate-600 space-y-1">
              {files.map((f, i) => (
                <li key={i} className="flex items-center justify-between">
                  <span>{f.name} ({(f.size / 1024).toFixed(1)} KB)</span>
                  <span className="text-xs text-slate-500">{f.type || 'unknown'}</span>
                </li>
              ))}
            </ul>
            <button
              onClick={handleParse}
              className="btn-primary"
              disabled={isParsing}
            >
              {isParsing ? 'Memparsing...' : 'Parse Semua'}
            </button>
          </div>
        )}

        {error && (
          <div className="bg-red-50 border border-red-100 rounded-lg p-3 text-sm text-red-700">
            {error}
          </div>
        )}
      </div>

      {/* Hasil Parsing */}
      {parseResults.length > 0 && (
        <div className="card space-y-4">
          <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
            Hasil Parsing ({parseResults.filter((r) => r.success).length} berhasil,
            {parseResults.filter((r) => !r.success).length} gagal)
          </h3>

          <div className="space-y-3 max-h-96 overflow-y-auto" role="region" aria-label="Hasil parsing" tabIndex={0}>
            {parseResults.map((result, i) => (
              <div key={i} className="border border-slate-200 rounded-lg p-4 bg-white">
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div className="flex items-center gap-2">
                    <span className={`badge ${result.success ? 'badge-passed' : 'badge-failed'}`}>
                      {result.success ? 'Berhasil' : 'Gagal'}
                    </span>
                    <span className="text-sm font-medium text-slate-900">{result.fileName}</span>
                    <span className="text-xs text-slate-500">{result.type?.toUpperCase()}</span>
                  </div>
                  <button
                    onClick={() => handleRemoveSample(i)}
                    className="text-xs text-red-700 hover:text-red-800 underline"
                    disabled={isParsing}
                  >
                    Hapus
                  </button>
                </div>

                {result.error && (
                  <p className="text-xs text-red-700">Error: {result.error}</p>
                )}

                {result.success && (
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs text-slate-600 mt-2">
                    {result.html && (
                      <div>
                        <span className="font-medium">HTML: </span>
                        {result.html.length} karakter
                      </div>
                    )}
                    {result.htmlUrl && (
                      <div>
                        <span className="font-medium">HTML URL: </span>
                        <span className="truncate block max-w-xs">{result.htmlUrl}</span>
                      </div>
                    )}
                    {result.entries && (
                      <div>
                        <span className="font-medium">HAR Entries: </span>
                        {result.entries.length}
                      </div>
                    )}
                    {result.apiCalls && (
                      <div>
                        <span className="font-medium">API Calls: </span>
                        {result.apiCalls.length}
                      </div>
                    )}
                    {result.htmlPages && (
                      <div>
                        <span className="font-medium">HTML Pages: </span>
                        {result.htmlPages.length}
                      </div>
                    )}
                    {result.mediaUrls && (
                      <div>
                        <span className="font-medium">Media URLs: </span>
                        {result.mediaUrls.length}
                      </div>
                    )}
                    {result.redirectChains && (
                      <div>
                        <span className="font-medium">Redirect Chains: </span>
                        {result.redirectChains.length}
                      </div>
                    )}
                    {result.resources && (
                      <div>
                        <span className="font-medium">Resources (MHTML): </span>
                        {result.resources.length}
                      </div>
                    )}
                    {result.errors && result.errors.length > 0 && (
                      <div className="col-span-2">
                        <span className="font-medium text-yellow-700">Peringatan: </span>
                        {result.errors.join('; ')}
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Sampel yang sudah tersimpan di project */}
      {hasSamples && parseResults.length === 0 && (
        <div className="card space-y-4">
          <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">
            Sampel Tersimpan ({project.samples.length})
          </h3>
          <div className="space-y-2">
            {project.samples.map((sample, i) => (
              <div key={i} className="border border-slate-200 rounded-lg p-3 bg-white flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="badge badge-passed">{sample.type?.toUpperCase()}</span>
                  <span className="text-sm font-medium text-slate-900">{sample.fileName}</span>
                  {sample.html && <span className="text-xs text-slate-500">({sample.html.length} chars)</span>}
                </div>
                <button
                  onClick={() => handleRemoveSample(i)}
                  className="text-xs text-red-700 hover:text-red-800 underline"
                  disabled={isParsing}
                >
                  Hapus
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {!hasSamples && parseResults.length === 0 && files.length === 0 && (
        <div className="card text-center py-12 border-slate-200">
          <p className="text-slate-500">Belum ada sampel diunggah.</p>
          <p className="text-sm text-slate-500 mt-2">Pilih file di atas untuk memulai.</p>
        </div>
      )}
    </div>
  )
}

UploadSamples.propTypes = {
  project: projectShape.isRequired,
  onProjectChange: PropTypes.func.isRequired,
}
