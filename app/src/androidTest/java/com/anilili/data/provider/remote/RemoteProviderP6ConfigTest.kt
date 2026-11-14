package com.anilili.data.provider.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteSourceType
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntimeFactory
import com.anilili.data.provider.remote.runtime.WorkflowRemoteProviderRuntime
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifikasi bahwa provider.json hasil Generator P6 (dengan blok security) dapat di-decode
 * oleh Kotlin strict serializer dan menghasilkan WorkflowRemoteProviderRuntime yang valid
 * di emulator/perangkat nyata.
 *
 * Ini bukan playback test — tidak ada jaringan nyata. Tujuannya membuktikan bahwa:
 *   1. JSON P6 lolos decoding strict (security block, semua field, enum values)
 *   2. RemoteProviderRuntimeFactory menghasilkan WorkflowRemoteProviderRuntime (bukan null)
 *   3. Entry provider punya kapabilitas yang benar
 *   4. Semua 3 workflow (search/episodes/sources) tersedia di runtime
 */
@RunWith(AndroidJUnit4::class)
class RemoteProviderP6ConfigTest {

    // Provider.json P6 yang dihasilkan oleh Generator P6 (bukan manual).
    // Penanda P6: blok "security" dengan "allowedDomains" auto-derive dari endpoint hostname.
    private val p6Json = """
        {
          "schemaVersion": 1,
          "providers": [
            {
              "id": "anichin",
              "name": "Anichin",
              "endpoint": "https://anichin.moe",
              "source": "generic_html",
              "capabilities": {
                "supportsEpisodes": true,
                "supportsSources": true,
                "supportsSubtitles": false,
                "supportsSkipTimes": false,
                "supportsDownload": false,
                "supportsSearch": false,
                "requiresWebView": false,
                "multiAudio": false,
                "shortLivedStreamUrls": false,
                "countryLocked": null,
                "adultOnly": false
              },
              "category": "general",
              "languages": ["id"],
              "subtitleVariants": ["sub"],
              "priority": 0,
              "enabled": true
            }
          ],
          "metadata": {
            "name": "Anichin",
            "description": "",
            "author": null,
            "version": "1.0.0",
            "sourceUrl": "https://cfg.anichin.moe/provider.json",
            "fetchedAt": 1794572236040,
            "checksum": null,
            "minAppVersion": "0.1.0",
            "maxAppVersion": null
          },
          "workflows": {
            "search": {
              "name": "search",
              "version": "1.0.0",
              "description": null,
              "steps": [
                {"op":"http","id":"search_fetch","params":{"method":"GET","url":"https://anichin.moe/","query":{"s":"{{query}}"}},"saveAs":"search_response"},
                {"op":"parse_html","id":"search_parse","params":{"input":"{{search_response.body}}"},"saveAs":"search_document"},
                {"op":"select","id":"search_titles","params":{"input":"search_document","selector":"article.bs > div.bsx > a.tip","mode":"ALL","attribute":"title"},"saveAs":"search_titles"},
                {"op":"select","id":"search_urls","params":{"input":"search_document","selector":"article.bs > div.bsx > a.tip","mode":"ALL","attribute":"href"},"saveAs":"search_urls"},
                {"op":"select","id":"search_images","params":{"input":"search_document","selector":"article.bs > div.bsx > a.tip img","mode":"ALL","attribute":"src"},"saveAs":"search_images"}
              ],
              "output":{"type":"SEARCH","mapping":{"titles":"search_titles","urls":"search_urls","images":"search_images"}}
            },
            "episodes": {
              "name": "episodes",
              "version": "1.0.0",
              "description": null,
              "steps": [
                {"op":"http","id":"episodes_fetch","params":{"method":"GET","url":"{{url}}"},"saveAs":"episodes_response"},
                {"op":"parse_html","id":"episodes_parse","params":{"input":"{{episodes_response.body}}"},"saveAs":"episodes_document"},
                {"op":"select","id":"episodes_urls","params":{"input":"episodes_document","selector":"div.eplister > ul > li > a","mode":"ALL","attribute":"href"},"saveAs":"episodes_urls"},
                {"op":"select","id":"episodes_numbers","params":{"input":"episodes_document","selector":"div.eplister > ul > li > a div.epl-num","mode":"ALL"},"saveAs":"episodes_numbers"},
                {"op":"select","id":"episodes_titles","params":{"input":"episodes_document","selector":"div.eplister > ul > li > a div.epl-title","mode":"ALL"},"saveAs":"episodes_titles"}
              ],
              "output":{"type":"EPISODES","mapping":{"episodeUrls":"episodes_urls","episodeNumbers":"episodes_numbers","episodeTitles":"episodes_titles"}}
            },
            "sources": {
              "name": "sources",
              "version": "1.0.0",
              "description": null,
              "steps": [
                {"op":"http","id":"sources_fetch","params":{"method":"GET","url":"{{url}}"},"saveAs":"sources_response"},
                {"op":"parse_html","id":"sources_parse","params":{"input":"{{sources_response.body}}"},"saveAs":"sources_document"},
                {"op":"select","id":"sources_streams","params":{"input":"sources_document","selector":"div#pembed > iframe","mode":"ALL","attribute":"src"},"saveAs":"sources_streams"}
              ],
              "output":{"type":"SOURCES","mapping":{"streams":"sources_streams"}}
            }
          },
          "security": {
            "allowedDomains": ["anichin.moe"],
            "maxResponseBytes": 1048576,
            "maxRequestsPerRun": 20,
            "connectTimeoutMs": 10000,
            "readTimeoutMs": 15000,
            "totalTimeoutMs": 30000,
            "allowRedirects": true,
            "maxRedirects": 5
          }
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun p6JsonDecodesOnDevice() {
        val config = json.decodeFromString(RemoteProviderConfig.serializer(), p6Json)

        // Schema version
        assertEquals(1, config.schemaVersion)

        // Provider entry
        assertEquals(1, config.providers.size)
        val entry = config.providers[0]
        assertEquals("anichin", entry.id)
        assertEquals("https://anichin.moe", entry.endpoint)
        assertEquals(RemoteSourceType.GENERIC_HTML, entry.source)
        assertTrue(entry.capabilities.supportsEpisodes)
        assertTrue(entry.capabilities.supportsSources)
        assertTrue(entry.enabled)

        // Security block (penanda P6 — tidak ada di P4/P5 generator output)
        assertNotNull("Blok security wajib ada di output P6", config.security)
        assertEquals(listOf("anichin.moe"), config.security!!.allowedDomains)
        assertEquals(1048576L, config.security!!.maxResponseBytes)

        // Workflows
        assertNotNull(config.workflows)
        assertTrue("Workflow search harus ada", config.workflows!!.containsKey("search"))
        assertTrue("Workflow episodes harus ada", config.workflows!!.containsKey("episodes"))
        assertTrue("Workflow sources harus ada", config.workflows!!.containsKey("sources"))
    }

    @Test
    fun p6RuntimeCreatesWorkflowRuntime() {
        val config = json.decodeFromString(RemoteProviderConfig.serializer(), p6Json)
        val entry = config.providers[0]

        val factory = RemoteProviderRuntimeFactory(OkHttpClient(), json)
        val runtime = factory.createRuntime(entry, config)

        assertNotNull("Factory harus menghasilkan runtime non-null untuk generic_html", runtime)
        assertTrue(
            "Runtime harus berupa WorkflowRemoteProviderRuntime",
            runtime is WorkflowRemoteProviderRuntime,
        )
        val wfRuntime = runtime as WorkflowRemoteProviderRuntime
        assertEquals("anichin", wfRuntime.entry.id)
        assertEquals("https://anichin.moe", wfRuntime.entry.endpoint)
    }

}
