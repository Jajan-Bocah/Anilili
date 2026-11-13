// Audit UI/UX final: 7 langkah wizard di 375/768/1280 px.
// Per langkah: tanpa horizontal overflow, tanpa pelanggaran axe WCAG 2.x A/AA, tanpa console error.
// Set AUDIT_SHOTS=1 untuk menyimpan screenshot ke docs/screenshots/.

import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import fs from 'node:fs'

const VIEWPORTS = [
  { name: '375', width: 375, height: 800 },
  { name: '768', width: 768, height: 1024 },
  { name: '1280', width: 1280, height: 900 },
]

const SAMPLE_HTML = `<!DOCTYPE html><html><body>
  <div class="search-results">
    <h2 class="anime-title">Anime One</h2><a class="detail-link" href="/anime/1">Detail</a>
    <h2 class="anime-title">Anime Two</h2><a class="detail-link" href="/anime/2">Detail</a>
    <h2 class="anime-title">Anime Three</h2><a class="detail-link" href="/anime/3">Detail</a>
    <a class="pagination-next" href="/search?page=2">Next</a>
  </div>
  <div class="episode-list"><a class="ep-link" href="/ep1">Episode 1</a><a class="ep-link" href="/ep2">Episode 2</a><a class="ep-link" href="/ep3">Episode 3</a></div>
  <iframe class="player-iframe" src="https://player.example.com/embed/123"></iframe>
  <video><source src="https://stream.example.com/video.m3u8" type="application/x-mpegURL"></video>
  <select class="server-select"><option value="1">Server 1</option><option value="2">Server 2</option></select>
</body></html>`

const next = (page) => page.getByRole('button', { name: 'Berikutnya' })
const step = (page, n) => expect(page.locator(`text=Langkah ${n} dari 7`).first()).toBeVisible()

