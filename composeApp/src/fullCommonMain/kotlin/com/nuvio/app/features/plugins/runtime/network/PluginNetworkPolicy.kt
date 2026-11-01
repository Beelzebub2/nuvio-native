package com.nuvio.app.features.plugins.runtime.network

import io.ktor.http.Url

/** Scrapers run untrusted code. They may contact public HTTPS websites, never local services. */
internal fun requirePublicPluginUrl(raw: String): String {
    require(raw.length in 9..4096 && raw.startsWith("https://", ignoreCase = true)) {
        "Plugins may fetch public HTTPS addresses only"
    }
    val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    require('@' !in authority && '%' !in authority && '\\' !in authority) { "Invalid plugin URL" }
    val parsed = Url(raw)
    val host = parsed.host.lowercase().trimEnd('.')
    require(parsed.protocol.name == "https" && publicPluginHostname(host)) {
        "Plugins cannot access private network or device addresses"
    }
    return raw
}

internal fun publicPluginHostname(host: String): Boolean {
    if (host.isBlank() || ':' in host || host.contains("..") || !host.contains('.')) return false
    if (host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return false
    // Reject all literal IPv4 forms, including decimal, octal and hex variants interpreted
    // differently by system resolvers, and hostnames reserved for local resolution.
    if (host.split('.').all { part -> part.all(Char::isDigit) && part.isNotBlank() }) return false
    if (host.startsWith("0x")) return false
    val blockedSuffixes = listOf("localhost", "local", "lan", "home", "internal", "test", "invalid", "onion", "arpa")
    if (blockedSuffixes.any { host == it || host.endsWith(".$it") }) return false
    return true
}
