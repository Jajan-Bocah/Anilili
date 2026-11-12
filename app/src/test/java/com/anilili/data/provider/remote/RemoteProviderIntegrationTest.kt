package com.anilili.data.provider.remote

import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import com.anilili.data.provider.remote.config.ConfigMetadata
import com.anilili.data.provider.remote.config.ProviderCapabilities
import com.anilili.data.provider.remote.config.RemoteCategory
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderConfigValidator
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.config.RemoteProviderList
import com.anilili.data.provider.remote.config.RemoteSourceType
import com.anilili.data.provider.remote.config.RemoteSubtitleVariant
import com.anilili.data.provider.remote.config.WorkflowOutputConfig
import com.anilili.data.provider.remote.config.WorkflowStepConfig
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.workflow.HttpMethod
import com.anilili.data.provider.remote.engine.workflow.HttpStep
import com.anilili.data.provider.remote.engine.workflow.NormalizeStep
import com.anilili.data.provider.remote.engine.workflow.ParseHtmlStep
import com.anilili.data.provider.remote.engine.workflow.SelectStep
import com.anilili.data.provider.remote.engine.workflow.SelectMode
import com.anilili.data.provider.remote.engine.workflow.TemplateEvaluator
import com.anilili.data.provider.remote.engine.workflow.WorkflowContext
import com.anilili.data.provider.remote.engine.workflow.WorkflowDefinition
import com.anilili.data.provider.remote.engine.workflow.WorkflowExecutor
import com.anilili.data.provider.remote.engine.workflow.WorkflowOutput
import com.anilili.data.provider.remote.engine.workflow.OutputType
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntime
import com.anilili.data.provider.remote.runtime.WorkflowRemoteProviderRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * MockWebServer E2E integration test covering:
 * Install → Search → Match → Episodes → Servers → Sources
 *
 * No emulator, no live network. All HTTP is served by a local MockWebServer.
 */
class RemoteProviderIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var json: Json
    private lateinit var policy: RemoteRequestPolicy
    private lateinit var BASE_URL: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        BASE_URL = server.url("/").toString()
        client = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; isLenient = true }
        policy = RemoteRequestPolicy(
            allowedDomains = emptySet(),
            maxResponseBytes = 1024 * 1024,
            maxRequestsPerRun = 100,
            allowLoopbackForTesting = true, // MockWebServer binds to localhost
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun enqueueHtml(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .setHeader("Content-Type", "text/html"),
        )
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .setHeader("Content-Type", "application/json"),
        )
    }

    private fun buildConfig(baseUrl: String): RemoteProviderConfig {
        return RemoteProviderConfig(
            schemaVersion = 1,
            providers = listOf(
                RemoteProviderEntry(
                    id = "test-provider",
                    name = "Test Provider",
                    endpoint = baseUrl,
                    source = RemoteSourceType.GENERIC_HTML,
                    capabilities = ProviderCapabilities(
                        supportsEpisodes = true,
                        supportsSources = true,
                        supportsSubtitles = false,
                        supportsSkipTimes = false,
                        supportsDownload = false,
                        supportsSearch = true,
                        requiresWebView = false,
                        multiAudio = false,
                        shortLivedStreamUrls = false,
                        countryLocked = null,
                        adultOnly = false,
                    ),
                    category = RemoteCategory.ANIME,
                    languages = listOf("id"),
                    subtitleVariants = listOf(RemoteSubtitleVariant.GENERIC_SUB),
                    priority = 100,
                    enabled = true,
                )
            ),
            metadata = ConfigMetadata(
                name = "Test Provider Config",
                description = "E2E test config",
                author = "test",
                version = "1.0",
                sourceUrl = "$baseUrl/provider.json",
                fetchedAt = System.currentTimeMillis(),
                checksum = null,
            ),
            security = RemoteRequestPolicy.SecurityConfig(
                allowedDomains = emptyList(),
                maxResponseBytes = 1024 * 1024,
                maxRequestsPerRun = 20,
                connectTimeoutMs = 10000,
                readTimeoutMs = 15000,
                totalTimeoutMs = 30000,
                allowRedirects = true,
                maxRedirects = 5,
            ),
            workflows = mapOf(
                "search" to buildSearchConfig(baseUrl),
                "episodes" to buildEpisodesConfig(),
                "sources" to buildSourcesConfig(),
            ),
        )
    }

    private fun buildSearchConfig(baseUrl: String): com.anilili.data.provider.remote.config.RemoteWorkflowConfig {
        return com.anilili.data.provider.remote.config.RemoteWorkflowConfig(
            name = "search",
            version = "1.0",
            description = "Search test provider",
            steps = listOf(
                WorkflowStepConfig(
                    op = "http",
                    id = "search_fetch",
                    params = mapOf(
                        "method" to "GET",
                        "url" to "$baseUrl/",
                        "query" to "{\"s\":\"{{query}}\"}",
                    ),
                    saveAs = "search_response",
                ),
                WorkflowStepConfig(
                    op = "parse_html",
                    id = "search_parse",
                    params = mapOf("input" to "{{search_response.body}}"),
                    saveAs = "search_document",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "search_titles",
                    params = mapOf(
                        "input" to "search_document",
                        "selector" to ".listupd article.bs a.tip",
                        "attribute" to "title",
                        "mode" to "ALL",
                    ),
                    saveAs = "search_titles",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "search_links",
                    params = mapOf(
                        "input" to "search_document",
                        "selector" to ".listupd article.bs a.tip",
                        "attribute" to "href",
                        "mode" to "ALL",
                    ),
                    saveAs = "search_urls",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "search_images",
                    params = mapOf(
                        "input" to "search_document",
                        "selector" to ".listupd article.bs a.tip img",
                        "attribute" to "src",
                        "mode" to "ALL",
                    ),
                    saveAs = "search_images",
                ),
            ),
            output = WorkflowOutputConfig(
                type = "SEARCH",
                mapping = mapOf(
                    "titles" to "search_titles",
                    "urls" to "search_urls",
                    "images" to "search_images",
                ),
            ),
        )
    }

    private fun buildEpisodesConfig(): com.anilili.data.provider.remote.config.RemoteWorkflowConfig {
        return com.anilili.data.provider.remote.config.RemoteWorkflowConfig(
            name = "episodes",
            version = "1.0",
            description = "Episodes test provider",
            steps = listOf(
                WorkflowStepConfig(
                    op = "http",
                    id = "series_page_fetch",
                    params = mapOf("method" to "GET", "url" to "{{url}}"),
                    saveAs = "series_page_response",
                ),
                WorkflowStepConfig(
                    op = "parse_html",
                    id = "series_page_parse",
                    params = mapOf("input" to "{{series_page_response.body}}"),
                    saveAs = "series_document",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "episode_urls",
                    params = mapOf(
                        "input" to "series_document",
                        "selector" to ".eplister li a",
                        "attribute" to "href",
                        "mode" to "ALL",
                    ),
                    saveAs = "episode_urls",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "episode_numbers",
                    params = mapOf(
                        "input" to "series_document",
                        "selector" to ".eplister li .epl-num",
                        "mode" to "ALL",
                    ),
                    saveAs = "episode_numbers",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "episode_titles",
                    params = mapOf(
                        "input" to "series_document",
                        "selector" to ".eplister li .epl-title",
                        "mode" to "ALL",
                    ),
                    saveAs = "episode_titles",
                ),
            ),
            output = WorkflowOutputConfig(
                type = "EPISODES",
                mapping = mapOf(
                    "episodeUrls" to "episode_urls",
                    "episodeNumbers" to "episode_numbers",
                    "episodeTitles" to "episode_titles",
                ),
            ),
        )
    }

    private fun buildSourcesConfig(): com.anilili.data.provider.remote.config.RemoteWorkflowConfig {
        return com.anilili.data.provider.remote.config.RemoteWorkflowConfig(
            name = "sources",
            version = "1.0",
            description = "Sources test provider",
            steps = listOf(
                WorkflowStepConfig(
                    op = "http",
                    id = "watch_fetch",
                    params = mapOf("method" to "GET", "url" to "{{url}}"),
                    saveAs = "watch_response",
                ),
                WorkflowStepConfig(
                    op = "parse_html",
                    id = "watch_parse",
                    params = mapOf("input" to "{{watch_response.body}}"),
                    saveAs = "watch_doc",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "mirror_options",
                    params = mapOf(
                        "input" to "watch_doc",
                        "selector" to "select.mirror option",
                        "attribute" to "value",
                        "mode" to "ALL",
                    ),
                    saveAs = "raw_mirrors",
                ),
                WorkflowStepConfig(
                    op = "select",
                    id = "mirror_names",
                    params = mapOf(
                        "input" to "watch_doc",
                        "selector" to "select.mirror option",
                        "mode" to "ALL",
                    ),
                    saveAs = "mirror_labels",
                ),
            ),
            output = WorkflowOutputConfig(
                type = "SOURCES",
                mapping = mapOf(
                    "streams" to "raw_mirrors",
                    "servers" to "mirror_labels",
                ),
            ),
        )
    }

    private fun buildExecutor(): WorkflowExecutor {
        return WorkflowExecutor(client, policy)
    }

    private fun buildRuntime(config: RemoteProviderConfig): WorkflowRemoteProviderRuntime {
        // Validation is skipped here because the test config uses MockWebServer (HTTP loopback)
        // which the production validator correctly rejects for production use.
        val entry = config.providers.first()
        val runtimePolicy = RemoteRequestPolicy(
            allowedDomains = emptySet(),
            maxResponseBytes = 1024 * 1024,
            maxRequestsPerRun = 100,
            allowLoopbackForTesting = true, // MockWebServer binds to localhost
        )
        val workflows = config.workflows!!.mapValues { (_, wf) -> workflowToEngine(wf) }
        return RemoteProviderRuntime.create(entry, workflows, client, json, runtimePolicy)
    }

    private fun workflowToEngine(
        wf: com.anilili.data.provider.remote.config.RemoteWorkflowConfig,
    ): WorkflowDefinition {
        return WorkflowDefinition(
            id = "wf_${wf.name}",
            name = wf.name ?: "",
            version = wf.version ?: "1.0",
            description = wf.description ?: "",
            steps = wf.steps!!.map { sc ->
                when (sc.op) {
                    "http" -> HttpStep(
                        id = sc.id ?: "http_step",
                        method = HttpMethod.valueOf(sc.params["method"]?.uppercase() ?: "GET"),
                        url = sc.params["url"] ?: "",
                        headers = sc.params.getOrDefault("headers", "{}").let { json.decodeFromString(it) },
                        query = sc.params.getOrDefault("query", "{}").let { json.decodeFromString(it) },
                        body = sc.params["body"],
                        saveAs = sc.saveAs,
                    )
                    "parse_html" -> ParseHtmlStep(
                        id = sc.id ?: "parse_html",
                        input = sc.params["input"] ?: "",
                        saveAs = sc.saveAs,
                    )
                    "select" -> SelectStep(
                        id = sc.id ?: "select",
                        input = sc.params["input"] ?: "",
                        selector = sc.params["selector"] ?: "",
                        attribute = sc.params["attribute"],
                        mode = SelectMode.valueOf(sc.params["mode"]?.uppercase() ?: "FIRST"),
                        saveAs = sc.saveAs,
                    )
                    else -> throw IllegalArgumentException("Unsupported op: ${sc.op}")
                }
            },
            output = WorkflowOutput(
                type = OutputType.valueOf(wf.output?.type?.uppercase() ?: "RAW"),
                mapping = wf.output?.mapping ?: emptyMap(),
            ),
        )
    }

    // ── Test data ────────────────────────────────────────────────────────

    private val SEARCH_HTML = """
        <html><body>
        <div class="listupd">
            <article class="bs">
                <a class="tip" href="/anime/naruto" title="Naruto">
                    <img src="https://anichin.test/img/naruto.jpg"/>
                </a>
            </article>
            <article class="bs">
                <a class="tip" href="/anime/bleach" title="Bleach">
                    <img src="https://anichin.test/img/bleach.jpg"/>
                </a>
            </article>
        </div>
        </body></html>
    """.trimIndent()

    private val SERIES_HTML = """
        <html><body>
        <ul class="eplister">
            <li><a href="/anime/naruto/episode-1"><span class="epl-num">1</span><span class="epl-title">Pilot</span></a></li>
            <li><a href="/anime/naruto/episode-2"><span class="epl-num">2</span><span class="epl-title">Episode 2</span></a></li>
            <li><a href="/anime/naruto/episode-3"><span class="epl-num">3</span><span class="epl-title">Episode 3</span></a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val WATCH_HTML = """
        <html><body>
        <select class="mirror">
            <option value="https://ok.ru/video/123">OK.ru Mirror 1</option>
            <option value="https://mega.nz/file/456">Mega Mirror 2</option>
        </select>
        </body></html>
    """.trimIndent()

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `e2e engine search resolves titles urls and images from mock server`() {
        // Given
        enqueueHtml(SEARCH_HTML)
        val config = buildConfig(BASE_URL)
        val executor = buildExecutor()

        val wf = config.workflows!!.getValue("search")
        val engineWf = workflowToEngine(wf)

        // When
        val result = runBlocking { executor.execute(engineWf, mapOf("query" to "naruto"), "e2e_search") }

        // Then
        assertTrue("Search should succeed: ${result.error}", result.success)
        val output = (result.output as? Map<String, Any?>)!!
        assertNotNull("Search output should be a map", output)
        val titles = output["titles"] as? List<*>
        val urls = output["urls"] as? List<*>
        val images = output["images"] as? List<*>
        assertNotNull("titles should be present", titles)
        assertNotNull("urls should be present", urls)
        assertNotNull("images should be present", images)
        assertEquals(2, titles!!.size)
        assertEquals("Naruto", titles!![0])
        assertEquals("Bleach", titles!![1])
        assertEquals("/anime/naruto", urls!![0])
        assertEquals("/anime/bleach", urls!![1])
    }

    @Test
    fun `e2e engine episodes extracts numbered episode list`() {
        // Given
        enqueueHtml(SERIES_HTML)
        val config = buildConfig(BASE_URL)
        val executor = buildExecutor()

        val wf = config.workflows!!.getValue("episodes")
        val engineWf = workflowToEngine(wf)

        // When
        val result = runBlocking { executor.execute(
            engineWf,
            mapOf("url" to "$BASE_URL/anime/naruto", "anilistId" to 123, "title" to "Naruto"),
            "e2e_episodes",
        ) }

        // Then
        assertTrue("Episodes should succeed: ${result.error}", result.success)
        val output = (result.output as? Map<String, Any?>)!!
        assertNotNull("Episodes output should be a map", output)
        val episodeUrls = output["episodeUrls"] as? List<*>
        val episodeNumbers = output["episodeNumbers"] as? List<*>
        val episodeTitles = output["episodeTitles"] as? List<*>
        assertNotNull("episodeUrls should be present", episodeUrls)
        assertNotNull("episodeNumbers should be present", episodeNumbers)
        assertNotNull("episodeTitles should be present", episodeTitles)
        assertEquals(3, episodeUrls!!.size)
        assertEquals(3, episodeNumbers!!.size)
        assertEquals(3, episodeTitles!!.size)
        assertEquals("/anime/naruto/episode-1", episodeUrls!![0])
        assertEquals("1", episodeNumbers!![0])
        assertEquals("Pilot", episodeTitles!![0])
    }

    @Test
    fun `e2e engine sources extracts mirror streams and server names`() {
        // Given
        enqueueHtml(WATCH_HTML)
        val config = buildConfig(BASE_URL)
        val executor = buildExecutor()

        val wf = config.workflows!!.getValue("sources")
        val engineWf = workflowToEngine(wf)

        // When
        val result = runBlocking { executor.execute(
            engineWf,
            mapOf("url" to "$BASE_URL/anime/naruto/episode-1"),
            "e2e_sources",
        ) }

        // Then
        assertTrue("Sources should succeed: ${result.error}", result.success)
        val output = (result.output as? Map<String, Any?>)!!
        assertNotNull("Sources output should be a map", output)
        val streams = output["streams"] as? List<*>
        val servers = output["servers"] as? List<*>
        assertNotNull("streams should be present", streams)
        assertNotNull("servers should be present", servers)
        assertEquals(2, streams!!.size)
        assertEquals(2, servers!!.size)
        assertEquals("https://ok.ru/video/123", streams!![0])
        assertEquals("https://mega.nz/file/456", streams!![1])
        assertEquals("OK.ru Mirror 1", servers!![0])
        assertEquals("Mega Mirror 2", servers!![1])
    }

    @Test
    fun `e2e full pipeline runtime search match episodes sources`() {
        // Given
        // Request order: search -> getEpisodes (internal search + episode fetch) -> getSources
        // 4 requests: search, internal search, episode fetch, watch fetch
        enqueueHtml(SEARCH_HTML)   // 1. runtime.search("naruto")
        enqueueHtml(SEARCH_HTML)   // 2. internal search inside getEpisodes
        enqueueHtml(SERIES_HTML)   // 3. episode fetch
        enqueueHtml(WATCH_HTML)    // 4. watch/sources fetch

        val config = buildConfig(BASE_URL)
        val runtime = buildRuntime(config)

        // 1. Search
        val searchResults = runBlocking { runtime.search("naruto") }
        assertFalse("Search should return results", searchResults.isEmpty())
        assertEquals("Naruto", searchResults[0].title)
        assertEquals("/anime/naruto", searchResults[0].slugOrUrl)

        // 2. Match -> pick first result
        val best = searchResults.maxByOrNull { r ->
            com.anilili.data.provider.remote.runtime.AnimeTitleMatcher.calculateMatchScore("Naruto", r.title)
        }
        assertNotNull("Should have a match", best)
        val score = com.anilili.data.provider.remote.runtime.AnimeTitleMatcher.calculateMatchScore("Naruto", best!!.title)
        assertTrue("Match score should be >= 0.75", score >= 0.75)

        // 3. Episodes
        val episodes = runBlocking { runtime.getEpisodes(999, "Naruto") }
        assertNotNull("Episodes should not be null", episodes)
        assertFalse("Episode list should not be empty", episodes!!.providers.isEmpty())
        val providerData = episodes!!.providers.first()
        assertEquals("test-provider", providerData.name)
        assertFalse("Sub episodes should not be empty", providerData.sub.isEmpty())
        assertEquals(1.0, providerData.sub.first().number, 0.001)
        assertTrue("pipeId should carry provider id", providerData.sub.first().pipeId.startsWith("remote/test-provider/"))

        // 4. Sources
        val sources = runBlocking { runtime.getSources(999, 1.0, Category.SUB) }
        assertNotNull("Sources should not be null", sources)
        assertFalse("Streams should not be empty", sources!!.streams.isEmpty())
        assertEquals("https://ok.ru/video/123", sources!!.streams.first().url)
        assertEquals("embed", sources!!.streams.first().type)
    }

    @Test
    fun `e2e title matcher rejects season mismatch`() {
        val score = com.anilili.data.provider.remote.runtime.AnimeTitleMatcher.calculateMatchScore(
            "Attack on Titan Season 2",
            "Attack on Titan Season 1",
        )
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun `e2e title matcher accepts same title`() {
        val score = com.anilili.data.provider.remote.runtime.AnimeTitleMatcher.calculateMatchScore("Naruto", "Naruto")
        assertTrue("Score should be 1.0 for exact match", score >= 0.99)
    }
}