async function audit(page, vp, label) {
  const overflow = await page.evaluate(() => ({
    sw: document.documentElement.scrollWidth,
    cw: document.documentElement.clientWidth,
  }))
  expect(overflow.sw, `horizontal overflow di ${label}`).toBeLessThanOrEqual(overflow.cw)

  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()
  const summary = result.violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.slice(0, 3).map((n) => `${n.target.join(' ')} :: ${n.any[0]?.message || ''}`).join(' | ')}`)
  expect(summary, `axe ${label}`).toEqual([])

  if (process.env.AUDIT_SHOTS) {
    fs.mkdirSync('docs/screenshots', { recursive: true })
    await page.screenshot({ path: `docs/screenshots/${vp.name}-${label}.png`, fullPage: true })
  }
}

for (const vp of VIEWPORTS) {
  test(`Audit 7 langkah @ ${vp.name}px`, async ({ page }) => {
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.goto('/')

    // 1 Create
    await step(page, 1)
    await audit(page, vp, '1-create')
    await page.fill('input[name="name"]', 'Audit Project')
    await page.fill('input[name="id"]', 'audit-project')
    await page.fill('input[name="endpoint"]', 'https://anime.example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://anime.example.com/provider.json')
    await next(page).click()

    // 2 Upload (state kosong → berhasil)
    await step(page, 2)
    await audit(page, vp, '2-upload-empty')
    await page.locator('input[type="file"]').first().setInputFiles({ name: 'a.html', mimeType: 'text/html', buffer: Buffer.from(SAMPLE_HTML) })
    await page.getByRole('button', { name: 'Parse Semua' }).click()
    await expect(page.locator('text=1 berhasil')).toBeVisible({ timeout: 15000 })
    await audit(page, vp, '2-upload-done')
    await next(page).click()

    // 3 Analyze
    await step(page, 3)
    await expect(page.getByRole('button', { name: 'Analisis Ulang' })).toBeVisible({ timeout: 30000 })
    await audit(page, vp, '3-analyze')
    await next(page).click()

    // 4 Review
    await step(page, 4)
    const cb = page.locator('input[type="checkbox"]').first()
    await expect(cb).toBeVisible()
    await cb.check()
    await expect(page.locator('text=Workflow siap di-generate')).toBeVisible()
    await audit(page, vp, '4-review')
    await next(page).click()

    // 5 Generate
    await step(page, 5)
    await expect(page.locator('.badge-passed:has-text("VALID")')).toBeVisible({ timeout: 15000 })
    await audit(page, vp, '5-generate')
    await next(page).click()

    // 6 Test
    await step(page, 6)
    await audit(page, vp, '6-test-idle')
    await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
    await expect(page.locator('text=OFFLINE PASSED')).toBeVisible({ timeout: 15000 })
    await audit(page, vp, '6-test-done')
    await next(page).click()

    // 7 Export
    await step(page, 7)
    await audit(page, vp, '7-export')

    expect(errors).toEqual([])
  })
}

test('Navigasi keyboard penuh: wizard dapat diselesaikan hanya dengan keyboard', async ({ page }) => {
  await page.goto('/')
  await step(page, 1)
  await page.locator('input[name="name"]').focus()
  await page.keyboard.type('Kbd Project')
  await page.keyboard.press('Tab')
  await page.keyboard.type('kbd-project')
  await page.keyboard.press('Tab')
  await page.keyboard.type('https://kbd.example.com')

  // Tab sampai tombol Berikutnya mendapat fokus, lalu Enter.
  for (let i = 0; i < 40; i++) {
    if (await next(page).evaluate((el) => el === document.activeElement)) break
    await page.keyboard.press('Tab')
  }
  await expect(next(page)).toBeFocused()
  await page.keyboard.press('Enter')
  await step(page, 2)

  // Fokus terlihat: elemen fokus punya box-shadow/outline (ring) yang bukan 'none'.
  await page.keyboard.press('Tab')
  const indicator = await page.evaluate(() => {
    const cs = getComputedStyle(document.activeElement)
    return { outline: cs.outlineStyle, outlineW: cs.outlineWidth, shadow: cs.boxShadow }
  })
  expect(indicator.shadow !== 'none' || (indicator.outline !== 'none' && indicator.outlineW !== '0px')).toBe(true)
})

test('Perubahan selector di Tinjau Aturan memengaruhi provider.json dan bertahan saat kembali', async ({ page }) => {
  await page.goto('/')
  await page.fill('input[name="name"]', 'Rule Effect')
  await page.fill('input[name="id"]', 'rule-effect')
  await page.fill('input[name="endpoint"]', 'https://anime.example.com')
  await page.fill('input[name="configSourceUrl"]', 'https://anime.example.com/provider.json')
  await next(page).click()
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'a.html', mimeType: 'text/html', buffer: Buffer.from(SAMPLE_HTML) })
  await page.getByRole('button', { name: 'Parse Semua' }).click()
  await expect(page.locator('text=1 berhasil')).toBeVisible({ timeout: 15000 })
  await next(page).click()
  await expect(page.getByRole('button', { name: 'Analisis Ulang' })).toBeVisible({ timeout: 30000 })
  await next(page).click()
  await step(page, 4)

  // Rule episode-url (DETECTED) menjadi step workflow "episodes": selector, atribut DOM, dan saveAs harus masuk provider.json + output.mapping.
  const selector = page.locator('#selector-episode-url')
  await expect(selector).toBeVisible()
  await selector.fill('.custom-selector-xyz')
  await page.locator('#attr-episode-url').fill('data-href')
  await page.locator('#saveas-episode-url').fill('my_episode_urls')
  await expect(page.getByTestId('inference-episodes')).toContainText('my_episode_urls')
  await expect(page.locator('text=Workflow siap di-generate')).toBeVisible()

  await next(page).click()
  await step(page, 5)
  await expect(page.locator('.badge-passed:has-text("VALID")')).toBeVisible({ timeout: 15000 })
  const preview = page.locator('pre', { hasText: '.custom-selector-xyz' })
  await expect(preview).toBeVisible()
  await expect(preview).toContainText('"attribute": "data-href"')
  await expect(preview).toContainText('"episodeUrls": "my_episode_urls"')

  // Kembali ke Review: edit tidak boleh hilang.
  await page.getByRole('button', { name: 'Sebelumnya' }).click()
  await step(page, 4)
  await expect(page.locator('#selector-episode-url')).toHaveValue('.custom-selector-xyz')
  await expect(page.locator('#attr-episode-url')).toHaveValue('data-href')
  await expect(page.locator('#saveas-episode-url')).toHaveValue('my_episode_urls')
})

test('Autosave/restore: edit rule tersimpan di IndexedDB dan pulih setelah reload', async ({ page }) => {
  await page.goto('/')
  await page.fill('input[name="name"]', 'Restore Rule')
  await page.fill('input[name="id"]', 'restore-rule')
  await page.fill('input[name="endpoint"]', 'https://anime.example.com')
  await page.fill('input[name="configSourceUrl"]', 'https://anime.example.com/provider.json')
  await next(page).click()
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'a.html', mimeType: 'text/html', buffer: Buffer.from(SAMPLE_HTML) })
  await page.getByRole('button', { name: 'Parse Semua' }).click()
  await expect(page.locator('text=1 berhasil')).toBeVisible({ timeout: 15000 })
  await next(page).click()
  await expect(page.getByRole('button', { name: 'Analisis Ulang' })).toBeVisible({ timeout: 30000 })
  await next(page).click()
  await step(page, 4)
  await page.locator('input[type="checkbox"]').first().check()
  await page.locator('input[id^="selector-"]').first().fill('.restored-selector')
  await expect(page.locator('text=Workflow siap di-generate')).toBeVisible()

  // Tunggu autosave (debounce) sampai edit benar-benar ada di IndexedDB — berbasis state, bukan sleep.
  await expect.poll(() => page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open('anilili-provider-generator')
    open.onerror = () => reject(open.error)
    open.onsuccess = () => {
      const req = open.result.transaction('projects').objectStore('projects').getAll()
      req.onsuccess = () => resolve(JSON.stringify(req.result).includes('.restored-selector'))
      req.onerror = () => reject(req.error)
    }
  })), { timeout: 10000 }).toBe(true)

  await page.reload()
  await expect(page.locator('input[name="name"]')).toHaveValue('Restore Rule')
  for (let i = 2; i <= 4; i++) {
    await next(page).click()
    await step(page, i)
  }
  await expect(page.locator('input[id^="selector-"]').first()).toHaveValue('.restored-selector')
})
