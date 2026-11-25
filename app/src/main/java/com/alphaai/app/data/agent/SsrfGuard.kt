package com.alphaai.app.data.agent

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Rejects unsafe URL schemes and any host resolving to private, loopback, link-local,
 * unspecified, multicast, or carrier-grade NAT space. DNS is resolved at validation time. */
object SsrfGuard {
    data class Allowed(val uri: URI, val addresses: List<InetAddress>)

    fun validate(raw: String, allowlistedHosts: Set<String>? = null): Allowed {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("invalid URL") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "only http:// and https:// URLs are allowed" }
        require(uri.userInfo == null) { "URL credentials are not allowed" }
        val host = uri.host?.trimEnd('.')?.lowercase() ?: throw IllegalArgumentException("URL host is missing")
        require(host.isNotBlank() && host.length <= 253) { "invalid host" }
        if (allowlistedHosts != null) {
            require(allowlistedHosts.any { host == it || host.endsWith(".$it") }) {
                "host '$host' is not in the user's HTTP allowlist"
            }
        }
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            throw IllegalArgumentException("host could not be resolved")
        }
        require(addresses.isNotEmpty()) { "host did not resolve" }
        require(addresses.none(::isBlocked)) { "URL resolves to a private or reserved network address" }
        return Allowed(uri, addresses)
    }

    private fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (address is Inet4Address) {
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            // RFC1918, loopback, link-local, CGNAT, benchmarking, multicast/reserved.
            if (a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 100 && b in 64..127) ||
                (a == 198 && b in 18..19) || a >= 224 || (a == 0)) return true
        }
        if (address is Inet6Address) {
            // IPv4-mapped IPv6, unique-local fc00::/7, plus link-local fe80::/10.
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
            if ((bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80) return true
            if (bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
                val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                return isBlocked(v4)
            }
        }
        return false
    }
}
