// htmlWorker.js — Web Worker untuk parsing HTML biasa (non-blokir UI thread).
// Menggunakan DOMParser untuk ekstraksi HTML murni.

// Polyfill DOMParser for Web Worker if not available
const DOMParserPolyfill = self.DOMParser || class DOMParserPolyfill {
  parseFromString(content) {
    // Simple implementation for HTML parsing in worker
    const doc = {
      documentElement: { outerHTML: content },
      querySelector: () => null,
      querySelectorAll: () => []
    }
    return doc
  }
}

self.onmessage = async(event) => {
  const { id, content, baseUrl } = event.data || {}

  try {
    // Validate input
    if (!content || typeof content !== 'string') {
      throw new Error('Konten HTML tidak valid atau kosong')
    }

    const parser = new DOMParserPolyfill()
    const doc = parser.parseFromString(content, 'text/html')
    // Cek apakah parsing gagal
    const parseError = doc.querySelector('parsererror')
    if (parseError) {
      throw new Error('HTML tidak valid: ' + parseError.textContent)
    }

    // Ekstrak HTML bersih (tanpa script, style, dll)
    const html = doc.documentElement.outerHTML

    // Cari link dan resource
    const links = Array.from(doc.querySelectorAll('a[href]')).map(a => ({
      url: new URL(a.href, baseUrl).href,
      text: a.textContent.trim(),
    }))

    const scripts = Array.from(doc.querySelectorAll('script[src]')).map(s =>
      new URL(s.src, baseUrl).href
    )

    const styles = Array.from(doc.querySelectorAll('link[rel="stylesheet"][href]')).map(l =>
      new URL(l.href, baseUrl).href
    )

    const images = Array.from(doc.querySelectorAll('img[src]')).map(i =>
      new URL(i.src, baseUrl).href
    )

    const result = {
      id,
      success: true,
      type: 'html',
      html,
      htmlUrl: baseUrl,
      resources: [
        ...links.map(l => ({ url: l.url, type: 'link', text: l.text })),
        ...scripts.map(u => ({ url: u, type: 'script' })),
        ...styles.map(u => ({ url: u, type: 'style' })),
        ...images.map(u => ({ url: u, type: 'image' })),
      ],
      entries: [],
      apiCalls: [],
      htmlPages: [{ url: baseUrl, status: 200, mimeType: 'text/html' }],
      mediaUrls: [],
      redirectChains: [],
      errors: [],
    }
    self.postMessage(result)
  } catch (err) {
    self.postMessage({
      id,
      success: false,
      error: err.message || 'Parsing HTML gagal',
      type: 'html',
      html: null,
      htmlUrl: null,
      resources: [],
      entries: [],
      apiCalls: [],
      htmlPages: [],
      mediaUrls: [],
      redirectChains: [],
      errors: [err.message],
    })
  }
}
