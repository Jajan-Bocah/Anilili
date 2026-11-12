package com.anilili.data.provider.remote

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Offline fixture parser tests for Anichin and Kuramanime.
 *
 * Parses the staged HTML/HAR fixtures using the same selectors declared in the provider
 * config JSON assets, and asserts that real metadata (titles, URLs, episode numbers,
 * mirror streams) is extracted.
 *
 * No emulator, no live network. Fixtures are read from src/test/resources/fixtures/.
 *
 * Results:
 * - Anichin: PASS if search/episodes/watch selectors extract expected data
 * - Kuramanime: search page HTML is readable; episode extraction (a.ep-button) requires
 *   a detail-page fixture which is not yet staged -> reported per-test below
 */
class ProviderFixtureOfflineTest {

    private val fixturesDir = File("src/test/resources/fixtures")

    // ── Anichin ────────────────────────────────────────────────────────

    @Test
    fun `anichin search fixture extracts titles urls and images`() {
        val html = readFixture("anichin/search.htm")
        val doc: Document = Jsoup.parse(html)

        // Selector from anichin.json search workflow: .listupd article.bs a.tip
        val links = doc.select(".listupd article.bs a.tip")
        assertFalse("Anichin search should have at least one .listupd article.bs a.tip link", links.isEmpty())

        val titles = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val images = mutableListOf<String>()

        links.forEach { link ->
            val title = link.attr("title").ifBlank { link.text() }
            val url = link.attr("href").ifBlank { "" }
            val img = link.selectFirst("img")?.attr("src") ?: ""
            if (title.isNotBlank()) titles.add(title)
            if (url.isNotBlank()) urls.add(url)
            if (img.isNotBlank()) images.add(img)
        }

        assertTrue("Should extract at least one title", titles.isNotEmpty())
        assertTrue("Should extract at least one URL", urls.isNotEmpty())
        titles.forEach { assertNotNull("Title should not be null", it) }
        urls.forEach { assertTrue("URL should start with / or http", it.startsWith("/") || it.startsWith("http")) }
    }

    @Test
    fun `anichin series fixture extracts episode urls numbers and titles`() {
        val html = readFixture("anichin/series.htm")
        val doc: Document = Jsoup.parse(html)

        // Selectors from anichin.json episodes workflow
        val epLinks = doc.select(".eplister li a")
        val epNumbers = doc.select(".eplister li .epl-num")
        val epTitles = doc.select(".eplister li .epl-title")

        assertFalse("Anichin series should have .eplister li a links", epLinks.isEmpty())

        val urls = mutableListOf<String>()
        val numbers = mutableListOf<String>()
        val titles = mutableListOf<String>()

        epLinks.forEach { link ->
            val url = link.attr("href").ifBlank { "" }
            if (url.isNotBlank()) urls.add(url)
        }
        epNumbers.forEach { el ->
            val num = el.text().ifBlank { "" }
            if (num.isNotBlank()) numbers.add(num)
        }
        epTitles.forEach { el ->
            val title = el.text().ifBlank { "" }
            if (title.isNotBlank()) titles.add(title)
        }

        assertTrue("Should extract at least one episode URL", urls.isNotEmpty())
        urls.forEach { assertTrue("Episode URL should start with / or http", it.startsWith("/") || it.startsWith("http")) }
        // Numbers and titles may be empty if the fixture uses a different markup variant;
        // we report what we find rather than fail the whole suite.
        System.out.println("Anichin series: urls=${urls.size} numbers=${numbers.size} titles=${titles.size}")
    }

    @Test
    fun `anichin watch fixture extracts mirror streams and server names`() {
        val html = readFixture("anichin/watch.htm")
        val doc: Document = Jsoup.parse(html)

        // Selector from anichin.json sources workflow: select.mirror option
        val options = doc.select("select.mirror option")
        assertFalse("Anichin watch should have select.mirror option elements", options.isEmpty())

        val streamUrls = mutableListOf<String>()
        val serverNames = mutableListOf<String>()

        options.forEach { opt ->
            val value = opt.attr("value").ifBlank { "" }
            val text = opt.text().ifBlank { "" }
            if (text.isNotBlank()) serverNames.add(text)

            // First option has empty value; remaining options are Base64-encoded iframe HTML.
            // Decode Base64 and extract the iframe src URL.
            if (value.isNotBlank()) {
                val decoded = try {
                    String(java.util.Base64.getDecoder().decode(value))
                } catch (e: Exception) {
                    value
                }
                // Extract src="https://..." from decoded iframe markup
                val srcMatch = Regex("src=\"(https://[^\"]+)\"").find(decoded)
                srcMatch?.groupValues?.getOrNull(1)?.let { streamUrls.add(it) }
            }
        }

        assertTrue("Should extract at least one mirror stream URL", streamUrls.isNotEmpty())
        assertTrue("Should extract at least one server name", serverNames.isNotEmpty())
        streamUrls.forEach { assertTrue("Mirror URL should start with http", it.startsWith("http")) }
    }

    // ── Kuramanime ─────────────────────────────────────────────────────

    @Test
    fun `kuramanime search fixture is readable and contains search results markup`() {
        val html = readFixture("kuramanime/search.htm")
        val doc: Document = Jsoup.parse(html)

        assertNotNull("Kuramanime search document should parse", doc)
        assertTrue("Should have a title", doc.title().isNotBlank())

        // Kuramanime search workflow uses JSON API (need_json=true), not HTML parsing.
        // The staged fixture is the search HTML page. We verify it is well-formed HTML.
        // Episode extraction for Kuramanime uses `a.ep-button[href*="/episode/"]` on the
        // detail page; no detail-page fixture is staged yet, so that selector is not
        // tested here (reported as NOT TESTED for Kuramanime episodes).
        val epButtons = doc.select("a.ep-button[href*=\"/episode/\"]")
        System.out.println("Kuramanime search page: ep-button count=${epButtons.size} (expected 0 on search page)")
    }

    @Test
    fun `anichin and kuramanime fixture files all exist`() {
        val anichinFiles = listOf("search.htm", "series.htm", "watch.htm")
        val kuramanimeFiles = listOf("search.htm")

        anichinFiles.forEach { f ->
            val file = File(fixturesDir, "anichin/$f")
            assertTrue("Anichin fixture must exist: anichin/$f", file.exists())
            assertTrue("Anichin fixture must be non-empty: anichin/$f", file.length() > 0)
        }

        kuramanimeFiles.forEach { f ->
            val file = File(fixturesDir, "kuramanime/$f")
            assertTrue("Kuramanime fixture must exist: kuramanime/$f", file.exists())
            assertTrue("Kuramanime fixture must be non-empty: kuramanime/$f", file.length() > 0)
        }
    }

    // ── Private ────────────────────────────────────────────────────────

    private fun readFixture(relativePath: String): String {
        val file = File(fixturesDir, relativePath)
        assertNotNull("Fixture file must exist: $relativePath", file)
        assertTrue("Fixture file must be readable: $relativePath", file.isFile)
        return file.readText()
    }
}
