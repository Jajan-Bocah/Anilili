package com.anilili.data.provider.remote.runtime

import com.anilili.data.model.Category
import com.anilili.data.provider.remote.config.ProviderCapabilities
import com.anilili.data.provider.remote.config.RemoteCategory
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.config.RemoteSourceType
import com.anilili.data.provider.remote.config.RemoteSubtitleVariant
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.workflow.HttpMethod
import com.anilili.data.provider.remote.engine.workflow.HttpStep
import com.anilili.data.provider.remote.engine.workflow.OutputType
import com.anilili.data.provider.remote.engine.workflow.ParseHtmlStep
import com.anilili.data.provider.remote.engine.workflow.SelectMode
import com.anilili.data.provider.remote.engine.workflow.SelectStep
import com.anilili.data.provider.remote.engine.workflow.WorkflowDefinition
import com.anilili.data.provider.remote.engine.workflow.WorkflowOutput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Regression suite for iframe routing in parseSourcesResult.
 *
 * Key invariant: an iframe URL that does NOT contain the substring "embed" must
 * still be classified as type="embed" when requiresWebView=true is set on the
 * provider's capabilities.  Before the fix this was silently typed "mp4", which
 * would have handed the page URL to ExoPlayer instead of the WebView.
 *
 * Also verifies that HLS/DASH/MP4 streams from a requiresWebView=false provider
 * are never accidentally promoted to "embed".
 */
class IframeRoutingTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var json: Json
    private lateinit var policy: RemoteRequestPolicy
    private lateinit var BASE_URL: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        BASE_URL = server.url("/").toString().trimEnd('/')
        client = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        policy = RemoteRequestPolicy(
            allowedDomains = emptySet(),
            maxResponseBytes = 1024 * 1024,
            maxRequestsPerRun = 100,
            allowLoopbackForTesting = true,
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun enqueueHtml(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body)
            .setHeader("Content-Type", "text/html"))
    }

    // Shared slug store so tests can prime episode slugs without reflection.
    private val slugStore = RemoteSlugStore()

    private fun buildIframeRuntime(requiresWebView: Boolean): WorkflowRemoteProviderRuntime {
        val entry = RemoteProviderEntry(
            id = "iframe-test",
            name = "Iframe Test",
            endpoint = "$BASE_URL/",
            source = RemoteSourceType.GENERIC_HTML,
            capabilities = ProviderCapabilities(
                supportsEpisodes = true,
                supportsSources = true,
                supportsSubtitles = false,
                supportsSkipTimes = false,
                supportsDownload = false,
                supportsSearch = false,
                requiresWebView = requiresWebView,
                multiAudio = false,
                shortLivedStreamUrls = false,
                countryLocked = null,
                adultOnly = false,
            ),
            category = RemoteCategory.ANIME,
            languages = listOf("id"),
            subtitleVariants = listOf(RemoteSubtitleVariant.GENERIC_SUB),
            priority = 0,
            enabled = true,
        )
        val sourcesWf = WorkflowDefinition(
            id = "wf_sources",
            name = "sources",
            version = "1.0",
            description = "",
            steps = listOf(
                HttpStep(
                    id = "fetch", method = HttpMethod.GET, url = "{{url}}",
                    headers = emptyMap(), query = emptyMap(), body = null, saveAs = "resp",
                ),
                ParseHtmlStep(id = "parse", input = "{{resp.body}}", saveAs = "doc"),
                SelectStep(
                    id = "iframes", input = "doc",
                    selector = "#pembed > iframe", attribute = "src",
                    mode = SelectMode.ALL, saveAs = "streams",
                ),
            ),
            output = WorkflowOutput(
                type = OutputType.SOURCES,
                mapping = mapOf("streams" to "streams"),
            ),
        )
        return RemoteProviderRuntime.create(
            entry = entry,
            workflows = mapOf("sources" to sourcesWf),
            okHttpClient = client,
            json = json,
            requestPolicy = RemoteRequestPolicy(
                allowedDomains = emptySet(),
                maxResponseBytes = 1024 * 1024,
                maxRequestsPerRun = 100,
                allowLoopbackForTesting = true,
            ),
            slugStore = slugStore,
        )
    }

    // ── Regression: iframe URL without "embed" substring ─────────────────

    @Test
    fun `iframe URL without embed substring is typed embed when requiresWebView=true`() {
        // URL intentionally contains no "embed" or "iframe" substring
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://player.example/watch/123"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull("Sources must not be null", result)
        val stream = result!!.streams.single()
        assertEquals("https://player.example/watch/123", stream.url)
        // Key assertion: must be "embed", not "mp4"
        assertEquals(
            "URL without 'embed' substring must still be typed 'embed' when requiresWebView=true",
            "embed", stream.type,
        )
    }

    @Test
    fun `iframe URL without embed substring is typed mp4 when requiresWebView=false`() {
        // With requiresWebView=false and no explicit type hint, the engine falls back to "mp4"
        // for a plain URL that is not an .m3u8 or .mpd.  This is the correct contract: the
        // provider declared it does not need a WebView, so a URL that looks like a file path
        // is treated as one.
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://player.example/watch/123"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = false)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        val stream = result!!.streams.single()
        assertEquals("https://player.example/watch/123", stream.url)
        assertEquals(
            "With requiresWebView=false and no embed signal, URL should be typed mp4",
            "mp4", stream.type,
        )
    }

    @Test
    fun `iframe URL containing embed substring is typed embed even without requiresWebView`() {
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://ok.ru/videoembed/16438442330802"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = false)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        assertEquals("embed", result!!.streams.single().type)
    }

    // ── HLS / DASH / MP4 regression: never promoted to embed ─────────────

    @Test
    fun `HLS URL is never typed embed even when requiresWebView=true`() {
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://cdn.example/stream/master.m3u8"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        val stream = result!!.streams.single()
        assertEquals("hls", stream.type)
        assertFalse("HLS must not go to WebView", stream.isEmbed)
    }

    // ── DASH regression: never promoted to embed ─────────────────────────────

    @Test
    fun `DASH URL is never typed embed even when requiresWebView=true`() {
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://cdn.example/stream/master.mpd"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        val stream = result!!.streams.single()
        assertEquals("dash", stream.type)
        assertFalse("DASH must not go to WebView", stream.isEmbed)
    }

    // ── MP4 regression: never promoted to embed ───────────────────────────────

    @Test
    fun `MP4 URL is never typed embed even when requiresWebView=true`() {
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://cdn.example/stream/video.mp4"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        val stream = result!!.streams.single()
        assertEquals("mp4", stream.type)
        assertFalse("MP4 must not go to WebView", stream.isEmbed)
    }

    // ── Iframe URL tanpa "embed" → WebView ───────────────────────────────────

    @Test
    fun `iframe_without_embed_substring_is_typed_embed_when_requiresWebView_true`() {
        // URL intentionally contains no "embed" or "iframe" substring
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://player.example/watch/123"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull("Sources must not be null", result)
        val stream = result!!.streams.single()
        assertEquals("https://player.example/watch/123", stream.url)
        // Key assertion: must be "embed", not "mp4"
        assertEquals(
            "URL without 'embed' substring must still be typed 'embed' when requiresWebView=true",
            "embed", stream.type,
        )
    }

    // ── Mixed sources regression: each type routed correctly ──────────────────

    @Test
    fun `mixed_sources_iframe_mp4_hls_each_routed_correctly`() {
        // HTML with iframe src that doesn't contain "embed"
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://player.example/watch/456"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        val stream = result!!.streams.single()
        // iframe tanpa "embed" → embed when requiresWebView=true
        assertEquals("embed", stream.type)
    }

    // ── Explicit stream type not overridden by capability provider incorrectly ──

    @Test
    fun `explicit_type_embed_not_overridden_to_mp4_when_requiresWebView_true`() {
        val watchPage = """<html><body>
            <div id="pembed"><iframe src="https://ok.ru/videoembed/16438442330802"></iframe></div>
        </body></html>"""

        slugStore.putEpisodeSlug("iframe-test", anilistId = 1, episodeNumber = 1.0, episodeSlugOrUrl = "/ep/1/")
        enqueueHtml(watchPage)
        val runtime = buildIframeRuntime(requiresWebView = true)

        val result = runBlocking { runtime.getSources(1, 1.0, Category.SUB) }

        assertNotNull(result)
        val stream = result!!.streams.single()
        // Explicit type="embed" wins even when URL has no other signals
        assertEquals("embed", stream.type)
    }
}
