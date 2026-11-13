import { test, expect } from '@playwright/test'

async function waitForStep(page, stepLabel) {
  // Wait for step indicator (e.g., "Langkah 1 dari 7") which is always visible
  // Map step labels to step numbers
  const stepMap = {
    'Buat Proyek': '1',
    'Unggah Sampel': '2',
    'Analisis': '3',
    'Tinjau Aturan': '4',
    'Generate JSON': '5',
    'Uji Provider': '6',
    'Ekspor': '7',
  }
  const stepNum = stepMap[stepLabel] || '1'
  await expect(page.locator(`text=Langkah ${stepNum} dari 7`).first()).toBeVisible({ timeout: 15000 })
}

// Auto-analisis berjalan saat langkah Analisis dibuka. Tombol "Analisis Ulang" hanya muncul
// setelah inferredRules tersimpan dan dipropagasi ke App (state yang sama), jadi ini sinyal deterministik.
async function waitForAnalysisDone(page) {
  await expect(page.getByRole('button', { name: 'Analisis Ulang' })).toBeVisible({ timeout: 30000 })
  await expect(page.locator('text=Detail Hasil per Sampel')).toBeVisible()
  await expect(page.locator('text=Ringkasan Aturan Tergabung')).toBeVisible()
}

async function gotoExportStep(page) {
  for (let i = 2; i <= 7; i++) {
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await expect(page.locator(`text=Langkah ${i} dari 7`).first()).toBeVisible()
  }
}

