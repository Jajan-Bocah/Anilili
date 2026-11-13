package com.anilili.data.provider.remote.runtime

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search -> Episodes -> Sources through the real Kotlin engine with the official Anichin fixtures.
 * HTTP is served by an interceptor that only knows the Anichin pages; every request URL is recorded
 * and asserted, so a request to an empty URL or to the series page instead of the watch page fails.
 */
class WorkflowRemoteProviderRuntimeFixtureTest {

    private val fx = File("src/test/resources/fixtures/anichin")
    private val series = "https://anichin.moe/quanzhi-fashi-season-7/"
    private val ep57 = "/oyen-season-5-episode-57-subtitle-indonesia/"
    private val ep153 = "/btth-season-5-episode-153-subtitle-indonesia/"
    private val ep153Dup = "/oyen-pertempuran-akhir-sekte-misty-cloud-episode-01-53-sub-indo/"
    private val iframe = "https://ok.ru/videoembed/16438442330802"

    private val configs = listOf(
        "generator" to File(fx, "generator-provider.json"),
        "official" to File("src/main/assets/remote-provider-configs/anichin.json"),
    )

    private class Env(val name: String, val runtime: WorkflowRemoteProviderRuntime, val store: RemoteSlugStore, val requests: MutableList<String>)

    private fun env(name: String, configFile: File): Env {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            requests += req.url.toString()
            val path = req.url.encodedPath
            val page = when {
                req.url.host != "anichin.moe" -> throw AssertionError("unexpected host: ${req.url}")
                path == "/" -> "search.htm"
                req.url.toString() == series -> "series.htm"
                path in setOf(ep57, ep153, ep153Dup, "/x", "/x/") -> "watch.htm"
                else -> throw AssertionError("unexpected request: ${req.url}")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(File(fx, page).readText().toResponseBody("text/html".toMediaType())).build()
        }).build()
        val config = Json.decodeFromString(RemoteProviderConfig.serializer(), configFile.readText())
        val store = RemoteSlugStore()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val runtime = RemoteProviderRuntimeFactory(client, json, store).createRuntime(config.providers[0], config)!!
        return Env(name, runtime, store, requests)
    }

    private fun forEachConfig(block: (Env) -> Unit) = configs.forEach { (name, file) ->
        try { block(env(name, file)) } catch (e: Throwable) { throw AssertionError("config=$name: ${e.message}", e) }
    }

    // The official config selects the 12 base64 mirror options (no per-item decode in the engine);
    // only the generator config yields the single plain iframe.
    private fun assertIframe(e: Env, res: com.anilili.data.model.SourcesResult?) {
        if (e.name != "generator") { assertTrue(res!!.streams.isNotEmpty()); return }
        assertEquals(listOf(iframe), res!!.streams.map { it.url })
        assertEquals("embed", res.streams.single().type)
    }

    private fun Env.episodes() = runBlocking { runtime.getEpisodes(123, "Quanzhi Fashi Season 7") }!!.providers.single().sub

    @Test
    fun `search gives 2 items and episodes gives 166`() = forEachConfig { e ->
        assertEquals(2, runBlocking { e.runtime.search("quanzhi") }.size)
        assertEquals(166, e.episodes().size)
        assertTrue("series page requested: ${e.requests}", series in e.requests)
    }

    @Test
    fun `getSourcesForPipeId requests the episode watch url and yields the iframe`() = forEachConfig { e ->
        val pipe = e.episodes().first { it.number == 57.0 }.pipeId
        e.requests.clear()

        val res = runBlocking { e.runtime.getSourcesForPipeId(pipe, 123, SourceVariant.GENERIC_SUB) }

        assertEquals(listOf("https://anichin.moe$ep57"), e.requests)
        assertIframe(e, res)
    }

    @Test
    fun `getSources by number requests the episode url, not the series page`() = forEachConfig { e ->
        e.episodes()
        e.requests.clear()

        val res = runBlocking { e.runtime.getSources(123, 57.0, Category.SUB) }

        assertEquals(listOf("https://anichin.moe$ep57"), e.requests)
        assertIframe(e, res)
    }

    @Test
    fun `getSources without a known episode makes no request`() = forEachConfig { e ->
        assertNull(runBlocking { e.runtime.getSources(123, 57.0, Category.SUB) })
        assertTrue(e.requests.isEmpty())
    }

    @Test
    fun `duplicate episode number keeps the genuine slug and each pipeId hits its own url`() = forEachConfig { e ->
        val dups = e.episodes().filter { it.number == 153.0 }
        assertEquals(2, dups.size)
        assertEquals(ep153, e.store.getEpisodeSlug("anichin", 123, 153.0))
        e.requests.clear()

        dups.forEach { runBlocking { e.runtime.getSourcesForPipeId(it.pipeId, 123, SourceVariant.GENERIC_SUB) } }

        assertEquals(dups.map { "https://anichin.moe" + it.pipeId.substringAfter("/sub/") }.sorted(), e.requests.sorted())
        assertEquals(setOf("https://anichin.moe$ep153", "https://anichin.moe$ep153Dup"), e.requests.toSet())
    }

    @Test
    fun `pipeId forms resolve to the right url`() {
        val ok = "https://anichin.moe/x"
        val cases = listOf(
            "remote/anichin/123/sub/x" to listOf(ok),                          // bare relative slug
            "remote/anichin/123/sub//x" to listOf(ok),                         // leading slash
            "remote/anichin/123/sub//x/" to listOf("$ok/"),                    // leading + trailing slash (generator form)
            "remote/anichin/123/sub/x/" to listOf("$ok/"),                     // trailing slash
            "remote/anichin/123/sub/https://anichin.moe/x/" to listOf("$ok/"), // absolute, same host
            "remote/anichin/123/sub/https://evil.example/x/" to emptyList(),   // absolute, other host refused
            "remote/anichin/123/sub///evil.example/x" to emptyList(),          // protocol-relative, other host refused
        )
        forEachConfig { e ->
            cases.forEach { (pipe, want) ->
                e.requests.clear()
                runBlocking { e.runtime.getSourcesForPipeId(pipe, 123, SourceVariant.GENERIC_SUB) }
                assertEquals("pipe=$pipe", want, e.requests)
            }
        }
    }

    @Test
    fun `empty or numeric-without-cache pipeId makes no request`() = forEachConfig { e ->
        listOf("remote/anichin/123/sub/", "remote/anichin/123/sub//", "remote/anichin/123/sub/57", "").forEach {
            assertNull("pipe=$it", runBlocking { e.runtime.getSourcesForPipeId(it, 123, SourceVariant.GENERIC_SUB) })
        }
        assertTrue(e.requests.isEmpty())
    }

    @Test
    fun `legacy numeric pipeId uses the cached episode url`() = forEachConfig { e ->
        e.episodes()
        e.requests.clear()
        listOf("remote/anichin/123/sub/57", "remote/anichin/123/sub/57.0").forEach {
            runBlocking { e.runtime.getSourcesForPipeId(it, 123, SourceVariant.GENERIC_SUB) }
        }
        assertEquals(List(2) { "https://anichin.moe$ep57" }, e.requests)
    }

    @Test
    fun `parseRemotePipeId keeps every slash after the category`() {
        assertEquals("/x/", parseRemotePipeId("remote/anichin/123/sub//x/"))
        assertEquals("x", parseRemotePipeId("remote/anichin/123/sub/x"))
        assertEquals("a/b/c?d=1", parseRemotePipeId("remote/anichin/123/sub/a/b/c?d=1"))
        assertEquals("https://h/x/", parseRemotePipeId("remote/anichin/123/sub/https://h/x/"))
        assertEquals("57", parseRemotePipeId("remote/anichin/123/sub/57"))
        assertNull(parseRemotePipeId("remote/anichin/123/sub/"))
        assertNull(parseRemotePipeId(""))
    }
}
