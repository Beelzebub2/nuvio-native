package com.nuvio.app.features.p2p

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