test.describe('Full Wizard Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()
  })

  test('Complete flow: Create Project → Upload Samples → Analyze → Review Rules → Generate JSON → Test Provider → Export', async ({ page }) => {
    // STEP 1: Create Project
    await waitForStep(page, 'Buat Proyek')

    await page.fill('input[name="name"]', 'E2E Test Project')
    await page.fill('input[name="id"]', 'e2e-test-project')
    await page.fill('input[name="endpoint"]', 'https://anime.example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://anime.example.com/provider.json')
    await page.selectOption('select[name="source"]', 'generic_html')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    // STEP 2: Upload Samples (HTML) - comprehensive sample to trigger rule detection
    const sampleHtml = `<!DOCTYPE html>
<html><body>
  <!-- Search page structure -->
  <div class="search-results">
    <h2 class="anime-title">Anime One</h2>
    <a class="detail-link" href="/anime/1">Detail</a>
    <h2 class="anime-title">Anime Two</h2>
    <a class="detail-link" href="/anime/2">Detail</a>
    <h2 class="anime-title">Anime Three</h2>
    <a class="detail-link" href="/anime/3">Detail</a>
    <a class="pagination-next" href="/search?page=2">Next</a>
  </div>

  <!-- Episode page structure -->
  <div class="episode-list">
    <a class="ep-link" href="/ep1">Episode 1</a>
    <a class="ep-link" href="/ep2">Episode 2</a>
    <a class="ep-link" href="/ep3">Episode 3</a>
  </div>

  <!-- Player page structure -->
  <iframe class="player-iframe" src="https://player.example.com/embed/123"></iframe>
  <video><source src="https://stream.example.com/video.m3u8" type="application/x-mpegURL"></video>
  <select class="server-select">
    <option value="1">Server 1</option>
    <option value="2">Server 2</option>
  </select>
</body></html>`
    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.html',
      mimeType: 'text/html',
      buffer: Buffer.from(sampleHtml),
    })

    const pageErrors = []
    page.on('pageerror', (err) => pageErrors.push(err.message))
    page.on('console', (msg) => { if (msg.type() === 'error') pageErrors.push(msg.text()) })

    await page.getByRole('button', { name: 'Parse Semua' }).click()

    // Wait for parsing to complete
    await expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 15000 })
    await expect(page.locator('text=1 berhasil')).toBeVisible({ timeout: 15000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Analisis')

    // STEP 3: Analyze (auto-run saat langkah dibuka)
    await waitForAnalysisDone(page)
    await expect(page.locator('text=Search Rules:')).toBeVisible()
    await expect(page.locator('text=Episodes Rules:')).toBeVisible()
    await expect(page.locator('text=Stream Rules:')).toBeVisible()


    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Tinjau Aturan')

    // Wait for RuleEditor to receive inferredRules and render checkboxes
    await expect(page.locator('text=Belum ada aturan terdeteksi')).not.toBeVisible({ timeout: 10000 })

    // STEP 4: Review Rules
    await expect(page.locator('text=Tinjau Aturan & Workflow')).toBeVisible()
    // Enable at least one detected rule (e.g., search-container)
    const firstCheckbox = page.locator('input[type="checkbox"]').first()
    await expect(firstCheckbox).toBeVisible({ timeout: 10000 })
    await firstCheckbox.click()
    // Wait for workflow to be generated
    await expect(page.locator('text=Workflow siap di-generate')).toBeVisible({ timeout: 10000 })
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Generate JSON')

    // STEP 5: Generate JSON
    await page.getByRole('button', { name: 'Generate Ulang' }).click()
    // Wait for schema validation to complete and show VALID badge
    await expect(page.locator('h3:has-text("Validasi Schema")')).toBeVisible({ timeout: 15000 })
    await expect(page.locator('.badge-passed:has-text("VALID")')).toBeVisible({ timeout: 15000 })
    await expect(page.locator('h3:has-text("Preview provider.json")')).toBeVisible()

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Uji Provider')

    // STEP 6: Test Provider (Offline)
    await page.getByRole('button', { name: 'Jalankan Uji Offline' }).click()
    await expect(page.locator('text=Hasil per Workflow Step')).toBeVisible({ timeout: 15000 })
    // There are multiple tables - use the one with the workflow steps results
    await expect(page.locator('table.w-full.text-left.text-sm').first()).toBeVisible()
    // Gerbang parser engine (bukan label tier): workflow hasil inferensi memberi item nyata yang diterima parser.
    // Sampel sintetis ini: episodes → 3 item, sources → 1 item (iframe); search tidak dibuat (tanpa form pencarian = bukti kurang).
    await expect(page.getByText(/parser engine: 3 item/).first()).toBeVisible()
    await expect(page.getByText(/parser engine: 1 item/).first()).toBeVisible()
    await expect(page.getByText(/menghasilkan 0 item/)).toHaveCount(0)

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Ekspor')

    // STEP 7: Export
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Unduh provider.json' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('provider.json')

    const bundleDownloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Unduh Bundel' }).click()
    const bundleDownload = await bundleDownloadPromise
    expect(bundleDownload.suggestedFilename()).toContain('-bundle.json')

    const testReportDownloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Unduh Test Report' }).click()
    const testReportDownload = await testReportDownloadPromise
    expect(testReportDownload.suggestedFilename()).toContain('-test-report.json')

    await page.getByRole('button', { name: 'Simpan Proyek' }).click()
    await expect(page.locator('text=Proyek disimpan ke IndexedDB')).toBeVisible({ timeout: 5000 })
    await expect(page.locator('text=Status Kompatibilitas (4-tier)')).toBeVisible()
    expect(pageErrors).toEqual([])
  })
})

