package com.anilili.data.provider.remote

import com.anilili.data.provider.remote.config.ConfigMetadata
import com.anilili.data.provider.remote.config.ProviderCapabilities
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderConfigValidator
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.config.RemoteSourceTrust
import com.anilili.data.provider.remote.config.RemoteSourceType
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9: Comprehensive tests for Trusted HTTP & Self-Hosted Sources.
 *
 * Covers:
 * - HTTPS regression preservation
 * - Public HTTP source & endpoint trust
 * - Local LAN IP & hostname trust
 * - Custom port scoping
 * - Loopback permanent denial (localhost/127.0.0.1/::1)
 * - Insecure protocol downgrade redirect prevention (HTTPS -> HTTP)
 * - Cross-origin redirect SSRF protection
 * - Separate config fetching vs runtime endpoint permissions
 */
class RemoteProviderHttpTrustTest {

    private val validator = RemoteProviderConfigValidator.DEFAULT

    private fun sampleConfig(
        sourceUrl: String = "https://example.com/provider.json",
        endpoint: String = "https://example.com/api",
    ) = RemoteProviderConfig(
        schemaVersion = 1,
        metadata = ConfigMetadata(
            name = "Test Provider",
            description = "Test Description",
            author = "Test Author",
            version = "1.0.0",
            sourceUrl = sourceUrl,
            fetchedAt = System.currentTimeMillis(),
            checksum = null,
        ),
        providers = listOf(
            RemoteProviderEntry(
                id = "test_custom_p9",
                name = "Test P9 Provider",
                endpoint = endpoint,
                source = RemoteSourceType.GENERIC_JSON,
                priority = 10,
                capabilities = ProviderCapabilities(supportsSearch = true),
            ),
        ),
    )

    @Test
    fun `HTTPS regression - public HTTPS works without any trust grant`() {
        val config = sampleConfig()
        val result = validator.validate(config, "https://example.com/provider.json")
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())

