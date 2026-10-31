package com.nuvio.app.core.auth

internal expect object AuthStorage {
    fun loadImportedSettingsScope(): String?
    fun saveImportedSettingsScope(scope: String?)
    fun loadAnonymousUserId(): String?
    fun saveAnonymousUserId(userId: String)
    fun clearAnonymousUserId()
}