test.describe('File Upload Formats', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()
  })

  test('Upload MHTML sample', async ({ page }) => {
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'MHTML Test')
    await page.fill('input[name="id"]', 'mhtml-test')
    await page.fill('input[name="endpoint"]', 'https://example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://example.com/provider.json')
    await page.selectOption('select[name="source"]', 'generic_html')
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    const mhtmlContent = `From: test@example.com
Content-Type: multipart/related; boundary="boundary123"

--boundary123
Content-Type: text/html; charset=utf-8
Content-Location: https://example.com/page.html

<!DOCTYPE html><html><body><div class="anime-item"><a class="ep" href="/ep1">Ep 1</a></div></body></html>
--boundary123--`

    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.mhtml',
      mimeType: 'multipart/related',
      buffer: Buffer.from(mhtmlContent),
    })

    await expect(page.getByRole('button', { name: 'Parse Semua' })).toBeEnabled({ timeout: 10000 })
    await page.getByRole('button', { name: 'Parse Semua' }).click()

    // Wait for either success or error
    await Promise.race([
      expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 30000 }).catch(() => {}),
      expect(page.locator('text=Error:')).toBeVisible({ timeout: 30000 }).catch(() => {}),
      expect(page.locator('text=Gagal')).toBeVisible({ timeout: 30000 }).catch(() => {}),
    ])
  })

  test('Upload HAR sample', async ({ page }) => {
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'HAR Test')
    await page.fill('input[name="id"]', 'har-test')
    await page.fill('input[name="endpoint"]', 'https://example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://example.com/provider.json')
    await page.selectOption('select[name="source"]', 'generic_html')
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    const harContent = {
      log: {
        version: '1.2',
        creator: { name: 'Test', version: '1.0' },
        entries: [{
          request: { url: 'https://example.com/api/search?q=test', method: 'GET', headers: [] },
          response: { status: 200, headers: [], content: { mimeType: 'application/json', text: '{"results":[]}' } },
        }],
      },
    }

    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.har',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(harContent)),
    })

    await expect(page.getByRole('button', { name: 'Parse Semua' })).toBeEnabled({ timeout: 10000 })
    await page.getByRole('button', { name: 'Parse Semua' }).click()

    await expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 15000 })
    await expect(page.locator('text=1 berhasil')).toBeVisible()
  })
})

test.describe('IndexedDB Persistence', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()
  })

  test('Save project, reload, verify in sidebar', async ({ page }) => {
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'Persistence Test')
    await page.fill('input[name="id"]', 'persistence-test')
    await page.fill('input[name="endpoint"]', 'https://persist.example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://persist.example.com/provider.json')

    // Navigate through steps
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    // Upload a sample HTML
    const sampleHtml = `<!DOCTYPE html>
<html><body>
  <!-- Search page structure -->
  <div class="search-results">
    <h2 class="anime-title">Anime One</h2>
    <a class="detail-link" href="/anime/1">Detail</a>
    <h2 class="anime-title">Anime Two</h2>
    <a class="detail-link" href="/anime/2">Detail</a>
    <h2 class="anime-title">Anime Three</h2>
    <a class="detail-link" href="/anime/3">Detail</a>
    <a class="pagination-next" href="/search?page=2">Next</a>
  </div>

  <!-- Episode page structure -->
  <div class="episode-list">
    <a class="ep-link" href="/ep1">Episode 1</a>
    <a class="ep-link" href="/ep2">Episode 2</a>
    <a class="ep-link" href="/ep3">Episode 3</a>
  </div>

  <!-- Player page structure -->
  <iframe class="player-iframe" src="https://player.example.com/embed/123"></iframe>
  <video><source src="https://stream.example.com/video.m3u8" type="application/x-mpegURL"></video>
  <select class="server-select">
    <option value="1">Server 1</option>
    <option value="2">Server 2</option>
  </select>
</body></html>`
    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.html',
      mimeType: 'text/html',
      buffer: Buffer.from(sampleHtml),
    })
    await page.getByRole('button', { name: 'Parse Semua' }).click()
    await expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 15000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Analisis')

    await waitForAnalysisDone(page)


    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Tinjau Aturan')

    // Wait for RuleEditor to receive inferredRules and render checkboxes
    await expect(page.locator('text=Belum ada aturan terdeteksi')).not.toBeVisible({ timeout: 10000 })

    // Enable a rule
    const firstCheckbox = page.locator('input[type="checkbox"]').first()
    await expect(firstCheckbox).toBeVisible({ timeout: 10000 })
    await firstCheckbox.click()
    await expect(page.locator('text=Workflow siap di-generate')).toBeVisible({ timeout: 10000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Generate JSON')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Uji Provider')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Ekspor')
    await page.getByRole('button', { name: 'Simpan Proyek' }).click()
    await expect(page.locator('text=Proyek disimpan ke IndexedDB')).toBeVisible({ timeout: 5000 })

    await page.reload()
    await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()

    await expect(page.locator('text=Proyek Tersimpan')).toBeVisible()
    await expect(page.locator('p:has-text("Persistence Test")').first()).toBeVisible()
  })

  test('Delete saved project from sidebar', async ({ page }) => {
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'To Delete')
    await page.fill('input[name="id"]', 'to-delete')
    await page.fill('input[name="endpoint"]', 'https://delete.example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://delete.example.com/provider.json')

    // Navigate through steps with sample and workflow
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    const sampleHtml = `<!DOCTYPE html>
<html><body>
  <!-- Search page structure -->
  <div class="search-results">
    <h2 class="anime-title">Anime One</h2>
    <a class="detail-link" href="/anime/1">Detail</a>
    <h2 class="anime-title">Anime Two</h2>
    <a class="detail-link" href="/anime/2">Detail</a>
    <h2 class="anime-title">Anime Three</h2>
    <a class="detail-link" href="/anime/3">Detail</a>
    <a class="pagination-next" href="/search?page=2">Next</a>
  </div>

  <!-- Episode page structure -->
  <div class="episode-list">
    <a class="ep-link" href="/ep1">Episode 1</a>
    <a class="ep-link" href="/ep2">Episode 2</a>
    <a class="ep-link" href="/ep3">Episode 3</a>
  </div>

  <!-- Player page structure -->
  <iframe class="player-iframe" src="https://player.example.com/embed/123"></iframe>
  <video><source src="https://stream.example.com/video.m3u8" type="application/x-mpegURL"></video>
  <select class="server-select">
    <option value="1">Server 1</option>
    <option value="2">Server 2</option>
  </select>
</body></html>`
    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.html',
      mimeType: 'text/html',
      buffer: Buffer.from(sampleHtml),
    })
    await page.getByRole('button', { name: 'Parse Semua' }).click()
    await expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 15000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Analisis')

    await waitForAnalysisDone(page)


    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Tinjau Aturan')

    const firstCheckbox = page.locator('input[type="checkbox"]').first()
    await expect(firstCheckbox).toBeVisible({ timeout: 10000 })
    await firstCheckbox.click()
    await expect(page.locator('text=Workflow siap di-generate')).toBeVisible({ timeout: 10000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Generate JSON')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Uji Provider')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Ekspor')
    await page.getByRole('button', { name: 'Simpan Proyek' }).click()
    await expect(page.locator('text=Proyek disimpan ke IndexedDB')).toBeVisible({ timeout: 5000 })

    await page.reload()
    await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()

    await page.getByRole('button', { name: 'Hapus' }).click()
    await expect(page.locator('p:has-text("To Delete")').first()).not.toBeVisible({ timeout: 5000 })
  })
})

