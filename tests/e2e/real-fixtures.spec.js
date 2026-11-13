// E2E dengan sampel HTML NYATA (tests/fixtures/real/anichin, lihat MANIFEST.json untuk provenance).
// Memverifikasi bahwa hasil analisis berasal dari berkas yang diunggah, bukan data bawaan.
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { validateProviderJson } from '../../src/lib/contract/engineContract.js'

const fx = (n) => readFileSync(resolve('tests/fixtures/real/anichin', n))

test('Upload sampel nyata Anichin → analisis berasal dari berkas → provider.json patuh kontrak', async ({ page }) => {
  await page.goto('/')
  await page.fill('input[name="name"]', 'Anichin Real')
  await page.fill('input[name="id"]', 'anichin-real')
  await page.fill('input[name="endpoint"]', 'https://anichin.moe')
  // Config Source URL (lokasi hosting provider.json) terpisah dari Provider Endpoint (situs yang di-fetch).
  await page.fill('input[name="configSourceUrl"]', 'https://configs.example.org/anichin/provider.json')
  await page.selectOption('select[name="source"]', 'generic_html')
  await page.getByRole('button', { name: 'Berikutnya' }).click()

  await page.locator('input[type="file"]').first().setInputFiles(
    ['search.htm', 'series.htm', 'watch.htm'].map((name) => ({ name, mimeType: 'text/html', buffer: fx(name) }))
  )
  await page.getByRole('button', { name: 'Parse Semua' }).click()
  await expect(page.locator('text=3 berhasil')).toBeVisible({ timeout: 30000 })
  await page.getByRole('button', { name: 'Berikutnya' }).click()

  await expect(page.getByRole('button', { name: 'Analisis Ulang' })).toBeVisible({ timeout: 30000 })
  // Hasil analisis berasal dari berkas: panjang karakter = isi berkas, selector memuat class dari search.htm.
  const detail = await page.locator('main').innerText()
  expect(detail).toContain(`(${fx('search.htm').toString('utf8').length} chars)`)
  expect(detail).toContain(`(${fx('series.htm').toString('utf8').length} chars)`)
  // Selector hasil analisis berasal dari struktur search.htm (kartu hasil), bukan dari data bawaan.
  expect(fx('search.htm').toString('utf8')).toContain('class="bsx"')
  expect(detail).toContain('article.bs > div.bsx > a.tip')

  await page.getByRole('button', { name: 'Berikutnya' }).click()
  await expect(page.locator('text=Tinjau Aturan & Workflow')).toBeVisible()
  await expect(page.locator('text=Workflow siap di-generate')).toBeVisible({ timeout: 10000 })
  // Inferensi otomatis: Search/Episodes/Sources READY dengan output.type, Servers NEEDS_REVIEW (engine butuh list Map).
  for (const n of ['search', 'episodes', 'sources']) await expect(page.getByTestId(`inference-${n}`)).toContainText('READY')
  await expect(page.getByTestId('inference-search')).toContainText('output SEARCH')
  await expect(page.getByTestId('inference-episodes')).toContainText('output EPISODES')
  await expect(page.getByTestId('inference-servers')).toContainText('NEEDS_REVIEW')
  await page.getByRole('button', { name: 'Berikutnya' }).click()

  await page.getByRole('button', { name: 'Generate Ulang' }).click()
  await expect(page.locator('.badge-passed:has-text("VALID")')).toBeVisible({ timeout: 15000 })
  const preview = await page.locator('main').innerText()
  expect(preview).toContain('https://anichin.moe')
  expect(preview).toContain('https://configs.example.org/anichin/provider.json')
  expect(preview).toContain('{{query}}')
  expect(preview).toContain('parse_html')
  // Tanpa uji Android, blocker kritis harus tetap tampil (tidak ada klaim siap-playback).
  expect(preview).toContain('android-playback-untested')

  await page.getByRole('button', { name: 'Berikutnya' }).click()
  await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
  await expect(page.locator('text=Hasil per Workflow Step')).toBeVisible({ timeout: 15000 })
  // Replay per workflow: tiap workflow memakai sampelnya sendiri (search.htm / series.htm / watch.htm), bukan satu HTML untuk semua.
  const out = await page.locator('main').innerText()
  expect(out).toContain('sampel: search.htm')
  expect(out).toContain('sampel: series.htm')
  expect(out).toContain('sampel: watch.htm')
  expect(out).toContain('REPLAY fixture')
  expect(out).toContain('jaringan/redirect/policy NOT_TESTED')
  // Gerbang parser engine: item nyata yang diterima parser runtime (sama dengan config resmi Anichin: search 2, episodes 166).
  expect(out).toContain('parser engine: 2 item')
  expect(out).toContain('parser engine: 166 item')
  expect(out).toContain('parser engine: 1 item')
  expect(out).not.toMatch(/menghasilkan 0 item/)
  await page.getByRole('button', { name: 'Berikutnya' }).click()
  await expect(page.locator('text=Langkah 7 dari 7').first()).toBeVisible()

  // Export: provider.json hasil unduhan harus lolos validator kontrak dan tiap step non-http punya params.input.
  const dl = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Unduh provider.json' }).click()
  const json = JSON.parse(readFileSync(await (await dl).path(), 'utf8'))
  expect(validateProviderJson(json).valid).toBe(true)
  const steps = Object.values(json.workflows || {}).flatMap((w) => w.steps || [])
  expect(steps.length).toBeGreaterThan(0)
  for (const s of steps.filter((x) => x.op !== 'http')) expect(s.params?.input, `${s.op} tanpa input`).toBeTruthy()
  // Status kompatibilitas teramati (badge Export): tidak pernah ANILILI_READY tanpa uji Android.
  const badge = (await page.locator('span.rounded.text-xs.font-semibold').filter({ hasText: /^[A-Z_]{6,}$/ }).allInnerTexts())
  expect(badge.length).toBeGreaterThan(0)
  expect(badge).not.toContain('ANILILI_READY')
  // Gerbang parser lulus, tetapi tanpa uji Android asli status tertinggi adalah OFFLINE_PASSED (bukan ANILILI_READY).
  expect(badge[0]).toBe('OFFLINE_PASSED')
})
