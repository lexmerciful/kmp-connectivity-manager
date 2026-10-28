package io.github.lexmerciful.connectivity.config

import io.github.lexmerciful.connectivity.internal.parseHttpUrl

/** A TCP probe target. [host] can be an IPv4/IPv6 literal (no DNS lookup) or a host name. */
public data class HostPort(val host: String, val port: Int = 443) {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

/**
 * Ready-made probe targets.
 *
 * The library never contacts third-party hosts on its own: probe-based monitors need explicit targets,
 * and these constants make that a one-liner.
 */
public object ProbeTargets {

    /** Cloudflare and Google public resolvers on port 443, IPv4 only. */
    public val PublicResolversV4: List<HostPort> = listOf(HostPort("1.1.1.1"), HostPort("8.8.8.8"))

    /** Cloudflare and Google public resolvers on port 443, IPv6 only. */
    public val PublicResolversV6: List<HostPort> =
        listOf(HostPort("2606:4700:4700::1111"), HostPort("2001:4860:4860::8888"))

    /**
     * Cloudflare and Google public resolvers on port 443, IPv4 first, then IPv6.
     * IP literals skip DNS, so a check usually takes one round trip.
     */
    public val PublicResolvers: List<HostPort> = PublicResolversV4 + PublicResolversV6

    /**
     * Google's connectivity-check URL for the HTTP probe. It answers `204`; captive portals answer
     * `200` or a redirect, so use `expectedStatus = 204..204` to detect them.
     */
    public const val Generate204Url: String = "https://www.gstatic.com/generate_204"

    /**
     * The host and port of an `http(s)` URL, e.g. your API base URL. The port defaults to 443 for
     * https and 80 for http.
     *
     * @throws IllegalArgumentException if [url] is not an absolute http(s) URL.
     */
    public fun fromUrl(url: String): HostPort {
        val parsed = parseHttpUrl(url)
        return HostPort(parsed.host, parsed.port)
    }
}
