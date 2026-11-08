package com.nuvio.app.features.remote

import com.nuvio.app.features.streams.StreamItem

/** Never advertise external open actions, file URIs or disabled P2P sources over the remote. */
internal fun StreamItem.isEligibleForRemotePlayback(p2pEnabled: Boolean, debridEnabled: Boolean): Boolean {
    if (shouldOpenExternally) return false
    val direct = playableDirectUrl?.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }
    return direct != null ||
        (debridEnabled && isAddonDebridCandidate) ||
        (p2pEnabled && needsLocalDebridResolve && p2pInfoHash != null)
}

private val privateSourceLink = Regex("(?i)(https?://|magnet:|torrent://)\\S+")
private val privateSourceCredential = Regex("(?i)(bearer|token|api[-_]?key|authorization)\\s*[=:]\\s*\\S+")

/** Addon titles and names are untrusted. Never leak media links or credentials in a display label. */
internal fun StreamItem.remoteSourceLabel(): String {
    fun safe(input: String) = input
        .replace(privateSourceLink, "[link]")
        .replace(privateSourceCredential, "[redacted]")
        .map { if (it < ' ' || it == '\u007f') ' ' else it }
        .joinToString("")
        .trim()
        .take(65)
    val provider = safe(addonName).ifBlank { "Source" }
    val source = safe(streamLabel).ifBlank { "Stream" }
    val size = behaviorHints.videoSize?.takeIf { it > 0 }?.let { bytes ->
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 0.1) " · " + ((gib * 10).toLong() / 10.0).toString() + " GiB" else ""
    }.orEmpty()
    return "$provider · $source$size".take(150)
}
