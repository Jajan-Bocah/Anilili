package com.anilili.data.provider.remote.engine.security

import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class RemoteRequestPolicy(
    private val allowedDomains: Set<String> = emptySet(),
    private val maxResponseBytes: Long = 1024 * 1024,
    private val maxRequestsPerRun: Int = 20,
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000,
    private val totalTimeoutMs: Int = 30000,
    private val allowRedirects: Boolean = true,
    private val maxRedirects: Int = 5,
) {
    private val requestCount = ConcurrentHashMap<String, Int>()

    data class ValidationResult(
        val allowed: Boolean,
        val error: String? = null,
        val sanitizedUrl: String? = null,
    ) {
        companion object {
            fun allow(url: String) = ValidationResult(true, sanitizedUrl = url)
            fun deny(error: String) = ValidationResult(false, error)
        }
    }

    fun validateRequest(url: String, workflowId: String): ValidationResult {
        if (!isHttpsUrl(url)) {
            return ValidationResult.deny("Only HTTPS URLs are allowed: $url")
        }

        val parsedUrl = try { URL(url) } catch (e: Exception) {
            return ValidationResult.deny("Invalid URL format: $url")
        }

        val host = parsedUrl.host.lowercase()

        if (allowedDomains.isNotEmpty() && host !in allowedDomains && !allowedDomains.any { host.endsWith(".$it") }) {
            return ValidationResult.deny("Domain '$host' is not in allowed domains list")
        }

        if (isPrivateOrReservedIp(host)) {
            return ValidationResult.deny("Access to private/reserved IP addresses is blocked: $host")
        }

        val currentCount = requestCount.getOrDefault(workflowId, 0)
        if (currentCount >= maxRequestsPerRun) {
            return ValidationResult.deny("Maximum requests per workflow run exceeded ($maxRequestsPerRun)")
        }

        return ValidationResult.allow(url)
    }

    fun validateRedirect(originalUrl: String, redirectUrl: String, workflowId: String): ValidationResult {
        if (!allowRedirects) {
            return ValidationResult.deny("Redirects are not allowed")
        }

        val redirectValidation = validateRequest(redirectUrl, workflowId)
        if (!redirectValidation.allowed) {
            return redirectValidation
        }

        val originalHost = try { URL(originalUrl).host.lowercase() } catch (e: Exception) { "" }
        val redirectHost = try { URL(redirectUrl).host.lowercase() } catch (e: Exception) { "" }

        if (originalHost != redirectHost) {
            DiagnosticsLog.event("RemoteRequestPolicy cross-origin redirect: $originalHost -> $redirectHost (workflow: $workflowId)")
        }

        return redirectValidation
    }

    fun incrementRequestCount(workflowId: String): Int {
        return requestCount.compute(workflowId) { _, v -> (v ?: 0) + 1 } ?: 0
    }

    fun resetRequestCount(workflowId: String) {
        requestCount.remove(workflowId)
    }

    fun getConnectTimeoutMs(): Int = connectTimeoutMs
    fun getReadTimeoutMs(): Int = readTimeoutMs
    fun getTotalTimeoutMs(): Int = totalTimeoutMs
    fun getMaxResponseBytes(): Long = maxResponseBytes
    fun getMaxRedirects(): Int = maxRedirects
    fun isRedirectsAllowed(): Boolean = allowRedirects

    private fun isHttpsUrl(url: String): Boolean {
        return try {
            URL(url).protocol.equals("https", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun isPrivateOrReservedIp(host: String): Boolean {
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.any { addr ->
                val ip = addr.hostAddress ?: return@any false
                ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                ip.startsWith("172.") && (16..31).contains(ip.substringAfter("172.").substringBefore(".").toIntOrNull() ?: -1) ||
                ip.startsWith("127.") ||
                ip.startsWith("169.254.") ||
                ip.startsWith("0.") ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            }
        } catch (e: Exception) {
            false
        }
    }

    @Serializable
    data class SecurityConfig(
        val allowedDomains: List<String> = emptyList(),
        val maxResponseBytes: Long = 1024 * 1024,
        val maxRequestsPerRun: Int = 20,
        val connectTimeoutMs: Int = 10000,
        val readTimeoutMs: Int = 15000,
        val totalTimeoutMs: Int = 30000,
        val allowRedirects: Boolean = true,
        val maxRedirects: Int = 5,
    )

    companion object {
        fun fromSecurityConfig(config: SecurityConfig): RemoteRequestPolicy {
            return RemoteRequestPolicy(
                allowedDomains = config.allowedDomains.toSet(),
                maxResponseBytes = config.maxResponseBytes,
                maxRequestsPerRun = config.maxRequestsPerRun,
                connectTimeoutMs = config.connectTimeoutMs,
                readTimeoutMs = config.readTimeoutMs,
                totalTimeoutMs = config.totalTimeoutMs,
                allowRedirects = config.allowRedirects,
                maxRedirects = config.maxRedirects,
            )
        }
    }
}