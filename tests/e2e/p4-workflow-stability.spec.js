// Regresi P4: edit manual workflow tidak tertimpa inferensi; bertahan lewat navigasi, autosave/reload, ekspor/impor;
// regenerasi hanya lewat tindakan eksplisit + konfirmasi. Memakai sampel nyata Anichin (lihat tests/fixtures/real/MANIFEST.json).
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const fx = (n) => readFileSync(resolve('tests/fixtures/real/anichin', n))
const next = (page) => page.getByRole('button', { name: 'Berikutnya' })
const prev = (page) => page.getByRole('button', { name: 'Sebelumnya' })
const step = (page, n) => expect(page.locator(`text=Langkah ${n} dari 7`).first()).toBeVisible()
const MARK = ', a.zz-manual-marker'
const epSelector = (page) => page.locator('#param-episodes-2-selector')

async function toReview(page) {
  await page.goto('/')
  await page.fill('input[name="name"]', 'P4 Anichin')
  await page.fill('input[name="id"]', 'p4-anichin')
  await page.fill('input[name="endpoint"]', 'https://anichin.moe')
  await page.fill('input[name="configSourceUrl"]', 'https://configs.example.org/anichin/provider.json')
  await next(page).click()
  await page.locator('input[type="file"]').first().setInputFiles(
    ['search.htm', 'series.htm', 'watch.htm'].map((name) => ({ name, mimeType: 'text/html', buffer: fx(name) }))
  )
  await page.getByRole('button', { name: 'Parse Semua' }).click()
  await expect(page.locator('text=3 berhasil')).toBeVisible({ timeout: 30000 })
  await next(page).click()
  await expect(page.getByRole('button', { name: 'Analisis Ulang' })).toBeVisible({ timeout: 30000 })
  await next(page).click()
  await step(page, 4)
  await expect(page.getByTestId('origin-episodes')).toHaveText('otomatis', { timeout: 15000 })
}

// Buka kartu workflow episodes (Workflow Builder) dan edit selector step ke-3 (select episode-url) secara manual.
async function manualEditEpisodes(page) {
  await page.getByTestId('wf-card-episodes').getByRole('button', { name: 'Buka' }).click()
  const sel = epSelector(page)
  await expect(sel).toBeVisible()
  const original = await sel.inputValue()
  await sel.fill(original + MARK)
  await expect(page.getByTestId('origin-episodes')).toHaveText('diedit manual')
  return original
}

async function changeEpisodeRule(page) {
  // Ubah rule di Rule Editor → inferensi menghasilkan workflow episodes berbeda dari edit manual.
  const rule = page.locator('#selector-episode-url')
  await rule.fill((await rule.inputValue()) + ', a.zz-rule-change')
}

test('Edit manual tidak tertimpa inferensi (rule berubah, navigasi bolak-balik) dan regenerasi butuh konfirmasi', async ({ page }) => {
  await toReview(page)
  const original = await manualEditEpisodes(page)
  await expect(page.getByTestId('origin-search')).toHaveText('otomatis')

  await changeEpisodeRule(page)
  // Edit manual tetap; peringatan konflik muncul dan tidak menimpa otomatis.
  await expect(epSelector(page)).toHaveValue(original + MARK)
  await expect(page.getByTestId('conflict-episodes')).toBeVisible()
  await expect(page.getByTestId('origin-episodes')).toHaveText('diedit manual')

  // Navigasi: keluar-masuk langkah Tinjau Aturan tidak mereset edit manual.
  await prev(page).click(); await step(page, 3)
  await next(page).click(); await step(page, 4)
  await page.getByTestId('wf-card-episodes').getByRole('button', { name: 'Buka' }).click()
  await expect(epSelector(page)).toHaveValue(original + MARK)
  await expect(page.getByTestId('conflict-episodes')).toBeVisible()

  // Regenerasi eksplisit: peringatan dulu; Batal tidak mengubah apa pun.
  await page.getByRole('button', { name: 'Regenerasi episodes dari aturan' }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'Perubahan manual pada workflow episodes akan hilang' })).toBeVisible()
  await page.getByRole('button', { name: 'Batal' }).click()
  await expect(epSelector(page)).toHaveValue(original + MARK)
  await expect(page.getByTestId('origin-episodes')).toHaveText('diedit manual')

  // Konfirmasi → ditimpa dengan inferensi (mengikuti rule yang baru diubah), kembali "otomatis", konflik hilang.
  await page.getByRole('button', { name: 'Regenerasi episodes dari aturan' }).click()
  await page.getByRole('button', { name: 'Ya, timpa episodes' }).click()
  await expect(page.getByTestId('origin-episodes')).toHaveText('otomatis')
  await expect(page.getByTestId('conflict-episodes')).toHaveCount(0)
  const regenerated = await epSelector(page).inputValue()
  expect(regenerated).toContain('a.zz-rule-change')
  expect(regenerated).not.toContain('zz-manual-marker')
})

