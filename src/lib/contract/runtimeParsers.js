// runtimeParsers.js — port parseSearchResults / parseEpisodesResult / parseSourcesResult / parseServers
// dari WorkflowRemoteProviderRuntime.kt (ref SHA di engineContract.ENGINE_REFERENCE_COMMIT).
// Gerbang "engine-processable": output workflow baru dianggap dapat diproses engine bila parser ini
// menghasilkan ≥1 item. Ini BUKAN bukti playback — itu butuh uji Android.
//
// Input = `rawOutput` dari executeWorkflow (tipe internal): Array = Kotlin List, objek polos = Map,
// JsonEl = JsonElement. Perbedaan List vs JsonArray PENTING: `as? List<*>` tidak cocok dengan JsonArray.

import { JsonEl, isPrim, primContent } from './workflowValues.js'

const isList = (v) => Array.isArray(v)
const isMap = (v) => v !== null && typeof v === 'object' && !Array.isArray(v) && !(v instanceof JsonEl)
const get = (m, k) => (m && Object.prototype.hasOwnProperty.call(m, k) ? m[k] : null)
/** a ?: b ?: c — elvis Kotlin (null/absen → lanjut). */
const firstNonNull = (...vs) => vs.find((v) => v !== null && v !== undefined) ?? null
const asList = (v) => (isList(v) ? v : null)
const asStr = (v) => (typeof v === 'string' ? v : null)
const str = (v) => (v === null || v === undefined ? null : typeof v === 'string' ? v : String(v))
/** JsonElement → konten string seperti `if (it is JsonPrimitive) it.content else it.toString()`. */
const jsonStr = (e) => (e === null || e === undefined ? null : isPrim(e) ? primContent(e) : JSON.stringify(e))
const jsonObjGet = (o, ...keys) => {
  for (const k of keys) {
    const v = get(o, k)
    if (v !== null) return v
  }
  return null
}
const jsonList = (el) => (el instanceof JsonEl && isList(el.v) ? el.v : null)

/** @returns {{items: Array, warnings: string[]}} */
export function parseSearchResults(output) {
  const warnings = []
  if (output === null || output === undefined) return { items: [], warnings }
  let list = null
  if (isList(output)) list = output
  else if (isMap(output)) {
    const std = firstNonNull(asList(get(output, 'results')), asList(get(output, 'animes')), asList(get(output, 'data')))
    if (std) list = std
    else if ('titles' in output || 'urls' in output) {
      const titles = asList(get(output, 'titles'))
      const urls = asList(get(output, 'urls'))
      const images = asList(get(output, 'images'))
      const size = Math.max(0, ...[titles, urls, images].filter(Boolean).map((l) => l.length))
      list = Array.from({ length: size }, (_, i) => ({
        title: asStr(titles?.[i]) ?? '',
        slug: asStr(urls?.[i]) ?? '',
        image: asStr(images?.[i]) ?? '',
      }))
    } else {
      list = asList(Object.values(output)[0] ?? null)
    }
  } else if (output instanceof JsonEl) {
    if (isMap(output.v)) list = firstNonNull(asList(output.v.results), asList(output.v.animes), asList(output.v.data))
    else if (isList(output.v)) list = output.v
  }
  if (!list) return { items: [], warnings }

  const items = []
  for (const item of list) {
    let it = item
    if (item instanceof JsonEl) it = item.v
    if (!isMap(it)) continue
    const isJson = item instanceof JsonEl
    const t = isJson ? jsonStr(firstNonNull(get(it, 'title'), get(it, 'name'))) : str(firstNonNull(get(it, 'title'), get(it, 'name')))
    if (t === null) continue
    const slug = (isJson ? jsonStr : str)(jsonObjGet(it, 'slug', 'url', 'link', 'id')) ?? ''
    const image = (isJson ? jsonStr : str)(jsonObjGet(it, 'image', 'poster', ...(isJson ? [] : ['thumbnail']), 'image_portrait_url'))
    items.push({ title: t.trim(), slugOrUrl: slug, image })
  }
  if (items.some((i) => i.title === '')) warnings.push('Ada item dengan title kosong (engine tetap menerimanya)')
  if (items.some((i) => i.slugOrUrl === '')) warnings.push('Ada item dengan slug/url kosong — episodes tidak bisa dipanggil untuk item itu')
  return { items, warnings }
}

