// mhtmlWorker.js — Web Worker untuk parsing MHTML/MHT (non-blokir UI thread).
// DOMParser tersedia di Web Worker (WindowOrWorkerGlobalScope) dan tidak mengeksekusi script.
// Tidak dapat mengirim Document via postMessage — hanya data hasil ekstraksi.

import { parseMhtml } from '../lib/parsers/mhtmlParser.js'

self.onmessage = async(event) => {
  const { id, content, contentType, baseUrl } = event.data || {}

  try {
    const result = parseMhtml(content, contentType, baseUrl)

    // postMessage hanya data hasil, bukan Document objek
    self.postMessage({
      id,
      success: true,
      html: result.html,
      htmlUrl: result.htmlUrl,
      resources: result.resources,
      errors: result.errors,
    })
  } catch (err) {
    self.postMessage({
      id,
      success: false,
      error: err.message || 'Parsing MHTML gagal',
      html: null,
      htmlUrl: null,
      resources: [],
      errors: [err.message],
    })
  }
}
