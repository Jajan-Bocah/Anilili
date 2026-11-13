// harWorker.js — Web Worker untuk analisis HAR (non-blokir UI thread).
// Tidak ada DOM parsing di sini — hanya manipulasi JSON string.

import { parseHar } from '../lib/parsers/harParser.js'

self.onmessage = async(event) => {
  const { id, content, baseUrl } = event.data || {}

  try {
    const result = parseHar(content, baseUrl)

    self.postMessage({
      id,
      success: true,
      entries: result.entries,
      apiCalls: result.apiCalls,
      htmlPages: result.htmlPages,
      mediaUrls: result.mediaUrls,
      redirectChains: result.redirectChains,
      errors: result.errors,
    })
  } catch (err) {
    self.postMessage({
      id,
      success: false,
      error: err.message || 'Parsing HAR gagal',
      entries: [],
      apiCalls: [],
      htmlPages: [],
      mediaUrls: [],
      redirectChains: [],
      errors: [err.message],
    })
  }
}