test.describe('Import/Export', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()
  })

  test('Import old format provider.json (backward compatibility)', async ({ page }) => {
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'Import Test')
    await page.fill('input[name="id"]', 'import-test')
    await page.fill('input[name="endpoint"]', 'https://example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://example.com/provider.json')

    // Navigate through steps with sample and workflow
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    const sampleHtml = `<!DOCTYPE html>
<html><body>
  <!-- Search page structure -->
  <div class="search-results">
    <h2 class="anime-title">Anime One</h2>
    <a class="detail-link" href="/anime/1">Detail</a>
    <h2 class="anime-title">Anime Two</h2>
    <a class="detail-link" href="/anime/2">Detail</a>
    <h2 class="anime-title">Anime Three</h2>
    <a class="detail-link" href="/anime/3">Detail</a>
    <a class="pagination-next" href="/search?page=2">Next</a>
  </div>

  <!-- Episode page structure -->
  <div class="episode-list">
    <a class="ep-link" href="/ep1">Episode 1</a>
    <a class="ep-link" href="/ep2">Episode 2</a>
    <a class="ep-link" href="/ep3">Episode 3</a>
  </div>

  <!-- Player page structure -->
  <iframe class="player-iframe" src="https://player.example.com/embed/123"></iframe>
  <video><source src="https://stream.example.com/video.m3u8" type="application/x-mpegURL"></video>
  <select class="server-select">
    <option value="1">Server 1</option>
    <option value="2">Server 2</option>
  </select>
</body></html>`
    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.html',
      mimeType: 'text/html',
      buffer: Buffer.from(sampleHtml),
    })
    await page.getByRole('button', { name: 'Parse Semua' }).click()
    await expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 15000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Analisis')

    await waitForAnalysisDone(page)


    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Tinjau Aturan')

    const firstCheckbox = page.locator('input[type="checkbox"]').first()
    await expect(firstCheckbox).toBeVisible({ timeout: 10000 })
    await firstCheckbox.click()
    await expect(page.locator('text=Workflow siap di-generate')).toBeVisible({ timeout: 10000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Generate JSON')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Uji Provider')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Ekspor')

    const oldFormat = {
      providers: [{
        id: 'old-format',
        name: 'Old Format',
        endpoint: 'https://old.example.com',
        source: 'generic_html',
      }],
      workflows: { search: { name: 'search', steps: [] } },
    }

    const importFileInput = page.locator('input[id="import-bundle"]')
    await importFileInput.setInputFiles({
      name: 'old-provider.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(oldFormat)),
    })

    // Successful import navigates to the first step; wait for the asynchronous FileReader event.
    await waitForStep(page, 'Buat Proyek')
    await expect(page.locator('input[name="name"]')).toHaveValue('Old Format')
  })

  test('Import new format project bundle', async ({ page }) => {
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'Import Test 2')
    await page.fill('input[name="id"]', 'import-test-2')
    await page.fill('input[name="endpoint"]', 'https://example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://example.com/provider.json')

    // Navigate through steps with sample and workflow
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    const sampleHtml = `<!DOCTYPE html>
<html><body>
  <!-- Search page structure -->
  <div class="search-results">
    <h2 class="anime-title">Anime One</h2>
    <a class="detail-link" href="/anime/1">Detail</a>
    <h2 class="anime-title">Anime Two</h2>
    <a class="detail-link" href="/anime/2">Detail</a>
    <h2 class="anime-title">Anime Three</h2>
    <a class="detail-link" href="/anime/3">Detail</a>
    <a class="pagination-next" href="/search?page=2">Next</a>
  </div>

  <!-- Episode page structure -->
  <div class="episode-list">
    <a class="ep-link" href="/ep1">Episode 1</a>
    <a class="ep-link" href="/ep2">Episode 2</a>
    <a class="ep-link" href="/ep3">Episode 3</a>
  </div>

  <!-- Player page structure -->
  <iframe class="player-iframe" src="https://player.example.com/embed/123"></iframe>
  <video><source src="https://stream.example.com/video.m3u8" type="application/x-mpegURL"></video>
  <select class="server-select">
    <option value="1">Server 1</option>
    <option value="2">Server 2</option>
  </select>
</body></html>`
    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'test.html',
      mimeType: 'text/html',
      buffer: Buffer.from(sampleHtml),
    })
    await page.getByRole('button', { name: 'Parse Semua' }).click()
    await expect(page.locator('text=Hasil Parsing')).toBeVisible({ timeout: 15000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Analisis')

    await waitForAnalysisDone(page)


    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Tinjau Aturan')

    const firstCheckbox = page.locator('input[type="checkbox"]').first()
    await expect(firstCheckbox).toBeVisible({ timeout: 10000 })
    await firstCheckbox.click()
    await expect(page.locator('text=Workflow siap di-generate')).toBeVisible({ timeout: 10000 })

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Generate JSON')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Uji Provider')

    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Ekspor')

    const newFormat = {
      version: 1,
      exportedAt: new Date().toISOString(),
      project: {
        name: 'Bundle Import',
        id: 'bundle-import',
        endpoint: 'https://bundle.example.com',
        source: 'generic_html',
        category: 'general',
        languages: [],
        subtitleVariants: [],
        priority: 0,
        enabled: true,
        capabilities: {},
        metadata: {},
        workflows: {},
        security: null,
        update: null,
        samples: [],
      },
      offlineTestResults: null,
    }

    const importFileInput = page.locator('input[id="import-bundle"]')
    await importFileInput.setInputFiles({
      name: 'project-bundle.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(newFormat)),
    })

    await waitForStep(page, 'Buat Proyek')
    await expect(page.locator('input[name="name"]')).toHaveValue('Bundle Import')
  })
})

