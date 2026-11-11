package com.anilili.data.provider.remote

import com.anilili.data.provider.remote.config.ConfigMetadata
import com.anilili.data.provider.remote.config.ProviderCapabilities
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderConfigValidator
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.config.RemoteSourceType
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy.SecurityConfig
import com.anilili.data.provider.remote.engine.workflow.TemplateEvaluator
import com.anilili.data.provider.remote.engine.workflow.WorkflowContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteProviderEngineTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `security policy enforces HTTPS only`() {
        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("example.com"),
        )
        val httpResult = policy.validateRequest("http://example.com/api", "wf-1")
        assertFalse(httpResult.allowed)
        assertTrue(httpResult.error?.contains("HTTPS") == true)

        val httpsResult = policy.validateRequest("https://example.com/api", "wf-1")
        assertTrue(httpsResult.allowed)
    }

    @Test
    fun `security policy blocks private and loopback IP addresses (SSRF protection)`() {
        val policy = RemoteRequestPolicy(
            allowedDomains = emptySet(),
        )
        val localhostResult = policy.validateRequest("https://127.0.0.1/api", "wf-ssrf")
        assertFalse(localhostResult.allowed)
        assertTrue(localhostResult.error?.contains("blocked") == true)

        val privateIp10 = policy.validateRequest("https://10.0.0.1/steal", "wf-ssrf")
        assertFalse(privateIp10.allowed)

        val privateIp192 = policy.validateRequest("https://192.168.1.1/admin", "wf-ssrf")
        assertFalse(privateIp192.allowed)
    }

    @Test
    fun `security policy enforces domain allowlist`() {
        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("trusted.com", "cdn.trusted.com"),
        )
        val allowed = policy.validateRequest("https://trusted.com/api", "wf-2")
        assertTrue(allowed.allowed)

        val subAllowed = policy.validateRequest("https://sub.trusted.com/api", "wf-2")
        assertTrue(subAllowed.allowed)

        val denied = policy.validateRequest("https://malicious.com/api", "wf-2")
        assertFalse(denied.allowed)
        assertTrue(denied.error?.contains("not in allowed domains") == true)
    }

    @Test
    fun `security policy limits request rate per workflow run`() {
        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("example.com"),
            maxRequestsPerRun = 3,
        )
        val wfId = "rate-limit-test"
        for (i in 1..3) {
            policy.incrementRequestCount(wfId)
        }
        val overLimit = policy.validateRequest("https://example.com/item", wfId)
        assertFalse(overLimit.allowed)
        assertTrue(overLimit.error?.contains("exceeded") == true)
    }

    @Test
    fun `template evaluator resolves variables and nested paths`() {
        val context = WorkflowContext()
        context.put("query", "naruto")
        context.put("user", mapOf("name" to "sakura", "id" to 42))
        context.put("episodes", listOf(mapOf("title" to "Pilot"), mapOf("title" to "Episode 2")))

        val simple = TemplateEvaluator.evaluate("https://api.com/search?q={{query}}", context)
        assertEquals("https://api.com/search?q=naruto", simple)

        val nested = TemplateEvaluator.evaluate("User: {{user.name}}", context)
        assertEquals("User: sakura", nested)

        val arrayIndexed = TemplateEvaluator.evaluate("Ep: {{episodes.0.title}}", context)
        assertEquals("Ep: Pilot", arrayIndexed)
    }

    @Test
    fun `config validator rejects schema version mismatch`() {
        val validator = RemoteProviderConfigValidator()
        val badConfig = RemoteProviderConfig(
            schemaVersion = 999, // unsupported
            providers = listOf(
                RemoteProviderEntry(
                    id = "test-provider",
                    name = "Test Provider",
                    endpoint = "https://example.com/api",
                    source = RemoteSourceType.GENERIC_JSON,
                    capabilities = ProviderCapabilities(),
                )
            ),
            metadata = ConfigMetadata(
                name = "Test",
                description = "Test description",
                author = "Anilili",
                version = "1.0",
                sourceUrl = "https://example.com/config.json",
                fetchedAt = System.currentTimeMillis(),
                checksum = null,
            ),
            security = SecurityConfig(allowedDomains = listOf("example.com")),
        )
        val result = validator.validate(badConfig, "https://example.com/config.json")
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("maximum supported version") })
    }

    @Test
    fun `config validator rejects non-https sourceUrl and endpoint`() {
        val validator = RemoteProviderConfigValidator()
        val httpConfig = RemoteProviderConfig(
            schemaVersion = 1,
            providers = listOf(
                RemoteProviderEntry(
                    id = "http-provider",
                    name = "Http Provider",
                    endpoint = "http://insecure.example.com/api",
                    source = RemoteSourceType.GENERIC_JSON,
                    capabilities = ProviderCapabilities(),
                )
            ),
            metadata = ConfigMetadata(
                name = "Insecure",
                description = "Test description",
                author = "Anilili",
                version = "1.0",
                sourceUrl = "http://insecure.example.com/config.json",
                fetchedAt = System.currentTimeMillis(),
                checksum = null,
            ),
            security = SecurityConfig(allowedDomains = listOf("insecure.example.com")),
        )
        val result = validator.validate(httpConfig, "http://insecure.example.com/config.json")
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("HTTPS") })
    }

    @Test
    fun `config validator detects duplicate provider IDs`() {
        val validator = RemoteProviderConfigValidator()
        val dupConfig = RemoteProviderConfig(
            schemaVersion = 1,
            providers = listOf(
                RemoteProviderEntry(
                    id = "same-id",
                    name = "P1",
                    endpoint = "https://example.com/1",
                    source = RemoteSourceType.GENERIC_JSON,
                    capabilities = ProviderCapabilities(),
                ),
                RemoteProviderEntry(
                    id = "same-id",
                    name = "P2",
                    endpoint = "https://example.com/2",
                    source = RemoteSourceType.GENERIC_JSON,
                    capabilities = ProviderCapabilities(),
                ),
            ),
            metadata = ConfigMetadata(
                name = "Dupes",
                description = "Test description",
                author = "Anilili",
                version = "1.0",
                sourceUrl = "https://example.com/config.json",
                fetchedAt = System.currentTimeMillis(),
                checksum = null,
            ),
            security = SecurityConfig(allowedDomains = listOf("example.com")),
        )
        val result = validator.validate(dupConfig, "https://example.com/config.json")
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Duplicate provider id", ignoreCase = true) })
    }

    @Test
    fun `verify anichin json asset parses and adheres to schema`() {
        val file = listOf(
            File("src/main/assets/remote-provider-configs/anichin.json"),
            File("app/src/main/assets/remote-provider-configs/anichin.json"),
            File("../app/src/main/assets/remote-provider-configs/anichin.json"),
        ).firstOrNull { it.exists() } ?: File("src/main/assets/remote-provider-configs/anichin.json")
        assertTrue("anichin.json must exist in assets: ${file.absolutePath}", file.exists())
        val content = file.readText()
        val config = json.decodeFromString<RemoteProviderConfig>(content)

        assertEquals(1, config.schemaVersion)
        assertEquals("anichin", config.providers.first().id)
        assertEquals("Anichin", config.metadata.name)
        assertNotNull(config.security)
        assertTrue(config.security!!.allowedDomains.contains("anichin.example.com"))

        // Check workflows present
        assertNotNull(config.workflows?.get("search"))
        assertNotNull(config.workflows?.get("episodes"))
        assertNotNull(config.workflows?.get("sources"))

        val validator = RemoteProviderConfigValidator()
        val validation = validator.validate(
            config.copy(metadata = config.metadata.copy(fetchedAt = System.currentTimeMillis())),
            config.metadata.sourceUrl
        )
        assertTrue("anichin.json schema validation failed: ${validation.errors}", validation.isValid)
    }

    @Test
    fun `verify kuramanime json asset parses and adheres to schema`() {
        val file = listOf(
            File("src/main/assets/remote-provider-configs/kuramanime.json"),
            File("app/src/main/assets/remote-provider-configs/kuramanime.json"),
            File("../app/src/main/assets/remote-provider-configs/kuramanime.json"),
        ).firstOrNull { it.exists() } ?: File("src/main/assets/remote-provider-configs/kuramanime.json")
        assertTrue("kuramanime.json must exist in assets: ${file.absolutePath}", file.exists())
        val content = file.readText()
        val config = json.decodeFromString<RemoteProviderConfig>(content)

        assertEquals(1, config.schemaVersion)
        assertEquals("kuramanime", config.providers.first().id)
        assertEquals("Kuramanime", config.metadata.name)
        assertNotNull(config.security)
        assertTrue(config.security!!.allowedDomains.contains("kuramanime.example.com"))

        // Check workflows present
        assertNotNull(config.workflows?.get("search"))
        assertNotNull(config.workflows?.get("episodes"))
        assertNotNull(config.workflows?.get("sources"))

        val validator = RemoteProviderConfigValidator()
        val validation = validator.validate(
            config.copy(metadata = config.metadata.copy(fetchedAt = System.currentTimeMillis())),
            config.metadata.sourceUrl
        )
        assertTrue("kuramanime.json schema validation failed: ${validation.errors}", validation.isValid)
    }
}
