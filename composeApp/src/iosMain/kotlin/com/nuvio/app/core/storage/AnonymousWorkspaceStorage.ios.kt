@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.storage

import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults

internal actual object AnonymousWorkspaceStorage {
    private val defaults get() = NSUserDefaults.standardUserDefaults
    private const val backupKey = "anonymous_workspace_preferences"
    private val codePath get() = "${NSHomeDirectory()}/Library/Application Support/nuvio_plugin_scrapers"
    private fun backupPath(slot: String) = "${NSHomeDirectory()}/Library/Application Support/anonymous_workspace_scrapers_$slot"

    actual fun loadValue(key: String): String? = defaults.stringForKey("anonymous_workspace_$key")
    actual fun saveValue(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey("anonymous_workspace_$key")
        else defaults.setObject(value, forKey = "anonymous_workspace_$key")
    }

    actual fun save(userId: String) {
        val slot = if (loadValue("slot") == "a") "b" else "a"
        saveValue("ready:$slot", null)
        val preferences = defaults.dictionaryRepresentation().filterKeys { key ->
            val name = key as? String ?: return@filterKeys false
            name == "profile_payload" || name.startsWith("settings_") ||
                name.matches(Regex(".+_[1-9][0-9]*"))
        }
        defaults.setObject(preferences, forKey = "${backupKey}_$slot")
        val backupPath = backupPath(slot)
        val files = NSFileManager.defaultManager
        if (files.fileExistsAtPath(backupPath)) check(files.removeItemAtPath(backupPath, null))
        val hasCode = files.fileExistsAtPath(codePath)
        if (hasCode) check(files.copyItemAtPath(codePath, backupPath, null))
        saveValue("code:$slot", hasCode.toString())
        saveValue("ready:$slot", userId)
        saveValue("slot", slot)
        saveValue("userId", userId)
    }

    actual fun restore(): String? {
        val userId = loadValue("userId") ?: return null
        val files = NSFileManager.defaultManager
        val slot = listOfNotNull(loadValue("slot"), "a", "b").distinct().firstOrNull { candidate ->
            val code = loadValue("code:$candidate")
            loadValue("ready:$candidate") == userId &&
                defaults.dictionaryForKey("${backupKey}_$candidate") != null &&
                (code == "false" || (code == "true" && files.fileExistsAtPath(backupPath(candidate))))
        } ?: error("Saved anonymous preferences are missing or incomplete")
        val backupPath = backupPath(slot)
        val preferences = checkNotNull(defaults.dictionaryForKey("${backupKey}_$slot"))
        preferences.forEach { (key, value) -> defaults.setObject(value, forKey = key as String) }
        if (files.fileExistsAtPath(codePath)) check(files.removeItemAtPath(codePath, null))
        if (loadValue("code:$slot") == "true") {
            check(files.copyItemAtPath(backupPath, codePath, null))
        }
        saveValue("slot", slot)
        return userId
    }
}
