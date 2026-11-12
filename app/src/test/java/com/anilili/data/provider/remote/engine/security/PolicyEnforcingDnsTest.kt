package com.anilili.data.provider.remote.engine.security

import com.anilili.data.provider.remote.config.RemoteSourceTrust
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class PolicyEnforcingDnsTest {

    private class MockDns(private val map: Map<String, List<InetAddress>>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return map[hostname] ?: throw java.net.UnknownHostException(hostname)
        }
    }

    @Test
    fun `lookup blocks loopback address in production mode`() {
        val loopbackIp = InetAddress.getByName("127.0.0.1")
        val mockDns = MockDns(mapOf("malicious-rebinding.com" to listOf(loopbackIp)))

        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("malicious-rebinding.com"),
            allowLoopbackForTesting = false
        )
        val enforcingDns = PolicyEnforcingDns(policy, baseDns = mockDns)

        assertThrows(SecurityException::class.java) {
            enforcingDns.lookup("malicious-rebinding.com")
        }
    }

    @Test
    fun `lookup blocks unapproved private IP`() {
        val privateIp = InetAddress.getByName("192.168.1.100")
        val mockDns = MockDns(mapOf("internal-service.local" to listOf(privateIp)))

        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("internal-service.local"),
            allowLoopbackForTesting = false
        )
        val enforcingDns = PolicyEnforcingDns(policy, baseDns = mockDns)

        assertThrows(SecurityException::class.java) {
            enforcingDns.lookup("internal-service.local")
        }
    }

    @Test
    fun `lookup allows approved private IP in trusted endpoints`() {
        val privateIp = InetAddress.getByName("192.168.1.50")
        val mockDns = MockDns(mapOf("my-nas.lan" to listOf(privateIp)))

        val trust = RemoteSourceTrust(
            configUrl = "https://my-nas.lan:8443/config.json",
            configOrigin = "https://my-nas.lan:8443",
            trustedEndpointOrigins = setOf("https://my-nas.lan:8443"),
            allowPrivateIpEndpoints = true
        )
        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("my-nas.lan"),
            trust = trust,
            allowLoopbackForTesting = false
        )
        val enforcingDns = PolicyEnforcingDns(policy, baseDns = mockDns)

        val resolved = enforcingDns.lookup("my-nas.lan")
        assertEquals(1, resolved.size)
        assertEquals(privateIp, resolved[0])
    }

    @Test
    fun `lookup allows public IP address`() {
        val publicIp = InetAddress.getByName("93.184.216.34") // example.com
        val mockDns = MockDns(mapOf("example.com" to listOf(publicIp)))

        val policy = RemoteRequestPolicy(
            allowedDomains = setOf("example.com"),
            allowLoopbackForTesting = false
        )
        val enforcingDns = PolicyEnforcingDns(policy, baseDns = mockDns)

        val resolved = enforcingDns.lookup("example.com")
        assertEquals(1, resolved.size)
        assertEquals(publicIp, resolved[0])
    }
}
