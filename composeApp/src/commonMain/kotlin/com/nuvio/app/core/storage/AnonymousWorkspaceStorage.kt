package com.nuvio.app.core.storage

/** Kept outside the active account's wipe boundary. Contains local data, never a Supabase session. */
internal expect object AnonymousWorkspaceStorage {
    fun save(userId: String)
    fun restore(): String?
    fun loadValue(key: String): String?
    fun saveValue(key: String, value: String?)
    /** Reads a saved guest preference without replacing the signed-in account's live preferences. */
    fun loadSavedPreference(storeName: String, key: String): String?
}
