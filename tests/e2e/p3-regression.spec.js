// Regresi P3 dengan sampel nyata Anichin (tests/fixtures/real/MANIFEST.json untuk provenance):
// pemetaan sampel per workflow memengaruhi replay, bertahan saat reload & bundel, serta migrasi proyek lama.
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const fx = (n) => readFileSync(resolve('tests/fixtures/real/anichin', n))
const next = (page) => page.getByRole('button', { name: 'Berikutnya' })
const step = (page, n) => expect(page.locator(`text=Langkah ${n} dari 7`).first()).toBeVisible()
async function importBundle(page, name, bundle) {
  if (!(await page.locator('text=Langkah 7 dari 7').first().isVisible())) for (let i = 2; i <= 7; i++) { await next(page).click(); await step(page, i) }
  await page.locator('input[id="import-bundle"]').first().setInputFiles({ name, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(bundle)) })
  await step(page, 1)
}
const cat = (page, key, file) => page.getByTestId(`sample-cat-${key}`).getByRole('checkbox', { name: new RegExp(`: ${file.replace('.', '\\.')}$`) })

async function toTestCenter(page) {
  await page.goto('/')
  await page.fill('input[name="name"]', 'P3 Anichin')
  await page.fill('input[name="id"]', 'p3-anichin')
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
  await next(page).click() // Generate
  await step(page, 5)
  await expect(page.locator('.badge-passed:has-text("VALID")')).toBeVisible({ timeout: 15000 })
  await next(page).click() // Test Center
  await step(page, 6)
}

test('Pemetaan sampel otomatis per kategori, manual mengubah replay, tanpa sampel → NOT_TESTED', async ({ page }) => {
  await toTestCenter(page)
  // Otomatis: tiap kategori memilih berkas yang bukti rule-nya cocok.
  await expect(cat(page, 'search', 'search.htm')).toBeChecked()
  await expect(cat(page, 'episodes', 'series.htm')).toBeChecked()
  await expect(cat(page, 'sources', 'watch.htm')).toBeChecked()
  await expect(cat(page, 'search', 'series.htm')).not.toBeChecked()

  await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
  await expect(page.locator('text=Hasil per Workflow Step')).toBeVisible({ timeout: 20000 })
  await expect(page.getByText('parser engine: 2 item')).toBeVisible()
  await expect(page.getByText('parser engine: 166 item')).toBeVisible()

  // Manual: search dipindah ke halaman series → replay search berubah (bukan 2 item lagi), episodes tetap 166.
  await cat(page, 'search', 'search.htm').uncheck()
  await cat(page, 'search', 'series.htm').check()
  await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
  await expect(page.getByText('sampel: series.htm').first()).toBeVisible({ timeout: 20000 })
  await expect(page.getByText('parser engine: 2 item')).toHaveCount(0)
  await expect(page.getByText('parser engine: 166 item')).toBeVisible()

  // Tanpa sampel: workflow NOT_TESTED, tidak jatuh ke HTML lain.
  await cat(page, 'search', 'series.htm').uncheck()
  await expect(page.getByText('Belum ada sampel → workflow search NOT_TESTED.')).toBeVisible()
  await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
  await expect(page.getByText('parser engine: 166 item')).toBeVisible({ timeout: 20000 })
  await expect(page.getByText('parser engine: 2 item')).toHaveCount(0)

  // Pilih ulang otomatis mengembalikan pemetaan berbasis bukti.
  await page.getByRole('button', { name: 'Pilih ulang otomatis' }).click()
  await expect(cat(page, 'search', 'search.htm')).toBeChecked()
})

