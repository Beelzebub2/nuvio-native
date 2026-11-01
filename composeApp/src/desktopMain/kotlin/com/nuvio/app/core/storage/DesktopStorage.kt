package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        val override = System.getProperty("nuvio.dataDir")
        val directory = override?.let(Paths::get) ?: resolveAppDataDir()
        val firstLaunch = !Files.exists(directory)
        Files.createDirectories(directory)
        if (firstLaunch && override == null && System.getProperty("os.name").startsWith("Windows")) {
            val previous = directory.parent.resolve("Nuvio")
            // Copy local preferences once; the original app's files and account credentials stay intact.
            val preferences = listOf(
                "nuvio_addons", "nuvio_profiles", "nuvio_avatars", "nuvio_home_catalog_settings",
                "nuvio_theme_settings", "nuvio_poster_card_style", "nuvio_card_depth_style",
                "nuvio_player_settings", "nuvio_watched", "nuvio_watch_progress", "torrent_settings",
            )
            preferences.forEach { name ->
                val source = previous.resolve("$name.properties")
                if (Files.isRegularFile(source)) runCatching { Files.copy(source, directory.resolve(source.fileName)) }
            }
        }
        directory
    }

    val cacheDir: Path by lazy {
        (System.getProperty("nuvio.dataDir")?.let { Paths.get(it).resolve("Cache") } ?: resolveCacheDir()).also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
        }
        wipeFiles(rootDir)
    }

    /** Account cleanup must preserve downloaded media even in the legacy app-managed folder. */
    internal fun wipeFiles(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != directory && !it.startsWith(directory.resolve("anonymous-workspace")) }
                .filter { !it.startsWith(directory.resolve("downloads")) }
                .filter { it.fileName.toString() !in setOf("server_configuration.properties", "nuvio_sync_client_identity.properties") }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    internal fun reloadStores() = synchronized(stores) {
        stores.values.forEach(Store::clearInMemory)
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/NuvioNative")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("NuvioNative")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio-native")
            }
        }
    }

    private fun resolveCacheDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Caches/NuvioNative")
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let(Paths::get) ?: userHome.resolve("AppData/Local")).resolve("NuvioNative/Cache")
            }
            else -> {
                val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                (xdgCache?.let(Paths::get) ?: userHome.resolve(".cache")).resolve("nuvio-native")
            }
        }
    }

    internal class Store(
        private val file: Path,
    ) {
        private data class Snapshot(val properties: Properties, val bytes: ByteArray)
        private class InvalidSnapshot(message: String, cause: Throwable? = null) : IOException(message, cause)

        private val backup = file.resolveSibling(file.fileName.toString() + ".bak")
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false
        private var committed: ByteArray? = null

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        /**
         * Restore a damaged value from the preceding committed generation.
         * Repair just this key so newer values for other profiles are retained.
         */
        fun getStringValidated(key: String, valid: (String) -> Boolean): String? = synchronized(lock) {
            ensureLoaded()
            val current = properties.getProperty(key) ?: return null
            if (valid(current)) return current
            val previous = runCatching { readSnapshot(backup) }.getOrNull()
                ?.properties?.getProperty(key)?.takeIf(valid)
                ?: throw IOException("Saved preference is damaged and no valid backup exists: " + file)
            // Keep the valid backup until the repaired primary is fully committed.
            mutate(preserveBackup = true) {
                properties.setProperty(key, previous)
                true
            }
            previous
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            mutate {
                if (value == null) {
                    properties.remove(key) != null
                } else {
                    properties.setProperty(key, value) != value
                }
            }
        }

        /**
         * Secret-bearing preference files may have a plaintext older generation in .bak.
         * Once the protected primary generation is durable, replace the backup with
         * that same protected generation; never retain historical cleartext secrets.
         */
        fun putProtectedString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            mutate {
                if (value == null) properties.remove(key) != null
                else properties.setProperty(key, value) != value
            }
            committed?.let { replaceSynced(backup, it) }
        }

        fun scrubProtectedBackup() = synchronized(lock) {
            ensureLoaded()
            committed?.let { current ->
                val existing = runCatching { Files.readAllBytes(backup) }.getOrNull()
                if (existing == null || !existing.contentEquals(current)) replaceSynced(backup, current)
            }
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            mutate { properties.remove(key) != null }
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            mutate {
                var changed = false
                keys.forEach { key ->
                    if (properties.remove(key) != null) changed = true
                }
                changed
            }
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
            committed = null
        }

        private fun ensureLoaded() {
            if (loaded) return
            val primary = runCatching {
                readSnapshot(file)?.also { current ->
                    // Before the first upgrade, the only valid legacy + backup
                    // pair is the identical snapshot copied before replacement.
                    // Never treat a damaged sealed primary as an empty legacy one.
                    if (!hasIntegrityHeader(current.bytes)) {
                        val previous = runCatching { readSnapshot(backup) }.getOrNull()
                        if (previous != null && !current.bytes.contentEquals(previous.bytes)) {
                            throw InvalidSnapshot("Unsealed preference file differs from recovery backup: " + file)
                        }
                    }
                }
            }
            // A transient read/permission failure is not evidence of corruption.
            // Replacing that primary with an older backup could lose newer data.
            primary.exceptionOrNull()?.let { if (it !is InvalidSnapshot) throw it }
            val snapshot = primary.getOrNull() ?: run {
                val fallback = runCatching { readSnapshot(backup) }
                val previous = fallback.getOrNull()
                if (previous == null) {
                    if (primary.isSuccess && fallback.isSuccess) {
                        loaded = true // New store; neither generation exists.
                        return
                    }
                    throw IOException(
                        "Saved preferences are unreadable and no valid backup exists: " + file,
                        primary.exceptionOrNull() ?: fallback.exceptionOrNull(),
                    )
                }
                // A complete, verified previous generation can recover a missing,
                // truncated or otherwise unreadable primary without losing data.
                replaceSynced(file, previous.bytes)
                previous
            }
            properties.clear()
            properties.putAll(snapshot.properties)
            committed = snapshot.bytes
            loaded = true
        }

        private fun mutate(preserveBackup: Boolean = false, change: () -> Boolean) {
            val before = Properties().also { it.putAll(properties) }
            if (!change()) return
            try {
                persist(preserveBackup)
            } catch (error: Exception) {
                // A failed save must not make a subsequent identical update a no-op.
                properties.clear()
                properties.putAll(before)
                throw error
            }
        }

        private fun persist(preserveBackup: Boolean) {
            val next = sealedRecord(properties)
            Files.createDirectories(file.parent)
            val temporary = writeSyncedTemp(next)
            try {
                // Publish the old verified generation before replacing the primary.
                // A crash at any point leaves either the primary or backup readable.
                if (!preserveBackup) committed?.let { replaceSynced(backup, it) }
                moveReplacing(temporary, file)
                syncParentDirectory()
                committed = next
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun sealedRecord(values: Properties): ByteArray {
            val body = ByteArrayOutputStream().also {
                values.store(it, "Nuvio desktop preferences")
            }.toByteArray()
            // This is a Properties-compatible comment. The checksum detects
            // truncated/partially written files, which Properties.load accepts.
            val header = (RECORD_PREFIX + sha256(body) + "\n").toByteArray(Charsets.US_ASCII)
            return header + body
        }

        private fun readSnapshot(path: Path): Snapshot? {
            if (Files.notExists(path)) return null
            val bytes = Files.readAllBytes(path)
            if (bytes.isEmpty()) throw InvalidSnapshot("Saved preference file is empty: " + path)
            val newline = bytes.indexOf('\n'.code.toByte())
            val firstLine = String(
                bytes, 0, if (newline < 0) bytes.size else newline, Charsets.US_ASCII,
            ).trimEnd('\r')
            val body = if (firstLine.startsWith("# NuvioStore")) {
                val expected = firstLine.removePrefix(RECORD_PREFIX)
                if (!firstLine.startsWith(RECORD_PREFIX) || newline < 0 ||
                    expected.length != 64 || expected.any { it !in '0'..'9' && it !in 'a'..'f' }
                ) {
                    throw InvalidSnapshot("Invalid saved preference checksum: " + path)
                }
                bytes.copyOfRange(newline + 1, bytes.size).also {
                    if (sha256(it) != expected) {
                        throw InvalidSnapshot("Saved preferences failed checksum validation: " + path)
                    }
                }
            } else {
                // Existing .properties files have no integrity header. They are
                // still readable and will be upgraded on their first mutation.
                bytes
            }
            val parsed = Properties()
            try {
                ByteArrayInputStream(body).use { parsed.load(it) }
            } catch (error: IllegalArgumentException) {
                throw InvalidSnapshot("Malformed saved preferences: " + path, error)
            }
            return Snapshot(parsed, bytes)
        }

        private fun hasIntegrityHeader(bytes: ByteArray): Boolean =
            String(bytes, 0, bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it },
                Charsets.US_ASCII).startsWith(RECORD_PREFIX)

        private fun replaceSynced(target: Path, bytes: ByteArray) {
            Files.createDirectories(target.parent)
            val temporary = writeSyncedTemp(bytes)
            try {
                moveReplacing(temporary, target)
                syncParentDirectory()
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun writeSyncedTemp(bytes: ByteArray): Path {
            val temporary = Files.createTempFile(file.parent, file.fileName.toString() + "-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            } catch (error: Exception) {
                Files.deleteIfExists(temporary)
                throw error
            }
            return temporary
        }

        private fun moveReplacing(source: Path, target: Path) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // The previous verified backup still exists if a filesystem
                // does not support an atomic rename-over-existing.
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun syncParentDirectory() {
            // Directory fsync is supported on common Unix filesystems, but a
            // Windows directory cannot be opened as a normal FileChannel.
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
            try {
                FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
                // Some filesystems cannot force directories. Both files are
                // independently synced and the previous generation is retained.
            } catch (_: UnsupportedOperationException) {
                // Directory forcing is unavailable on this filesystem.
            }
        }

        private fun sha256(bytes: ByteArray): String {
            val digits = "0123456789abcdef"
            return buildString(64) {
                MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(digits[value shr 4])
                    append(digits[value and 15])
                }
            }
        }

        private companion object {
            const val RECORD_PREFIX = "# NuvioStore sha256="
        }
    }
}