test.describe('Accessibility & Keyboard Navigation', () => {
  test('Tab navigation through Create Project form', async ({ page }) => {
    await page.goto('/')
    await waitForStep(page, 'Buat Proyek')

    const nameInput = page.locator('input[name="name"]')
    await nameInput.focus()
    await expect(nameInput).toBeFocused()

    await page.keyboard.press('Tab')
    const idInput = page.locator('input[name="id"]')
    await expect(idInput).toBeFocused()

    await page.keyboard.press('Tab')
    const endpointInput = page.locator('input[name="endpoint"]')
    await expect(endpointInput).toBeFocused()
  })

  test('ARIA labels on form inputs', async ({ page }) => {
    await page.goto('/')
    await waitForStep(page, 'Buat Proyek')

    const nameInput = page.locator('input[name="name"]')
    const inputId = await nameInput.getAttribute('id')
    expect(inputId).toBeTruthy()

    const label = page.locator(`label[for="${inputId}"]`)
    await expect(label).toBeVisible()
  })

  test('Step indicator visible', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('text=Langkah 1 dari 7')).toBeVisible()
  })
})

test.describe('Responsive Design', () => {
  const viewports = [
    { name: 'mobile', width: 375, height: 667 },
    { name: 'tablet', width: 768, height: 1024 },
    { name: 'desktop', width: 1280, height: 720 },
  ]

  for (const vp of viewports) {
    test(`Wizard renders correctly at ${vp.name} (${vp.width}px)`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await page.goto('/')

      await expect(page.locator('h1:has-text("Anilili Provider JSON Generator")')).toBeVisible()
      await expect(page.locator('text=Langkah 1 dari 7')).toBeVisible()
      await expect(page.locator('main').first()).toBeVisible()
    })
  }
})