        val policy = RemoteRequestPolicy(allowedDomains = setOf("example.com"))
        val requestResult = policy.validateRequest("https://example.com/api", "wf-p9-1")
        assertTrue(requestResult.allowed)
        assertNull(requestResult.error)
    }

    @Test
    fun `Public HTTP - rejected by default when trust is absent`() {
        val config = sampleConfig(
            sourceUrl = "http://public-server.org/provider.json",
            endpoint = "http://public-server.org/api",
        )
        val result = validator.validate(config, "http://public-server.org/provider.json")
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Metadata sourceUrl must use HTTPS") })
        assertTrue(result.errors.any { it.contains("endpoint must use HTTPS") })

        val policy = RemoteRequestPolicy()
        val requestResult = policy.validateRequest("http://public-server.org/api", "wf-p9-2")
        assertFalse(requestResult.allowed)
        assertTrue(requestResult.error?.contains("HTTPS") == true)
    }

    @Test
    fun `Public HTTP - accepted when explicit trust is granted`() {
        val trust = RemoteSourceTrust.createTrustGrant(
            configUrl = "http://public-server.org/provider.json",
            allowHttpConfig = true,
            allowPrivateIpConfig = false,
            trustedEndpointOrigins = setOf("http://public-server.org:80"),
            allowHttpEndpoints = true,
            allowPrivateIpEndpoints = false,
        )
        assertNotNull(trust)

        val config = sampleConfig(
            sourceUrl = "http://public-server.org/provider.json",
            endpoint = "http://public-server.org/api",
        )
        val result = validator.validate(config, "http://public-server.org/provider.json", trust)
        assertTrue(result.errors.joinToString(), result.isValid)

        val policy = RemoteRequestPolicy(trust = trust)
        val requestResult = policy.validateRequest("http://public-server.org/api", "wf-p9-3")
        assertTrue(requestResult.error ?: "", requestResult.allowed)
    }

    @Test
    fun `Local LAN IP and custom port - rejected by default, accepted with LAN trust`() {
        val config = sampleConfig(
            sourceUrl = "http://192.168.1.10:8080/provider.json",
            endpoint = "http://192.168.1.10:8080/api",
        )

        // Without trust
        val unapprovedResult = validator.validate(config, "http://192.168.1.10:8080/provider.json")
        assertFalse(unapprovedResult.isValid)

        // With explicit LAN trust
        val lanTrust = RemoteSourceTrust.createTrustGrant(
            configUrl = "http://192.168.1.10:8080/provider.json",
            allowHttpConfig = true,
            allowPrivateIpConfig = true,
            trustedEndpointOrigins = setOf("http://192.168.1.10:8080"),
            allowHttpEndpoints = true,
            allowPrivateIpEndpoints = true,
        )
        assertNotNull(lanTrust)
        assertTrue(lanTrust!!.isLocalNetwork)

        val approvedResult = validator.validate(config, "http://192.168.1.10:8080/provider.json", lanTrust)
        assertTrue(approvedResult.errors.joinToString(), approvedResult.isValid)

        val policy = RemoteRequestPolicy(trust = lanTrust)
        val reqResult = policy.validateRequest("http://192.168.1.10:8080/api", "wf-lan")
        assertTrue(reqResult.allowed)

        // Custom port scoping: permission for port 8080 does NOT grant access to port 22 or port 80
        val port80Result = policy.validateRequest("http://192.168.1.10/api", "wf-lan")
        assertFalse(port80Result.allowed)

        val port22Result = policy.validateRequest("http://192.168.1.10:22/api", "wf-lan")
        assertFalse(port22Result.allowed)
    }

    @Test
    fun `Local mDNS and dot-lan hostnames - classified as local network and require LAN permission`() {
        val nasTrust = RemoteSourceTrust.createTrustGrant(
            configUrl = "http://nas.local:8080/provider.json",
            allowHttpConfig = true,
            allowPrivateIpConfig = true,
            trustedEndpointOrigins = setOf("http://nas.local:8080"),
            allowHttpEndpoints = true,
            allowPrivateIpEndpoints = true,
        )
        assertNotNull(nasTrust)
        assertTrue(nasTrust!!.isLocalNetwork)

        val config = sampleConfig(
            sourceUrl = "http://nas.local:8080/provider.json",
            endpoint = "http://nas.local:8080/api",
        )
        val result = validator.validate(config, "http://nas.local:8080/provider.json", nasTrust)
        assertTrue(result.errors.joinToString(), result.isValid)

        val policy = RemoteRequestPolicy(trust = nasTrust)
        val okResult = policy.validateRequest("http://nas.local:8080/api", "wf-mdns")
        assertTrue(okResult.allowed)
    }

    @Test
    fun `Loopback protection - localhost and 127_0_0_1 are permanently blocked even if trust is attempted`() {
        val loopbackTrust = RemoteSourceTrust.createTrustGrant(
            configUrl = "http://127.0.0.1:8080/provider.json",
            allowHttpConfig = true,
            allowPrivateIpConfig = true,
        )
        // createTrustGrant rejects loopback
        assertNull(loopbackTrust)

        val policy = RemoteRequestPolicy()
        val res1 = policy.validateRequest("http://127.0.0.1:8080/api", "wf-loopback")
        assertFalse(res1.allowed)
        assertTrue(res1.error?.contains("loopback") == true || res1.error?.contains("Cleartext HTTP") == true)

        val res2 = policy.validateRequest("https://localhost:8443/api", "wf-loopback")
        assertFalse(res2.allowed)
        assertTrue(res2.error?.contains("loopback") == true)

        val config = sampleConfig(
            sourceUrl = "https://localhost:8443/provider.json",
            endpoint = "https://localhost:8443/api",
        )
        val valResult = validator.validate(config, "https://localhost:8443/provider.json")
        assertFalse(valResult.isValid)
        assertTrue(valResult.errors.any { it.contains("loopback") || it.contains("localhost") })
    }

    @Test
    fun `Redirect security - protocol downgrade from HTTPS to HTTP is blocked`() {
        val policy = RemoteRequestPolicy()
        val redirectResult = policy.validateRedirect(
            originalUrl = "https://example.com/api",
            redirectUrl = "http://example.com/api",
            workflowId = "wf-downgrade",
        )
        assertFalse(redirectResult.allowed)
        assertTrue(redirectResult.error?.contains("downgrade") == true)
    }

    @Test
    fun `Redirect security - cross-origin redirect to unapproved private IP or HTTP is blocked`() {
        val trust = RemoteSourceTrust.createTrustGrant(
            configUrl = "https://example.com/provider.json",
            allowHttpConfig = false,
            allowPrivateIpConfig = false,
            trustedEndpointOrigins = setOf("https://example.com:443"),
            allowHttpEndpoints = false,
            allowPrivateIpEndpoints = false,
        )
        val policy = RemoteRequestPolicy(trust = trust)

        // Redirect from approved example.com to internal 192.168.1.50
        val redirectResult = policy.validateRedirect(
            originalUrl = "https://example.com/api",
            redirectUrl = "https://192.168.1.50/api",
            workflowId = "wf-ssrf-redirect",
        )
        assertFalse(redirectResult.allowed)
        assertTrue(redirectResult.error?.contains("unapproved destination") == true || redirectResult.error?.contains("blocked") == true)
    }

    @Test
    fun `Separate permissions - config trust alone does not grant runtime endpoint HTTP access`() {
        // Source trust grants permission to fetch config over HTTP, but NOT runtime endpoints
        val configOnlyTrust = RemoteSourceTrust.createTrustGrant(
            configUrl = "http://192.168.1.20:8080/provider.json",
            allowHttpConfig = true,
            allowPrivateIpConfig = true,
            trustedEndpointOrigins = emptySet(),
            allowHttpEndpoints = false,
            allowPrivateIpEndpoints = false,
        )
        assertNotNull(configOnlyTrust)

        val config = sampleConfig(
            sourceUrl = "http://192.168.1.20:8080/provider.json",
            endpoint = "http://192.168.1.20:8080/api",
        )
        val result = validator.validate(config, "http://192.168.1.20:8080/provider.json", configOnlyTrust)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("endpoint must use HTTPS") })

        val policy = RemoteRequestPolicy(trust = configOnlyTrust)
        val requestResult = policy.validateRequest("http://192.168.1.20:8080/api", "wf-endpoints")
        assertFalse(requestResult.allowed)
    }
}