test('Pemetaan sampel bertahan: autosave+reload dan bundel ekspor/impor menjaga konfigurasi', async ({ page }) => {
  await toTestCenter(page)
  await cat(page, 'search', 'search.htm').uncheck()
  await cat(page, 'homepage', 'search.htm').check()

  // Autosave (debounce) ke IndexedDB — tunggu berbasis state.
  await expect.poll(() => page.evaluate(() => new Promise((resolveP, reject) => {
    const open = indexedDB.open('anilili-provider-generator')
    open.onerror = () => reject(open.error)
    open.onsuccess = () => {
      const req = open.result.transaction('projects').objectStore('projects').getAll()
      req.onsuccess = () => resolveP(req.result.some((p) => p.sampleMap?.homepage?.includes('search.htm') && p.sampleMap.search.length === 0))
      req.onerror = () => reject(req.error)
    }
  })), { timeout: 10000 }).toBe(true)

  // Bundel ekspor: pemetaan + config source URL + endpoint terpisah ikut tersimpan.
  await next(page).click()
  await step(page, 7)
  const dl = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Unduh Bundel' }).click()
  const bundle = JSON.parse(readFileSync(await (await dl).path(), 'utf8'))
  expect(bundle.project.sampleMap).toMatchObject({ search: [], episodes: ['series.htm'], sources: ['watch.htm'], homepage: ['search.htm'] })
  expect(bundle.project.endpoint).toBe('https://anichin.moe')
  expect(bundle.project.configSourceUrl).toBe('https://configs.example.org/anichin/provider.json')
  expect(bundle.project.samples.every((s) => !('html' in s))).toBe(true)

  // Reload: pemetaan dipulihkan dari autosave IndexedDB (tanpa impor) dan replay tetap jalan.
  await page.reload()
  await expect(page.locator('input[name="name"]')).toHaveValue('P3 Anichin')
  for (let i = 2; i <= 6; i++) { await next(page).click(); await step(page, i) }
  await expect(cat(page, 'search', 'search.htm')).not.toBeChecked()
  await expect(cat(page, 'homepage', 'search.htm')).toBeChecked()
  await expect(cat(page, 'episodes', 'series.htm')).toBeChecked()
  await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
  await expect(page.getByText('parser engine: 166 item')).toBeVisible({ timeout: 20000 })
  await next(page).click()
  await step(page, 7)

  // Impor bundel yang sama ke sesi baru: pemetaan dan kedua URL dipulihkan.
  await importBundle(page, 'b.json', bundle)
  await expect(page.locator('input[name="endpoint"]')).toHaveValue('https://anichin.moe')
  await expect(page.locator('input[name="configSourceUrl"]')).toHaveValue('https://configs.example.org/anichin/provider.json')
  for (let i = 2; i <= 6; i++) { await next(page).click(); await step(page, i) }
  await expect(cat(page, 'search', 'search.htm')).not.toBeChecked()
  await expect(cat(page, 'homepage', 'search.htm')).toBeChecked()
  await expect(cat(page, 'episodes', 'series.htm')).toBeChecked()
  await expect(page.getByText('(html tidak tersedia)').first()).toBeVisible()
})

test('Proyek lama (endpoint = URL .json, tanpa sampleMap) dimigrasikan: Endpoint dan Config Source URL dipisah', async ({ page }) => {
  await page.goto('/')
  const old = {
    version: 1,
    exportedAt: '2025-01-01T00:00:00.000Z',
    project: {
      name: 'Proyek Lama', id: 'proyek-lama', endpoint: 'https://old.example.com/provider.json', source: 'generic_html',
      category: 'general', languages: [], subtitleVariants: [], priority: 0, enabled: true, capabilities: {}, metadata: {},
      workflows: {}, security: null, update: null, samples: [{ fileName: 'search.htm', htmlUrl: '', title: 'x' }],
    },
    offlineTestResults: null,
  }
  await importBundle(page, 'old.json', old)
  await expect(page.locator('input[name="endpoint"]')).toHaveValue('')
  await expect(page.locator('input[name="configSourceUrl"]')).toHaveValue('https://old.example.com/provider.json')
  // Export tidak menampilkan "Belum ada data proyek": kebutuhan input endpoint dinyatakan eksplisit.
  for (let i = 2; i <= 7; i++) { await next(page).click(); await step(page, i) }
  await expect(page.getByRole('alert').filter({ hasText: 'BUTUH INPUT: Provider Endpoint' })).toBeVisible()
  await expect(page.getByText('Belum ada data proyek')).toHaveCount(0)
  await page.locator('#export-endpoint').fill('https://old.example.com')
  await expect(page.getByRole('button', { name: 'Unduh provider.json' })).toBeVisible()
})
