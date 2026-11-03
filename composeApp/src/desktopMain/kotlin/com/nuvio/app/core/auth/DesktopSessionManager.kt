package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopCredentialProtection
import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Remote sessions survive a guest-workspace cleanup, but are explicitly removed
 * by Supabase's sign-out/clearSession paths. The server identity prevents tokens
 * from one configured backend from being loaded for another.
 */
internal class DesktopSessionManager(
    serverUrl: String,
    private val store: DesktopStorage.Store = DesktopStorage.Store(
        DesktopStorage.rootDir.resolve(SESSION_STORE_FILE),
    ),
) : SessionManager {
    private val server = serverUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        require(server.isNotBlank()) { "Account server is required for session persistence" }
    }

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        DesktopCredentialProtection.write(
            store, SESSION_KEY, json.encodeToString(StoredSession(server, session)),
        )
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        if (store.getString(SESSION_KEY) == null) return@withContext null
        val encoded = DesktopCredentialProtection.read(store, SESSION_KEY)
        val saved = encoded?.let { runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull() }
        if (saved == null || saved.server != server) {
            DesktopCredentialProtection.write(store, SESSION_KEY, null)
            return@withContext null
        }
        saved.session
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) {
        // Also replaces the recovery generation, so sign-out cannot restore an old token.
        DesktopCredentialProtection.write(store, SESSION_KEY, null)
    }

    @Serializable
    private data class StoredSession(val server: String, val session: UserSession)

    internal companion object {
        const val SESSION_STORE_FILE = "nuvio_remote_session.properties"
        const val SESSION_KEY = "supabase_session"
    }
}