test('Edit manual bertahan: autosave+reload, ekspor/impor bundel, dan alur parser tetap nyata (2/166/1)', async ({ page }) => {
  await toReview(page)
  await manualEditEpisodes(page)
  await changeEpisodeRule(page)
  await expect(page.getByTestId('conflict-episodes')).toBeVisible()

  // Autosave (debounce) → IndexedDB menyimpan workflow + catatan origin manual.
  await expect.poll(() => page.evaluate(() => new Promise((resolveP, reject) => {
    const open = indexedDB.open('anilili-provider-generator')
    open.onerror = () => reject(open.error)
    open.onsuccess = () => {
      const req = open.result.transaction('projects').objectStore('projects').getAll()
      req.onsuccess = () => resolveP(req.result.some((p) => p.workflowOrigin?.episodes === 'manual' &&
        JSON.stringify(p.workflows?.episodes).includes('zz-manual-marker')))
      req.onerror = () => reject(req.error)
    }
  })), { timeout: 10000 }).toBe(true)

  // Reload → edit manual pulih dan tidak ditimpa inferensi saat Tinjau Aturan dibuka lagi.
  await page.reload()
  await expect(page.locator('input[name="name"]')).toHaveValue('P4 Anichin')
  for (let i = 2; i <= 4; i++) { await next(page).click(); await step(page, i) }
  await expect(page.getByTestId('origin-episodes')).toHaveText('diedit manual')
  await page.getByTestId('wf-card-episodes').getByRole('button', { name: 'Buka' }).click()
  await expect(epSelector(page)).toHaveValue(/zz-manual-marker/)
  await expect(page.getByTestId('origin-search')).toHaveText('otomatis')

  // Uji Offline: edit manual (selector tambahan tak cocok apa pun) tetap lolos parser engine dengan item nyata.
  await next(page).click(); await step(page, 5)
  await next(page).click(); await step(page, 6)
  await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
  await expect(page.getByText('parser engine: 166 item')).toBeVisible({ timeout: 20000 })
  await expect(page.getByText('parser engine: 2 item')).toBeVisible()
  await expect(page.getByText('parser engine: 1 item')).toBeVisible()

  // Ekspor: provider.json memuat edit manual; bundel memuat catatan origin.
  await next(page).click(); await step(page, 7)
  const dlJson = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Unduh provider.json' }).click()
  expect(readFileSync(await (await dlJson).path(), 'utf8')).toContain('zz-manual-marker')
  const dlBundle = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Unduh Bundel' }).click()
  const bundle = JSON.parse(readFileSync(await (await dlBundle).path(), 'utf8'))
  expect(bundle.project.workflowOrigin).toMatchObject({ episodes: 'manual', search: 'auto', sources: 'auto' })
  expect(JSON.stringify(bundle.project.workflows.episodes)).toContain('zz-manual-marker')
  // Status kompatibilitas: tanpa uji Android tidak pernah ANILILI_READY.
  const badges = await page.locator('span.rounded.text-xs.font-semibold').filter({ hasText: /^[A-Z_]{6,}$/ }).allInnerTexts()
  expect(badges.length).toBeGreaterThan(0) // jangkar positif: locator harus benar-benar menemukan badge status
  expect(badges).toContain('OFFLINE_PASSED')
  expect(badges).not.toContain('ANILILI_READY')

  // Impor bundel yang sama (sesi bersih): edit manual dan origin dipulihkan, inferensi tidak menimpanya.
  await page.evaluate(() => new Promise((res) => { const r = indexedDB.deleteDatabase('anilili-provider-generator'); r.onsuccess = r.onerror = r.onblocked = () => res() }))
  await page.goto('/')
  for (let i = 2; i <= 7; i++) { await next(page).click(); await step(page, i) }
  await page.locator('input[id="import-bundle"]').first().setInputFiles({ name: 'b.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(bundle)) })
  await step(page, 1)
  for (let i = 2; i <= 4; i++) { await next(page).click(); await step(page, i) }
  await expect(page.getByTestId('origin-episodes')).toHaveText('diedit manual')
  await page.getByTestId('wf-card-episodes').getByRole('button', { name: 'Buka' }).click()
  await expect(epSelector(page)).toHaveValue(/zz-manual-marker/)
  await expect(page.getByTestId('origin-search')).toHaveText('otomatis')
})

test('Workflow hasil impor provider.json (tanpa catatan origin) dianggap manual dan tidak dihapus oleh Tinjau Aturan', async ({ page }) => {
  await page.goto('/')
  const providerJson = {
    schemaVersion: 1,
    providers: [{ id: 'imp', name: 'Imp', endpoint: 'https://imp.example.com', source: 'generic_html', category: 'general', languages: [], subtitleVariants: [], priority: 0, enabled: true, capabilities: {} }],
    workflows: {
      search: {
        name: 'search', version: '1.0', description: 'manual',
        steps: [
          { op: 'http', id: 'h', params: { method: 'GET', url: 'https://imp.example.com/?s={{query}}' }, saveAs: 'r' },
          { op: 'parse_html', id: 'p', params: { input: '{{r.body}}' }, saveAs: 'd' },
          { op: 'select', id: 's', params: { input: 'd', selector: 'h2 a', mode: 'ALL' }, saveAs: 't' },
        ],
        output: { type: 'SEARCH', mapping: { titles: 't', urls: 't' } },
      },
    },
  }
  for (let i = 2; i <= 7; i++) { await next(page).click(); await step(page, i) }
  await page.locator('input[id="import-bundle"]').first().setInputFiles({ name: 'provider.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(providerJson)) })
  await step(page, 1)
  for (let i = 2; i <= 4; i++) { await next(page).click(); await step(page, i) }
  await expect(page.getByTestId('origin-search')).toHaveText('diedit manual')
  await page.getByTestId('wf-card-search').getByRole('button', { name: 'Buka' }).click()
  await expect(page.locator('#param-search-2-selector')).toHaveValue('h2 a')
})
