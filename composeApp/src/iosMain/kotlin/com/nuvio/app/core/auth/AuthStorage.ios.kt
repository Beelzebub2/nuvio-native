package com.nuvio.app.core.auth

import platform.Foundation.NSUserDefaults

actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"

    actual fun loadImportedSettingsScope(): String? = NSUserDefaults.standardUserDefaults.stringForKey("imported_settings_scope")
    actual fun saveImportedSettingsScope(scope: String?) {
        if (scope == null) NSUserDefaults.standardUserDefaults.removeObjectForKey("imported_settings_scope")
        else NSUserDefaults.standardUserDefaults.setObject(scope, forKey = "imported_settings_scope")
    }

    actual fun loadAnonymousUserId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        NSUserDefaults.standardUserDefaults.setObject(userId, forKey = KEY_ANONYMOUS_USER_ID)
    }

    actual fun clearAnonymousUserId() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_ANONYMOUS_USER_ID)
    }
}
