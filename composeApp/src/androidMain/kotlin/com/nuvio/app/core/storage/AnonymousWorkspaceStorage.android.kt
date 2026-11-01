package com.nuvio.app.core.storage

import android.content.Context
import android.content.SharedPreferences

internal actual object AnonymousWorkspaceStorage {
    private const val backupMarker = "__anonymous_workspace_backup_owner"
    private val context get() = checkNotNull(PlatformLocalAccountDataCleaner.appContext)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val metadata get() = prefs("anonymous_workspace_metadata")

    actual fun loadValue(key: String): String? = metadata.getString(key, null)
    actual fun saveValue(key: String, value: String?) {
        check(metadata.edit().putString(key, value).commit()) { "Could not save anonymous workspace" }
    }

    actual fun loadSavedPreference(storeName: String, key: String): String? {
        val userId = loadValue("userId") ?: return null
        val slot = listOfNotNull(loadValue("slot"), "a", "b").distinct()
            .firstOrNull { isCompleteSlot(it, userId) }
            ?: error("Saved anonymous preferences are missing or incomplete")
        val backup = prefs("anonymous_workspace_${slot}_$storeName")
        if (backup.getString(backupMarker, null) != userId) return null
        return backup.getString(key, null)
    }

    actual fun save(userId: String) {
        val slot = if (loadValue("slot") == "a") "b" else "a"
        saveValue("ready:$slot", null)
        names.forEach { name -> copy(prefs(name), prefs("anonymous_workspace_${slot}_$name"), marker = userId) }
        val code = context.filesDir.resolve("nuvio_plugin_scrapers")
        val backup = context.filesDir.resolve("anonymous_workspace_scrapers_$slot")
        check(!backup.exists() || backup.deleteRecursively())
        val hasCode = code.exists()
        if (hasCode) check(code.isDirectory && code.copyRecursively(backup, overwrite = true))
        saveValue("code:$slot", hasCode.toString())
        saveValue("ready:$slot", userId)
        saveValue("slot", slot)
        saveValue("userId", userId)
    }

    actual fun restore(): String? {
        val userId = loadValue("userId") ?: return null
        val preferred = loadValue("slot")
        val slot = listOfNotNull(preferred, "a", "b").distinct().firstOrNull { isCompleteSlot(it, userId) }
            ?: error("Saved anonymous preferences are missing or incomplete")
        names.forEach { name ->
            val saved = prefs("anonymous_workspace_${slot}_$name")
            if (saved.getString(backupMarker, null) == userId) copy(saved, prefs(name))
        }
        val backup = context.filesDir.resolve("anonymous_workspace_scrapers_$slot")
        val code = context.filesDir.resolve("nuvio_plugin_scrapers")
        if (code.exists()) check(code.deleteRecursively())
        if (loadValue("code:$slot") == "true") check(backup.copyRecursively(code, overwrite = true))
        saveValue("slot", slot)
        return userId
    }

    private fun isCompleteSlot(slot: String, userId: String): Boolean = runCatching {
        if (loadValue("ready:$slot") != userId) return@runCatching false
        if (requiredNames.any { name ->
                prefs("anonymous_workspace_${slot}_$name").getString(backupMarker, null) != userId
            }) return@runCatching false
        val code = loadValue("code:$slot") ?: return@runCatching false
        code == "false" || (code == "true" && context.filesDir.resolve("anonymous_workspace_scrapers_$slot").isDirectory)
    }.getOrDefault(false)

    private val requiredNames get() = (PlatformLocalAccountDataCleaner.preferenceNames - "nuvio_downloads" + listOf(
        "nuvio_card_depth_style", "nuvio_custom_poster_url", "nuvio_tmdb_settings", "nuvio_meta_screen_settings",
    )).distinct().filter { it != "nuvio_auth" }

    private val names get() = requiredNames + "nuvio_downloads"

    private fun copy(source: SharedPreferences, destination: SharedPreferences, marker: String? = null) {
        val editor = destination.edit().clear()
        source.all.forEach { (key, value) ->
            if (key == backupMarker) return@forEach
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (marker != null) editor.putString(backupMarker, marker)
        check(editor.commit()) { "Could not copy anonymous preferences" }
    }
}