/** @param {{providerId?: string, anilistId?: number}} [ctx] */
export function parseEpisodesResult(output, ctx = {}) {
  const warnings = []
  const { providerId = 'provider', anilistId = 0 } = ctx
  if (output === null || output === undefined) return { items: [], warnings }

  let parallel = null
  if (isMap(output)) {
    const urls = firstNonNull(asList(get(output, 'episodeUrls')), asList(get(output, 'episode_urls')), asList(get(output, 'urls')))
    if (urls) {
      const numbers = firstNonNull(asList(get(output, 'episodeNumbers')), asList(get(output, 'episode_numbers')), asList(get(output, 'numbers')))
      const titles = firstNonNull(asList(get(output, 'episodeTitles')), asList(get(output, 'episode_titles')), asList(get(output, 'titles')))
      const size = Math.max(urls.length, numbers?.length ?? 0, titles?.length ?? 0)
      parallel = Array.from({ length: size }, (_, i) => ({
        url: asStr(urls[i]) ?? '',
        number: asStr(numbers?.[i]) ?? String(i + 1),
        title: asStr(titles?.[i]),
      }))
    }
  }
  let list = null
  if (isList(output)) list = output
  else if (isMap(output)) list = asList(firstNonNull(get(output, 'episodes'), get(output, 'data')))
  else if (output instanceof JsonEl) {
    if (isMap(output.v)) list = asList(firstNonNull(output.v.episodes, output.v.data))
    else if (isList(output.v)) list = output.v
  }
  const src = parallel ?? list
  if (!src) return { items: [], warnings }

  const items = []
  for (const raw of src) {
    const isJson = raw instanceof JsonEl
    const it = isJson ? raw.v : raw
    if (!isMap(it)) continue
    const conv = isJson ? jsonStr : str
    const numRaw = conv(firstNonNull(get(it, 'number'), get(it, 'episode'), get(it, 'ep'), ...(isJson ? [] : [get(it, 'num')])))
    const num = numRaw === null ? NaN : parseFloat(numRaw.replace(/[^0-9.]/g, ''))
    if (Number.isNaN(num)) continue
    const url = conv(jsonObjGet(it, 'url', ...(isJson ? ['slug'] : ['link', 'slug', 'id']))) ?? String(num)
    items.push({
      pipeId: `remote/${providerId}/${anilistId}/sub/${url}`,
      number: num,
      title: conv(get(it, 'title'))?.trim() ?? null,
      url,
    })
  }
  items.sort((a, b) => a.number - b.number)
  if (items.length && items.every((i) => i.url === String(i.number))) {
    warnings.push('Semua episode memakai nomor sebagai url (tidak ada url episode terbaca)')
  }
  return { items, warnings }
}

/** @param {string} [category] 'sub'|'dub'|… (hanya label) */
export function parseSourcesResult(output, { endpoint = '' } = {}) {
  const warnings = []
  if (output === null || output === undefined) return { items: [], warnings }
  const isMirrorDropdown = isMap(output) && 'streams' in output && 'servers' in output
  let streamsRaw
  if (isMap(output)) streamsRaw = firstNonNull(get(output, 'streams'), get(output, 'sources'), get(output, 'video'))
  else if (output instanceof JsonEl && isMap(output.v)) streamsRaw = firstNonNull(get(output.v, 'streams'), get(output.v, 'sources'))
  else streamsRaw = output

  let rawList = null
  if (isList(streamsRaw)) rawList = streamsRaw
  else if (jsonList(streamsRaw)) rawList = streamsRaw.v
  else if (isMap(streamsRaw)) rawList = [streamsRaw]
  else if (streamsRaw instanceof JsonEl && isMap(streamsRaw.v)) rawList = [streamsRaw.v]
  else if (typeof streamsRaw === 'string') warnings.push('output.streams berupa String tunggal — engine hanya menerima List/Map, hasilnya kosong (gunakan select ALL)')
  if (!rawList) return { items: [], warnings }

  const items = []
  for (const raw of rawList) {
    const item = raw instanceof JsonEl ? raw.v : raw
    if (typeof item === 'string') {
      if (item.trim() === '') continue
      const isIframe = isMirrorDropdown || item.includes('embed')
      items.push({ url: item, type: item.includes('.m3u8') ? 'hls' : isIframe ? 'embed' : 'mp4', referer: endpoint, quality: null })
    } else if (isMap(item)) {
      const url = str(firstNonNull(get(item, 'url'), get(item, 'file'), get(item, 'src')))
      if (url === null) continue
      const typeRaw = (str(get(item, 'type')) ?? '').toLowerCase()
      const iframe = typeRaw === 'embed' || url.includes('embed') || get(item, 'isEmbed') === true
      items.push({
        url,
        type: url.includes('.m3u8') || typeRaw === 'hls' ? 'hls' : iframe ? 'embed' : 'mp4',
        referer: str(get(item, 'referer')) ?? endpoint,
        quality: str(firstNonNull(get(item, 'quality'), get(item, 'label'))),
      })
    }
  }
  const notUrl = items.filter((i) => !/^(https?:)?\/\//i.test(i.url))
  if (notUrl.length) {
    warnings.push(`${notUrl.length} dari ${items.length} stream bukan URL (mis. blob base64 dari option.value — engine TIDAK men-decode per item)`)
  }
  return { items, warnings, urlLike: items.length - notUrl.length }
}

export function parseServers(output) {
  const warnings = []
  if (output === null || output === undefined) return { items: [], warnings }
  let list = null
  if (isList(output)) list = output
  else if (isMap(output)) {
    list = asList(firstNonNull(get(output, 'servers'), get(output, 'mirrors'), Object.values(output)[0] ?? null))
  } else if (output instanceof JsonEl && isList(output.v)) list = output.v
  if (!list) return { items: [], warnings }
  const items = []
  let strings = 0
  for (const it of list) {
    if (typeof it === 'string') strings++
    if (!isMap(it)) continue
    const name = str(firstNonNull(get(it, 'name'), get(it, 'title'), get(it, 'server')))
    if (name === null) continue
    items.push({ name, url: str(firstNonNull(get(it, 'url'), get(it, 'link'), get(it, 'embed'))) ?? '', quality: str(get(it, 'quality')) })
  }
  if (!items.length && strings) warnings.push('servers berisi String — engine hanya menerima Map {name/title/server, url}; hasilnya kosong')
  return { items, warnings }
}

/** workflow name → parser (kegagalan ≥1 item = tidak dapat diproses engine). */
export const RUNTIME_PARSERS = {
  search: parseSearchResults,
  episodes: parseEpisodesResult,
  sources: parseSourcesResult,
  servers: parseServers,
}
