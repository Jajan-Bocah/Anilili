// Representasi nilai variabel workflow (cerminan tipe Kotlin) — dipakai offlineTester & runtimeParsers.
/** kotlinx JsonElement hasil parse_json tanpa jsonPath. */
export class JsonEl {
  constructor(v) {
    this.v = v
  }
}
/** HttpResponse(statusCode, headers, body). */
export class HttpResponse {
  constructor(statusCode, headers, body) {
    this.statusCode = statusCode
    this.headers = headers
    this.body = body
  }
}
/** org.jsoup Document hasil parse_html. */
export class ParsedDocument {
  constructor(html) {
    this.html = html
  }
}

export const isPrim = (v) => v === null || typeof v !== 'object'
export const primContent = (v) => (v === null ? 'null' : String(v))
export const jsonToString = (v) => (isPrim(v) ? primContent(v) : JSON.stringify(v))

/** Padanan `Any.toString()` Kotlin untuk nilai variabel. */
export function kString(v) {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return v
  if (v instanceof JsonEl) return jsonToString(v.v)
  if (v instanceof HttpResponse) return v.body
  if (v instanceof ParsedDocument) return v.html
  if (Array.isArray(v)) return `[${v.map(kString).join(', ')}]`
  if (typeof v === 'object') {
    return `{${Object.entries(v).map(([k, x]) => `${k}=${kString(x)}`).join(', ')}}`
  }
  return String(v)
}

