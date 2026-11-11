package com.anilili.data.provider.remote.config

import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.URL

/**
 * P9: Model for user-approved trust permissions for HTTP and self-hosted/LAN sources.
 *
 * Enforces per-source, granular permissions separating configuration fetching
 * from runtime provider endpoints. Loopback addresses (localhost/127.0.0.1/::1) are permanently blocked.
 */
@Serializable
data class RemoteSourceTrust(
    val configUrl: String,
    val configOrigin: String,
    val allowHttpConfig: Boolean = false,
    val allowPrivateIpConfig: Boolean = false,
    val trustedEndpointOrigins: Set<String> = emptySet(),
    val allowHttpEndpoints: Boolean = false,
    val allowPrivateIpEndpoints: Boolean = false,
    val isLocalNetwork: Boolean = false,
    val trustedAt: Long = System.currentTimeMillis(),
) {

    fun isConfigFetchAllowed(url: String): Boolean {
        val parsed = parseOrigin(url) ?: return false
        if (parsed.isLoopback) return false

        if (parsed.protocol.equals("https", ignoreCase = true)) {
            // HTTPS to public host is always allowed
            if (!parsed.isPrivateOrLocal) return true
            // HTTPS to private LAN host requires private IP permission
            return allowPrivateIpConfig
        }

        if (parsed.protocol.equals("http", ignoreCase = true)) {
            if (!allowHttpConfig) return false
            if (parsed.isPrivateOrLocal && !allowPrivateIpConfig) return false
            return parsed.origin.equals(configOrigin, ignoreCase = true)
        }

        return false
    }

    fun isEndpointOriginTrusted(endpointUrl: String): Boolean {
        val parsed = parseOrigin(endpointUrl) ?: return false
        if (parsed.isLoopback) return false

        // Standard public HTTPS endpoints are allowed by default
        if (parsed.protocol.equals("https", ignoreCase = true) && !parsed.isPrivateOrLocal) {
            return true
        }

        // HTTP or private IP endpoints require explicit authorization in trustedEndpointOrigins
        val matchesTrustedOrigin = trustedEndpointOrigins.any { it.equals(parsed.origin, ignoreCase = true) }
        if (!matchesTrustedOrigin) return false

        if (parsed.protocol.equals("http", ignoreCase = true) && !allowHttpEndpoints) {
            return false
        }

        if (parsed.isPrivateOrLocal && !allowPrivateIpEndpoints) {
            return false
        }

        return true
    }

    data class ParsedOrigin(
        val protocol: String,
        val host: String,
        val port: Int,
        val origin: String,
        val isPrivateOrLocal: Boolean,
        val isLoopback: Boolean,
    )

    companion object {
        fun parseOrigin(urlStr: String): ParsedOrigin? {
            return try {
                val url = URL(urlStr)
                val protocol = url.protocol.lowercase()
                if (protocol != "http" && protocol != "https") return null
                val host = url.host.lowercase()
                val port = if (url.port != -1) url.port else (if (protocol == "https") 443 else 80)
                val origin = "$protocol://$host:$port"
                val isLoopback = isLoopbackHost(host)
                val isPrivate = isPrivateOrLocalHost(host)
                ParsedOrigin(
                    protocol = protocol,
                    host = host,
                    port = port,
                    origin = origin,
                    isPrivateOrLocal = isPrivate,
                    isLoopback = isLoopback,
                )
            } catch (e: Exception) {
                null
            }
        }

        fun isLoopbackHost(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") {
                return true
            }
            return try {
                val addrs = InetAddress.getAllByName(host)
                addrs.any { it.isLoopbackAddress || it.isAnyLocalAddress || it.hostAddress == "127.0.0.1" || it.hostAddress == "::1" }
            } catch (e: Exception) {
                false
            }
        }

        fun isPrivateOrLocalHost(host: String): Boolean {
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".internal")) {
                return true
            }
            if (isLiteralPrivateIp(host)) {
                return true
            }
            return try {
                val addrs = InetAddress.getAllByName(host)
                addrs.any { addr ->
                    val ip = addr.hostAddress ?: return@any false
                    isLiteralPrivateIp(ip) || addr.isSiteLocalAddress || addr.isLinkLocalAddress
                }
            } catch (e: Exception) {
                isLiteralPrivateIp(host)
            }
        }

        fun isLiteralPrivateIp(ip: String): Boolean {
            return ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                (ip.startsWith("172.") && (16..31).contains(ip.substringAfter("172.").substringBefore(".").toIntOrNull() ?: -1)) ||
                ip.startsWith("169.254.") ||
                ip.startsWith("127.") ||
                ip.startsWith("0.") ||
                ip.startsWith("fc00:") ||
                ip.startsWith("fd00:") ||
                ip.startsWith("fe80:")
        }

        fun createTrustGrant(
            configUrl: String,
            allowHttpConfig: Boolean,
            allowPrivateIpConfig: Boolean,
            trustedEndpointOrigins: Set<String> = emptySet(),
            allowHttpEndpoints: Boolean = false,
            allowPrivateIpEndpoints: Boolean = false,
        ): RemoteSourceTrust? {
            val parsed = parseOrigin(configUrl) ?: return null
            if (parsed.isLoopback) return null

            return RemoteSourceTrust(
                configUrl = configUrl,
                configOrigin = parsed.origin,
                allowHttpConfig = allowHttpConfig,
                allowPrivateIpConfig = allowPrivateIpConfig,
                trustedEndpointOrigins = trustedEndpointOrigins,
                allowHttpEndpoints = allowHttpEndpoints,
                allowPrivateIpEndpoints = allowPrivateIpEndpoints,
                isLocalNetwork = parsed.isPrivateOrLocal,
                trustedAt = System.currentTimeMillis(),
            )
        }
    }
}
