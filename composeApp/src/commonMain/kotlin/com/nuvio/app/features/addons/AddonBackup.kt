package com.nuvio.app.features.addons

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val AddonBackupMaxBytes = 1024 * 1024

@Serializable
internal data class AddonBackup(
    val format: String,
    val version: Int,
    val addons: List<AddonBackupEntry>,
)

@Serializable
internal data class AddonBackupEntry(
    val manifestUrl: String,
    val enabled: Boolean = true,
    val name: String? = null,
)

internal enum class AddonBackupError { Invalid, UnsupportedVersion, TooLarge, ProfileChanged, PrimaryProfileRequired, FileAccess }

internal class AddonBackupException(val reason: AddonBackupError) : IllegalArgumentException(reason.name)

internal object AddonBackupCodec {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }

    fun encode(addons: List<ManagedAddon>): String {
        val payload = json.encodeToString(AddonBackup(format = "starlit-addons", version = 1, addons = addons.map {
            AddonBackupEntry(it.manifestUrl, it.enabled, it.userSetName)
        }))
        // Keep every exported file importable under the same limits.
        decode(payload)
        return payload
    }

    fun decode(payload: String): List<AddonBackupEntry> {
        if (payload.length > AddonBackupMaxBytes || payload.encodeToByteArray().size > AddonBackupMaxBytes) {
            throw AddonBackupException(AddonBackupError.TooLarge)
        }
        try {
            val backup = json.decodeFromString<AddonBackup>(payload)
            require(backup.format == "starlit-addons")
            if (backup.version != 1) throw AddonBackupException(AddonBackupError.UnsupportedVersion)
            require(backup.addons.size <= 1000)
            return backup.addons.map { entry ->
                val raw = entry.manifestUrl
                require(raw.length in 1..16384 && raw.none { it.isWhitespace() || it.isISOControl() } && '#' !in raw)
                require(raw.startsWith("https://") || raw.startsWith("http://"))
                // Ktor supplies defaults for an absent authority, so check the original URL too.
                val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
                require(authority.isNotBlank() && !authority.startsWith(':') && '\\' !in authority)
                val url = Url(raw)
                require(url.host.isNotBlank() && url.host.none { it.isWhitespace() } && url.port in 1..65535)
                require(entry.name == null || (entry.name.length <= 256 && entry.name.none { it.isISOControl() }))
                entry.copy(manifestUrl = normalizeManifestUrl(raw))
            }
        } catch (error: AddonBackupException) {
            throw error
        } catch (_: Exception) {
            throw AddonBackupException(AddonBackupError.Invalid)
        }
    }
}

internal data class AddonBackupMerge(val addons: List<ManagedAddon>, val imported: Int, val skipped: Int)

internal fun mergeAddonBackup(current: List<ManagedAddon>, entries: List<AddonBackupEntry>): AddonBackupMerge {
    val known = current.mapTo(mutableSetOf()) { it.manifestUrl }
    val added = entries.filter { known.add(it.manifestUrl) }.map {
        ManagedAddon(manifestUrl = it.manifestUrl, userSetName = it.name, enabled = it.enabled)
    }
    return AddonBackupMerge(current + added, added.size, entries.size - added.size)
}
