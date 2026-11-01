package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal actual object AnonymousWorkspaceStorage {
    private val directory get() = DesktopStorage.rootDir.resolve("anonymous-workspace")
    private val metadata by lazy { DesktopStorage.store("anonymous-workspace/metadata") }

    actual fun loadValue(key: String): String? = metadata.getString(key)
    actual fun saveValue(key: String, value: String?) = metadata.putString(key, value)

    actual fun loadSavedPreference(storeName: String, key: String): String? {
        if (loadValue("userId") == null) return null
        require(storeName.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid preference store name" }
        val snapshot = directory.resolve("preferences-${loadValue("slot") ?: "a"}")
        check(Files.isDirectory(snapshot)) { "Saved anonymous preferences are missing" }
        val file = snapshot.resolve("$storeName.properties")
        if (!Files.isRegularFile(file)) return null
        return DesktopStorage.Store(file).getString(key)
    }

    actual fun save(userId: String) {
        val slot = if (loadValue("slot") == "a") "b" else "a"
        val snapshot = directory.resolve("preferences-$slot")
        Files.createDirectories(snapshot)
        Files.list(snapshot).use { paths -> paths.forEach(Files::delete) }
        Files.list(DesktopStorage.rootDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".properties") }
                .filter { it.fileName.toString() !in excludedFiles }
                .forEach { Files.copy(it, snapshot.resolve(it.fileName), REPLACE_EXISTING) }
        }
        saveValue("slot", slot)
        saveValue("userId", userId)
    }

    actual fun restore(): String? {
        val userId = loadValue("userId") ?: return null
        val snapshot = directory.resolve("preferences-${loadValue("slot") ?: "a"}")
        check(Files.isDirectory(snapshot)) { "Anonymous preferences backup is missing" }
        Files.list(snapshot).use { paths ->
            paths.forEach { Files.copy(it, DesktopStorage.rootDir.resolve(it.fileName), REPLACE_EXISTING) }
        }
        DesktopStorage.reloadStores()
        return userId
    }

    private val excludedFiles = setOf("nuvio_auth.properties", "server_configuration.properties", "nuvio_sync_client_identity.properties")
}