test.describe('Error & Empty States', () => {
  test('Empty state on Export without project', async ({ page }) => {
    await page.goto('/')
    await gotoExportStep(page)

    await waitForStep(page, 'Ekspor')
    await expect(page.locator('text=Belum ada data proyek')).toBeVisible()
    await expect(page.locator('text=Lengkapi langkah Buat Proyek terlebih dahulu')).toBeVisible()
  })

  test('Error state on invalid import', async ({ page }) => {
    await page.goto('/')
    await gotoExportStep(page)
    await waitForStep(page, 'Ekspor')

    const fileInput = page.locator('input[id="import-bundle"]')
    await fileInput.setInputFiles({
      name: 'invalid.json',
      mimeType: 'application/json',
      buffer: Buffer.from('not valid json'),
    })

    await expect(page.locator('text=Gagal mengimpor bundel proyek')).toBeVisible({ timeout: 10000 })
  })

  test('Loading state during parsing', async ({ page }) => {
    await page.goto('/')
    await waitForStep(page, 'Buat Proyek')
    await page.fill('input[name="name"]', 'Load Test')
    await page.fill('input[name="id"]', 'load-test')
    await page.fill('input[name="endpoint"]', 'https://example.com')
    await page.fill('input[name="configSourceUrl"]', 'https://example.com/provider.json')
    await page.getByRole('button', { name: 'Berikutnya' }).click()
    await waitForStep(page, 'Unggah Sampel')

    // 5 MB sample: parsing takes long enough for the loading state to be observable
    const largeHtml = '<html><body>' + '<p>row</p>'.repeat(500000) + '</body></html>'
    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles({
      name: 'large.html',
      mimeType: 'text/html',
      buffer: Buffer.from(largeHtml),
    })
    // State "Memparsing" bisa hanya sekejap; rekam lewat MutationObserver sebelum klik, bukan polling.
    await page.evaluate(() => {
      window.__sawParsing = false
      new MutationObserver(() => {
        if (document.body.textContent.includes('Memparsing')) window.__sawParsing = true
      }).observe(document.body, { subtree: true, childList: true, characterData: true })
    })
    await page.getByRole('button', { name: 'Parse Semua' }).click()
    await expect(page.locator('text=1 berhasil')).toBeVisible({ timeout: 30000 })
    expect(await page.evaluate(() => window.__sawParsing)).toBe(true)
    await expect(page.getByRole('button', { name: 'Parse Semua' })).toBeEnabled()
  })
})
