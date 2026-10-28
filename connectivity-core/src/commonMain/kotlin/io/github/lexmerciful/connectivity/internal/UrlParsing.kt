package io.github.lexmerciful.connectivity.internal

internal class ParsedUrl(val scheme: String, val host: String, val port: Int)

/**
 * Minimal, dependency-free parsing of an absolute `http(s)` URL: scheme, host (IPv6 in brackets) and port.
 *
 * @throws IllegalArgumentException with a message naming [url] when it is not usable.
 */
internal fun parseHttpUrl(url: String): ParsedUrl {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    require(schemeEnd > 0) { "\"$url\" is not an absolute http(s) URL" }
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    require(scheme == "http" || scheme == "https") { "\"$url\" must use http or https, was $scheme" }

    val authority = trimmed.substring(schemeEnd + 3)
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
    val host: String
    val portText: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        require(close > 1) { "\"$url\" has a malformed IPv6 host" }
        host = authority.substring(1, close)
        portText = authority.substring(close + 1).removePrefix(":").ifEmpty { null }
    } else {
        host = authority.substringBefore(':')
        portText = if (':' in authority) authority.substringAfter(':') else null
    }
    require(host.isNotBlank()) { "\"$url\" has no host" }
    val port = portText?.toIntOrNull() ?: if (portText == null) defaultPort(scheme) else -1
    require(port in 1..65535) { "\"$url\" has an invalid port \"$portText\"" }
    return ParsedUrl(scheme, host, port)
}

private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
