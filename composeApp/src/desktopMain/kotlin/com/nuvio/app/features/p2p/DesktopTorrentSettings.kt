package com.nuvio.app.features.p2p

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Update existing JSON settings only while our process is stopped; retain other engine preferences. */
internal fun prepareTorrServerSettingsFile(directory: File, cache: File, settings: P2pSettingsUiState) {
    val file = File(directory, "settings.json")
    if (!file.isFile) return // First launch keeps upstream defaults, then applies the profile through the API.
    val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return
    val current = root["BitTorr"] as? JsonObject ?: return
    if (current["StoreSettingsInJson"]?.jsonPrimitive?.booleanOrNull != true) return
    val configured = desktopTorrentSettings(current, settings, cache.absolutePath)
    if (configured == current) return
    val updated = JsonObject(root + ("BitTorr" to configured))
    val temporary = Files.createTempFile(directory.toPath(), "settings-", ".tmp")
    try {
        Files.writeString(temporary, updated.toString())
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { Files.deleteIfExists(temporary) }
}

/** Keep engine defaults, apply the user's actual settings, and bound in-memory buffering. */
internal fun desktopTorrentSettings(
    current: JsonObject,
    settings: P2pSettingsUiState,
    cachePath: String,
): JsonObject = buildJsonObject {
    current.forEach { (key, value) -> put(key, value) }
    val diskCache = settings.cacheSize != P2pCacheSize.NONE
    val memoryBytes = when (settings.torrentProfile) {
        P2pTorrentProfile.SOFT -> 128L * 1024 * 1024
        P2pTorrentProfile.BALANCED -> 256L * 1024 * 1024
        P2pTorrentProfile.FAST -> 512L * 1024 * 1024
    }
    put("CacheSize", if (diskCache) settings.cacheSize.bytes else memoryBytes)
    put("UseDisk", diskCache)
    put("TorrentsSavePath", if (diskCache) cachePath else "")
    put("RemoveCacheOnDrop", true)
    put("PreloadCache", if (diskCache) 1 else 10)
    put("ReaderReadAHead", 95)
    put("DisableUpload", !settings.enableUpload)
    put("ConnectionsLimit", when (settings.torrentProfile) {
        P2pTorrentProfile.SOFT -> 40
        P2pTorrentProfile.BALANCED -> 100
        P2pTorrentProfile.FAST -> 200
    })
    put("TorrentDisconnectTimeout", 120)
    put("ResponsiveMode", true)
    put("EnableDLNA", false)
    put("EnableBonjour", false)
}
