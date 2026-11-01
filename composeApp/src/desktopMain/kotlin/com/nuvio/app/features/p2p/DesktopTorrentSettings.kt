package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.features.downloads.isManagedDownloadTorrentCache
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val SETTINGS_STORAGE_ERROR =
    "Torrent engine settings could not be read or repaired. Check app storage permissions and retry."

/** Five independent download processes share one download cache/connection allowance. */
internal const val MAX_DOWNLOAD_TORRENT_ENGINES = 5

internal data class DownloadTorrentResourceBudget(val cacheBytes: Long, val connectionLimit: Int)

private const val PLAYBACK_PRELOAD_TARGET_BYTES = 128L * 1024L * 1024L

/**
 * TorrServer's PreloadCache is a percentage of CacheSize, not a percentage of the media file.
 * Size the startup buffer for a high-bitrate episode without making a 10 GiB disk cache
 * require multiple gigabytes before the player can begin. Keep a maximum of 25%
 * for small disk caches and 50% for memory-only caches so reading can continue.
 */
internal fun playbackTorrentPreloadPercent(cacheBytes: Long, useDisk: Boolean): Int {
    require(cacheBytes > 0L)
    val roundedPercent = ((PLAYBACK_PRELOAD_TARGET_BYTES * 100L + cacheBytes / 2L) / cacheBytes)
    return roundedPercent.coerceIn(1L, if (useDisk) 25L else 50L).toInt()
}

/** Reserved per-process slice; playback continues to receive its normal user-selected settings. */
internal fun downloadTorrentResourceBudget(settings: P2pSettingsUiState): DownloadTorrentResourceBudget {
    val cacheBytes = if (settings.cacheSize != P2pCacheSize.NONE) {
        settings.cacheSize.bytes / MAX_DOWNLOAD_TORRENT_ENGINES
    } else {
        // Memory-only cache is independent of the disk allowance. Keep a usable >=128 MiB
        // buffer for soft mode, while limiting five processes to 640 MiB/1 GiB/1.5 GiB.
        val totalMiB = when (settings.torrentProfile) {
            P2pTorrentProfile.SOFT -> 640L
            P2pTorrentProfile.BALANCED -> 1_024L
            P2pTorrentProfile.FAST -> 1_536L
        }
        totalMiB * 1024L * 1024L / MAX_DOWNLOAD_TORRENT_ENGINES
    }
    val connections = when (settings.torrentProfile) {
        P2pTorrentProfile.SOFT -> 25
        P2pTorrentProfile.BALANCED -> 50
        P2pTorrentProfile.FAST -> 80
    }
    return DownloadTorrentResourceBudget(cacheBytes, connections)
}

/** Update existing JSON settings only while our process is stopped; retain other engine preferences. */
internal fun prepareTorrServerSettingsFile(directory: File, cache: File, settings: P2pSettingsUiState) {
    val file = File(directory, "settings.json")
    try {
        val text = try {
            Files.readString(file.toPath())
        } catch (_: NoSuchFileException) {
            return // First launch keeps upstream defaults, then applies the profile through the API.
        } catch (_: CharacterCodingException) {
            quarantineTorrServerSettings(file, "invalid UTF-8")
            return
        }
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            quarantineTorrServerSettings(file, "invalid JSON")
            return
        }
        if (root == null || (root.containsKey("BitTorr") && root["BitTorr"] !is JsonObject)) {
            quarantineTorrServerSettings(file, "invalid settings object")
            return
        }
        val current = root["BitTorr"] as? JsonObject ?: return
        val persistence = current["StoreSettingsInJson"]
        if (persistence != null && (persistence !is JsonPrimitive || persistence.isString || persistence.booleanOrNull == null)) {
            quarantineTorrServerSettings(file, "invalid persistence flag")
            return
        }
        if ((persistence as? JsonPrimitive)?.booleanOrNull != true) return
        val configured = desktopTorrentSettings(current, settings, cache.absolutePath)
        if (configured == current) return
        val updated = JsonObject(root + ("BitTorr" to configured))
        val temporary = Files.createTempFile(directory.toPath(), "settings-", ".tmp")
        try {
            temporary.toFile().outputStream().use { output ->
                output.write(updated.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    } catch (_: IOException) {
        throw P2pStreamingException(SETTINGS_STORAGE_ERROR)
    } catch (_: SecurityException) {
        throw P2pStreamingException(SETTINGS_STORAGE_ERROR)
    }
}

private fun quarantineTorrServerSettings(file: File, reason: String) {
    val backup = file.toPath().resolveSibling("settings-corrupt-${UUID.randomUUID()}.json")
    // No replacement: a failed backup must leave the original in place and abort startup.
    Files.move(file.toPath(), backup)
    Logger.withTag("TorrServerSettings").w { "Recovered $reason; preserved settings as ${backup.fileName}" }
}

/** Keep engine defaults, apply the user's actual settings, and bound in-memory buffering. */
internal fun desktopTorrentSettings(
    current: JsonObject,
    settings: P2pSettingsUiState,
    cachePath: String,
): JsonObject = buildJsonObject {
    current.forEach { (key, value) -> put(key, value) }
    val diskCache = settings.cacheSize != P2pCacheSize.NONE
    val downloadBudget = if (isManagedDownloadTorrentCache(File(cachePath))) downloadTorrentResourceBudget(settings) else null
    val memoryBytes = when (settings.torrentProfile) {
        P2pTorrentProfile.SOFT -> 128L * 1024 * 1024
        P2pTorrentProfile.BALANCED -> 256L * 1024 * 1024
        P2pTorrentProfile.FAST -> 512L * 1024 * 1024
    }
    val cacheBytes = downloadBudget?.cacheBytes ?: if (diskCache) settings.cacheSize.bytes else memoryBytes
    put("CacheSize", cacheBytes)
    put("UseDisk", diskCache)
    put("TorrentsSavePath", if (diskCache) cachePath else "")
    put("RemoveCacheOnDrop", true)
    // A downloaded file can wait for its HTTP reader; interactive playback needs a larger
    // contiguous startup cushion. Keep the small preload for background download processes.
    put("PreloadCache", if (downloadBudget != null) {
        if (diskCache) 1 else 10
    } else {
        playbackTorrentPreloadPercent(cacheBytes, diskCache)
    })
    put("ReaderReadAHead", 95)
    put("DisableUpload", !settings.enableUpload)
    put("ConnectionsLimit", downloadBudget?.connectionLimit ?: when (settings.torrentProfile) {
        P2pTorrentProfile.SOFT -> 40
        P2pTorrentProfile.BALANCED -> 100
        P2pTorrentProfile.FAST -> 200
    })
    put("TorrentDisconnectTimeout", 120)
    put("ResponsiveMode", true)
    put("EnableDLNA", false)
    put("EnableBonjour", false)
}
