package com.anilili.data.provider.remote.engine.workflow

import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.workflow.HttpResponse
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
 * Response-size enforcement tests:
 * 1. Large Content-Length declared > limit → request fails before body is read
 * 2. Chunked response exceeding limit without Content-Length → stream read is bounded, no OOM
 * 3. Response within limit → succeeds normally
 *
 * All served by MockWebServer; no emulator, no live network.
 */
class ResponseSizeLimitTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var json: Json

    private val MAX_BYTES = 1024L // 1 KB limit for test

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun policy(maxBytes: Long = MAX_BYTES): RemoteRequestPolicy {
        return RemoteRequestPolicy(
            allowedDomains = emptySet(),
            maxResponseBytes = maxBytes,
            maxRequestsPerRun = 100,
            allowLoopbackForTesting = true, // MockWebServer binds to localhost
        )
    }

    private fun executor(policy: RemoteRequestPolicy): WorkflowExecutor {
        return WorkflowExecutor(client, policy)
    }

    // ── 1. Large declared Content-Length ──────────────────────────────

    @Test
    fun `response with content-length exceeding limit fails before body read`() {
        // Declare 2 MB but policy limit is 1 KB
        val largeBody = "A".repeat(2 * 1024 * 1024)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(largeBody)
                .setHeader("Content-Length", largeBody.length.toString()),
        )

        val exec = executor(policy(MAX_BYTES))
        val wf = WorkflowDefinition(
            id = "wf_size_test",
            name = "size_test",
            version = "1.0",
            steps = listOf(
                HttpStep(
                    id = "http_fetch",
                    method = HttpMethod.GET,
                    url = server.url("/large").toString(),
                    saveAs = "response",
                ),
            ),
            output = WorkflowOutput(
                type = OutputType.RAW,
                mapping = mapOf("body" to "response.body"),
            ),
        )

        val result = runBlocking { exec.execute(wf, emptyMap(), "wf_size_large") }

        assertFalse("Response exceeding declared content-length limit should fail", result.success)
        assertNotNull("Error should be present", result.error)
        assertTrue("Error should mention size limit",
            result.error!!.contains("exceeds limit", ignoreCase = true) || result.error!!.contains("limit", ignoreCase = true))
    }

    // ── 2. Chunked response exceeding limit without Content-Length ─────

    @Test
    fun `chunked response exceeding limit is bounded and fails without OOM`() {
        // Chunked encoding: no Content-Length header; body streams 2 MB in chunks
        val chunkBody = "B".repeat(2 * 1024 * 1024)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(chunkBody)
                .removeHeader("Content-Length"), // chunked transfer: no Content-Length header
        )

        val exec = executor(policy(MAX_BYTES))
        val wf = WorkflowDefinition(
            id = "wf_chunked_test",
            name = "chunked_test",
            version = "1.0",
            steps = listOf(
                HttpStep(
                    id = "http_fetch",
                    method = HttpMethod.GET,
                    url = server.url("/chunked").toString(),
                    saveAs = "response",
                ),
            ),
            output = WorkflowOutput(
                type = OutputType.RAW,
                mapping = mapOf("body" to "response.body"),
            ),
        )

        val result = runBlocking { exec.execute(wf, emptyMap(), "wf_size_chunked") }

        assertFalse("Chunked response exceeding stream limit should fail", result.success)
        assertNotNull("Error should be present", result.error)
        assertTrue("Error should mention stream size limit",
            result.error!!.contains("exceeded policy limit", ignoreCase = true) || result.error!!.contains("size", ignoreCase = true))
    }

    // ── 3. Response within limit succeeds ─────────────────────────────

    @Test
    fun `response within size limit succeeds and returns body`() {
        val smallBody = "{\"ok\":true}"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(smallBody)
                .setHeader("Content-Length", smallBody.length.toString()),
        )

        val exec = executor(policy(MAX_BYTES))
        val wf = WorkflowDefinition(
            id = "wf_size_ok",
            name = "size_ok",
            version = "1.0",
            steps = listOf(
                HttpStep(
                    id = "http_fetch",
                    method = HttpMethod.GET,
                    url = server.url("/small").toString(),
                    saveAs = "response",
                ),
            ),
            output = WorkflowOutput(
                type = OutputType.RAW,
                mapping = mapOf("body" to "response.body"),
            ),
        )

        val result = runBlocking { exec.execute(wf, emptyMap(), "wf_size_ok") }

        assertTrue("Response within limit should succeed: ${result.error}", result.success)
        val output = (result.output as? Map<String, Any?>)!!
        assertNotNull("Output should be a map", output)
        val response = output["response"] as? HttpResponse
        assertNotNull("Response should be present", response)
        assertEquals("{\"ok\":true}", response!!.body)
    }

    // ── 4. Response exactly at limit succeeds ─────────────────────────

    @Test
    fun `response exactly at limit succeeds`() {
        val bodyAtLimit = "C".repeat(MAX_BYTES.toInt())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(bodyAtLimit)
                .setHeader("Content-Length", bodyAtLimit.length.toString()),
        )

        val exec = executor(policy(MAX_BYTES))
        val wf = WorkflowDefinition(
            id = "wf_size_exact",
            name = "size_exact",
            version = "1.0",
            steps = listOf(
                HttpStep(
                    id = "http_fetch",
                    method = HttpMethod.GET,
                    url = server.url("/exact").toString(),
                    saveAs = "response",
                ),
            ),
            output = WorkflowOutput(
                type = OutputType.RAW,
                mapping = mapOf("body" to "response.body"),
            ),
        )

        val result = runBlocking { exec.execute(wf, emptyMap(), "wf_size_exact") }

        assertTrue("Response exactly at limit should succeed: ${result.error}", result.success)
    }
}
