// Paritas offlineTester ↔ WorkflowExecutor.kt (ref SHA di engineContract.ENGINE_REFERENCE_COMMIT).
// Tiap kasus mengutip perilaku Kotlin yang ditiru. Bukan eksekusi JVM.
import { describe, it, expect } from 'vitest'
import { executeStep, executeWorkflow, STATUS, evaluateTemplate } from '../../src/lib/tester/offlineTester.js'

const HTML = `<html><body>
  <ul><li class="a"><a href="/x/1"> Satu  </a></li><li class="a"><a href="/x/2">Dua</a></li></ul>
  <script>var hidden = 1</script><div id="t">Halo <b>dunia</b></div></body></html>`

const run = (op, params, vars = {}) => {
  const ctx = new Map(Object.entries(vars))
  const r = executeStep({ op, params, saveAs: 'out' }, ctx, null)
  return { ...r, ctx }
}
const wf = (steps, output = { type: 'RAW', mapping: {} }) => ({ name: 'w', steps, output })

describe('resolusi input (resolveInputString/Raw)', () => {
  it('tidak ada chaining implisit: step tanpa input gagal walau step sebelumnya punya output', () => {
    const r = executeWorkflow(
      wf([
        { op: 'http', params: { url: 'https://x.test/' }, saveAs: 'resp' },
        { op: 'select', params: { selector: 'li', mode: 'ALL' }, saveAs: 'x' },
      ]),
      null,
      '',
      { fixtureHtml: HTML }
    )
    expect(r.overallStatus).toBe(STATUS.FAILED)
    expect(r.stepResults[1].error).toMatch(/input variable '' not found/)
  })

  it('HttpResponse sebagai input dipakai body-nya; template {{resp.body}} juga', () => {
    const r = executeWorkflow(
      wf([
        { op: 'http', params: { url: 'https://x.test/' }, saveAs: 'resp' },
        { op: 'select', params: { input: 'resp', selector: 'li a', mode: 'ALL' }, saveAs: 'a' },
        { op: 'select', params: { input: '{{resp.body}}', selector: 'li a', mode: 'ALL' }, saveAs: 'b' },
      ]),
      null,
      '',
      { fixtureHtml: HTML }
    )
    expect(r.variables.a).toEqual(['Satu', 'Dua'])
    expect(r.variables.b).toEqual(['Satu', 'Dua'])
  })

  it('params selain http/url_resolve TIDAK dievaluasi template; {{a[0]}} tidak diproses', () => {
    const r = run('select', { input: 'h', selector: '{{sel}}', mode: 'ALL' }, { h: HTML, sel: 'li' })
    expect(r.status).toBe(STATUS.FAILED) // selector literal "{{sel}}" tidak valid
    expect(evaluateTemplate('{{a[0]}}', new Map([['a', ['x']]]))).toBe('{{a[0]}}')
    expect(evaluateTemplate('{{a.0}} {{missing}}!', new Map([['a', ['x']]]))).toBe('x !')
  })
})

describe('select (Jsoup)', () => {
  it('mode default FIRST (bukan ALL); teks dinormalkan tanpa script', () => {
    const r = run('select', { input: 'h', selector: 'li a' }, { h: HTML })
    expect(r.output).toBe('Satu')
    expect(run('select', { input: 'h', selector: '#t', mode: 'TEXT' }, { h: HTML }).output).toBe('Halo dunia')
  })
  it('atribut hilang → "" (bukan null); ALL tanpa elemen → [] tersimpan, NOT_DETECTED', () => {
    expect(run('select', { input: 'h', selector: 'li', mode: 'ALL', attribute: 'data-x' }, { h: HTML }).output).toEqual(['', ''])
    const e = run('select', { input: 'h', selector: '.tidak-ada', mode: 'ALL' }, { h: HTML })
    expect(e.status).toBe(STATUS.NOT_DETECTED)
    expect(e.ctx.get('out')).toEqual([])
  })
  it('FIRST tanpa kecocokan: success(null) → tidak disimpan, workflow lanjut', () => {
    const r = run('select', { input: 'h', selector: '.tidak-ada', mode: 'FIRST' }, { h: HTML })
    expect(r.status).toBe(STATUS.NOT_DETECTED)
    expect(r.ctx.has('out')).toBe(false)
  })
  it('mode huruf kecil diterima (valueOf(uppercase())); selector Jsoup-only → NOT_TESTED', () => {
    expect(run('select', { input: 'h', selector: 'li a', mode: 'all' }, { h: HTML }).output).toHaveLength(2)
    expect(run('select', { input: 'h', selector: 'a:contains(Dua)', mode: 'ALL' }, { h: HTML }).status).toBe(STATUS.NOT_TESTED)
  })
})

