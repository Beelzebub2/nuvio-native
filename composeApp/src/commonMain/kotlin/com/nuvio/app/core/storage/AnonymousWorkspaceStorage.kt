package com.nuvio.app.core.storage

/** Kept outside the active account's wipe boundary. Contains local data, never a Supabase session. */
internal expect object AnonymousWorkspaceStorage {
    fun save(userId: String)
    fun restore(): String?
    fun loadValue(key: String): String?
    fun saveValue(key: String, value: String?)
}
