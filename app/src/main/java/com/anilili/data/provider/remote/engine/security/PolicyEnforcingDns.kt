package com.anilili.data.provider.remote.engine.security

import com.anilili.data.provider.remote.config.RemoteSourceTrust
import com.anilili.diagnostics.DiagnosticsLog
import okhttp3.Dns
import java.net.InetAddress

/**
 * Custom OkHttp DNS resolver that enforces security policies at socket connection time.
 * Prevents DNS Rebinding attacks where a domain resolves to a public IP during URL pre-validation
 * but mutates to a loopback or private LAN IP when the TCP connection is established.
 */
class PolicyEnforcingDns(
    private val policy: RemoteRequestPolicy,
    private val baseDns: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val hostLower = hostname.lowercase()

        // 1. Direct host name check
        if (!policy.allowLoopbackForTesting && RemoteSourceTrust.isLoopbackHost(hostLower)) {
            DiagnosticsLog.event("PolicyEnforcingDns blocked loopback host: $hostLower")
            throw SecurityException("Access to loopback host is blocked by security policy: $hostLower")
        }

        val addresses = baseDns.lookup(hostname)
        if (addresses.isEmpty()) {
            return addresses
        }

        // 2. Validate every resolved IP address at connection time
        for (addr in addresses) {
            val ip = addr.hostAddress ?: continue

            // Loopback validation (127.0.0.0/8, ::1, 0.0.0.0)
            if (!policy.allowLoopbackForTesting && (addr.isLoopbackAddress || ip.startsWith("127.") || ip == "0.0.0.0" || ip == "::1")) {
                DiagnosticsLog.event("PolicyEnforcingDns blocked loopback address: $hostname -> $ip")
                throw SecurityException("DNS rebinding blocked: '$hostname' resolved to loopback address $ip")
            }

            // Private / Local / Reserved IP validation
            val isPrivateOrReserved = addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isMulticastAddress ||
                ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                ip.startsWith("169.254.") ||
                (ip.startsWith("172.") && (16..31).contains(ip.substringAfter("172.").substringBefore(".").toIntOrNull() ?: -1))

            if (isPrivateOrReserved) {
                val trust = policy.trust
                val isTrusted = trust != null && trust.allowPrivateIpEndpoints && trust.isHostTrusted(hostname)
                if (!isTrusted) {
                    DiagnosticsLog.event("PolicyEnforcingDns blocked private IP: $hostname -> $ip")
                    throw SecurityException("Access to private/reserved IP address is blocked: $hostname -> $ip")
                }
            }
        }

        return addresses
    }
}