describe('parse_json (extractByPath)', () => {
  const J = '{"items":[{"t":"A"},{"t":"B"}],"n":5,"o":{"k":1}}'
  it('segmen [N] hanya sebagai segmen utuh: items.[1].t', () => {
    expect(run('parse_json', { input: 'j', jsonPath: 'items.[1].t' }, { j: J }).output).toBe('B')
  })
  it('"items[1]" tidak didukung → path tidak ketemu → engine mengembalikan SELURUH JSON', () => {
    const r = run('parse_json', { input: 'j', jsonPath: 'items[1].t' }, { j: J })
    expect(r.status).toBe(STATUS.PARTIAL)
    expect(r.output.v.n).toBe(5)
  })
  it('primitif → string, array → list string, objek → JSON string', () => {
    expect(run('parse_json', { input: 'j', jsonPath: 'n' }, { j: J }).output).toBe('5')
    expect(run('parse_json', { input: 'j', jsonPath: 'o' }, { j: J }).output).toBe('{"k":1}')
    expect(run('parse_json', { input: 'j', jsonPath: 'items' }, { j: J }).output).toEqual(['{"t":"A"}', '{"t":"B"}'])
  })
  it('JSON rusak → FAILED', () => {
    expect(run('parse_json', { input: 'j' }, { j: '{x' }).status).toBe(STATUS.FAILED)
  })
})

describe('regex', () => {
  it('ALL memakai flag g (semua kecocokan) dan group', () => {
    expect(run('regex', { input: 's', pattern: '(\\d+)-x', group: '1', mode: 'ALL' }, { s: '1-x 22-x' }).output).toEqual(['1', '22'])
  })
  it('FIRST tanpa kecocokan → null tidak disimpan; REPLACE ganti semua ke ""', () => {
    expect(run('regex', { input: 's', pattern: 'zzz' }, { s: 'abc' }).ctx.has('out')).toBe(false)
    expect(run('regex', { input: 's', pattern: 'a', mode: 'REPLACE' }, { s: 'banana' }).output).toBe('bnn')
  })
  it('flag inline Java (?i) didukung; pola tak dikompilasi JS → NOT_TESTED, bukan FAILED', () => {
    expect(run('regex', { input: 's', pattern: '(?i)ABC' }, { s: 'xabc' }).output).toBe('abc')
    expect(run('regex', { input: 's', pattern: 'a++' }, { s: 'aaa' }).status).toBe(STATUS.NOT_TESTED)
  })
})

describe('normalize', () => {
  const ops = (o, v) => run('normalize', { input: 'v', operations: JSON.stringify(o) }, { v })
  it('urutan operasi & url_resolve dengan baseUrl bertemplat', () => {
    expect(ops([{ type: 'trim' }, { type: 'url_resolve', baseUrl: '{{base}}' }], ' /a/b ').output).toBe('/a/b')
    const r = run('normalize', { input: 'v', operations: [{ type: 'url_resolve', baseUrl: '{{base}}' }] }, { v: '/a/b', base: 'https://x.test/p/' })
    expect(r.output).toBe('https://x.test/a/b')
  })
  it('episode_number: "Ep. 5" → "5" tetapi "Episode 5" → "isode 5" (bug urutan regex engine)', () => {
    expect(ops([{ type: 'episode_number' }], 'Ep. 5').output).toBe('5')
    expect(ops([{ type: 'episode_number' }], 'Episode 5').output).toBe('isode 5')
  })
  it('language_normalize hanya en/ja/id; selebihnya lowercase+trim', () => {
    expect(ops([{ type: 'language_normalize' }], 'English').output).toBe('eng')
    expect(ops([{ type: 'language_normalize' }], ' Indonesia ').output).toBe('ind')
    expect(ops([{ type: 'language_normalize' }], 'Korean').output).toBe('korean')
  })
  it('default hanya jika blank; base64_decode gagal → string asli; prefix "base64," dibuang', () => {
    expect(ops([{ type: 'default', value: 'zz' }], '  ').output).toBe('zz')
    expect(ops([{ type: 'base64_decode' }], 'aGFsbw==').output).toBe('halo')
    expect(ops([{ type: 'base64_decode' }], 'data:x;base64,aGFsbw==').output).toBe('halo')
    expect(ops([{ type: 'base64_decode' }], '%%%').output).toBe('%%%')
  })
  it('join langsung return: operasi sesudahnya diabaikan; list tidak di-base64-decode per item', () => {
    const r = run('normalize', { input: 'l', operations: [{ type: 'join', separator: '|' }, { type: 'uppercase' }] }, { l: ['a', 'b'] })
    expect(r.output).toBe('a|b')
    const b = run('normalize', { input: 'l', operations: [{ type: 'base64_decode' }] }, { l: ['aGFsbw=='] })
    expect(b.output).toBe('[aGFsbw==]') // value = List.toString() → blob, bukan per item
  })
})

