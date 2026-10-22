package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
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