describe('assert & filter', () => {
  const as = (condition, v) => run('assert', { input: 'v', condition: JSON.stringify(condition), message: 'gagal!' }, { v })
  it('matches = full match; size = panjang string (bukan jumlah item)', () => {
    expect(as({ type: 'matches', pattern: '\\d+' }, '12').status).toBe(STATUS.PASSED)
    expect(as({ type: 'matches', pattern: '\\d+' }, '12a').status).toBe(STATUS.FAILED)
    expect(as({ type: 'size', min: 3 }, ['a', 'b', 'c', 'd']).status).toBe(STATUS.PASSED) // "[a, b, c, d]".length
    expect(as({ type: 'size', min: 5 }, 'abc').status).toBe(STATUS.FAILED)
  })
  it('not_null = tidak kosong; not_empty = tidak blank; gagal membawa pesan step', () => {
    expect(as({ type: 'not_null' }, ' ').status).toBe(STATUS.PASSED)
    const r = as({ type: 'not_empty' }, ' ')
    expect(r.status).toBe(STATUS.FAILED)
    expect(r.error).toBe('gagal!')
  })
  it('filter: matches full match; input bukan list → FAILED', () => {
    const f = (predicate, l) => run('filter', { input: 'l', predicate: JSON.stringify(predicate) }, { l })
    expect(f({ type: 'matches', pattern: 'ab' }, ['ab', 'abc']).output).toEqual(['ab'])
    expect(f({ type: 'contains', value: 'B' }, ['ab', 'x']).output).toEqual(['ab'])
    expect(f({ type: 'not_empty' }, 'teks').status).toBe(STATUS.FAILED)
    expect(f({ type: 'contains', value: 'q' }, ['a']).status).toBe(STATUS.PARTIAL)
  })
})

describe('map', () => {
  it('mappings = nama sumber → nama baru; kunci lain dipertahankan', () => {
    const r = run('map', { input: 'j', mappings: JSON.stringify({ t: 'title' }) }, { j: '[{"t":"A","u":1}]' })
    expect(r.output.v).toEqual([{ title: 'A', u: 1 }])
  })
  it('sumber tidak ada → FAILED', () => {
    expect(run('map', { input: 'nope', mappings: '{}' }).status).toBe(STATUS.FAILED)
  })
})

describe('http (replay) & workflow', () => {
  it('http me-replay fixture sebagai HttpResponse(200) dan ditandai replayed; tanpa fixture → NOT_TESTED & berhenti', () => {
    const r = executeWorkflow(wf([{ op: 'http', params: { url: 'https://x.test/{{query}}' }, saveAs: 'resp' }]), null, '', { fixtureHtml: HTML, variables: { query: 'naruto' } })
    expect(r.replayed).toBe(true)
    expect(r.stepResults[0].evidence[0]).toMatch(/x\.test\/naruto.*REPLAY/)
    expect(r.variables.resp.statusCode).toBe(200)
    const n = executeWorkflow(wf([{ op: 'http', params: { url: 'https://x.test/' }, saveAs: 'resp' }, { op: 'select', params: { input: 'resp', selector: 'a' } }]), null)
    expect(n.overallStatus).toBe(STATUS.NOT_TESTED)
    expect(n.stepResults).toHaveLength(1)
  })
  it('step gagal menghentikan workflow; output non-RAW hanya memetakan nama→variabel', () => {
    const r = executeWorkflow(
      wf(
        [
          { op: 'http', params: { url: 'https://x.test/' }, saveAs: 'resp' },
          { op: 'select', params: { input: 'resp', selector: 'li a', mode: 'ALL' }, saveAs: 'titles' },
          { op: 'select', params: { input: 'tidak-ada', selector: 'a' }, saveAs: 'zzz' },
          { op: 'select', params: { input: 'resp', selector: 'a' }, saveAs: 'tak-tercapai' },
        ],
        { type: 'SEARCH', mapping: { items: 'titles', ghost: 'tidak-ada' } }
      ),
      null,
      '',
      { fixtureHtml: HTML }
    )
    expect(r.overallStatus).toBe(STATUS.FAILED)
    expect(r.stepResults).toHaveLength(3)
    expect(r.finalOutput).toEqual({ items: ['Satu', 'Dua'], ghost: null })
  })
  it('op tidak dikenal → BLOCKED', () => {
    expect(run('bogus', {}).status).toBe(STATUS.BLOCKED)
  })
})
