package com.nuvio.app.features.myanimelist

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.airing.NativeAiring
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop

internal data class MalConnectionState(val profileId: Int = -1, val connected: Boolean = false,
    val username: String = "", val clientId: String = "", val busy: Boolean = false,
    val connecting: Boolean = false, val revision: Long = 0, val error: String? = null)

internal object MyAnimeListRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DesktopStorage.store("myanimelist")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = MalClient()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(MalConnectionState())
    val state = mutableState.asStateFlow()
    @Volatile private var loadedProfile: Int? = null
    @Volatile private var generation = 0L
    @Volatile private var session: MalTokens? = null
    private var loadJob: Job? = null
    private var connectJob: Job? = null

    @Synchronized fun ensureLoaded() {
        val profile = ProfileRepository.activeProfileId
        if (loadedProfile == profile) return
        clearState()
        loadedProfile = profile
        val expected = generation
        mutableState.value = MalConnectionState(profileId = profile, revision = expected,
            clientId = store.getString("client_$profile").orEmpty(), busy = true)
        loadJob = scope.launch {
            try {
                val payload = store.getString("tokens_$profile")
                val tokens = payload?.let { json.decodeFromString<MalTokens>(protectMalCredential(it, decrypt = true)) }
                val context = currentCoroutineContext()
                synchronized(this@MyAnimeListRepository) {
                    context.ensureActive()
                    if (isCurrent(profile, expected)) {
                        session = tokens
                        mutableState.value = mutableState.value.copy(connected = tokens != null,
                            busy = false, username = tokens?.username.orEmpty())
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(profile, expected, "Reconnect MyAnimeList on this Windows account.") }
        }
    }

    @Synchronized fun clearState() {
        generation += 1
        loadJob?.cancel(); connectJob?.cancel()
        loadedProfile = null; session = null
        mutableState.value = MalConnectionState(revision = generation)
    }

    @Synchronized fun disconnect() {
        val profile = ProfileRepository.activeProfileId
        clearState()
        store.remove("tokens_$profile"); store.remove("username_$profile")
        ensureLoaded()
    }

    @Synchronized fun connect(clientId: String, clientSecret: String = "") {
        ensureLoaded()
        require(clientId.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Enter your MAL application's public client ID." }
        require(clientSecret.length <= 256 && clientSecret.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter a valid MyAnimeList client secret, or leave it empty for App Type Other."
        }
        check(!mutableState.value.busy || mutableState.value.connecting) { "Wait for account loading to finish." }
        val previous = connectJob
        previous?.cancel()
        generation += 1
        val profile = ProfileRepository.activeProfileId
        val expected = generation
        store.putString("client_$profile", clientId)
        mutableState.value = mutableState.value.copy(busy = true, connecting = true,
            revision = expected, clientId = clientId, error = null)
        connectJob = scope.launch {
            try {
                // Wait for the previous callback listener to release the fixed loopback port.
                previous?.join()
                currentCoroutineContext().ensureActive()
                val (code, verifier) = awaitMalAuthorization(clientId) { Desktop.getDesktop().browse(it) }
                val exchanged = client.exchange(clientId, code, verifier, MAL_REDIRECT, clientSecret)
                // A temporary profile API outage must not discard successfully authorized tokens.
                val username = try { client.username(exchanged.accessToken) }
                    catch (e: CancellationException) { throw e }
                    catch (e: MalHttpException) { if (e.status == 401) throw e else "Connected" }
                    catch (_: Exception) { "Connected" }
                val tokens = exchanged.copy(username = username)
                persist(tokens, profile, expected, finishConnect = true)
            } catch (e: TimeoutCancellationException) {
                fail(profile, expected, "Sign-in timed out. Check that the MAL App Redirect URL is $MAL_REDIRECT, then try again.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(profile, expected, e.message ?: "MyAnimeList sign-in failed.") }
        }
    }

    @Synchronized fun cancelConnect() {
        if (!mutableState.value.connecting) return
        generation += 1
        connectJob?.cancel()
        mutableState.value = mutableState.value.copy(busy = false, connecting = false, revision = generation)
    }

    @Synchronized private fun fail(profile: Int, expected: Long, message: String) {
        if (isCurrent(profile, expected)) mutableState.value = mutableState.value.copy(
            busy = false, connecting = false, error = message)
    }

    private fun isCurrent(profile: Int, expected: Long) =
        generation == expected && ProfileRepository.activeProfileId == profile

    private suspend fun persist(tokens: MalTokens, profile: Int, expected: Long, finishConnect: Boolean = false) {
        commitProtectedMalCredential(json.encodeToString(tokens), this,
            isCurrent = { isCurrent(profile, expected) },
            save = { encrypted ->
                store.putString("tokens_$profile", encrypted)
                session = tokens
                if (finishConnect) mutableState.value = mutableState.value.copy(
                    connected = true, username = tokens.username, busy = false, connecting = false)
            })
    }

    private suspend fun <T> authorized(action: suspend (String) -> T): T {
        val (profile, expected, loading) = synchronized(this) {
            ensureLoaded()
            check(!mutableState.value.connecting) { "Wait for MyAnimeList sign-in to finish." }
            Triple(ProfileRepository.activeProfileId, generation, loadJob)
        }
        loading?.join()
        return mutex.withLock {
            check(isCurrent(profile, expected)) { "Account changed; retry from the current profile." }
            var tokens = session ?: error("Connect MyAnimeList in Settings → Tracking first.")
            if (tokens.expiresAt - System.currentTimeMillis() < 60_000) {
                tokens = client.refresh(tokens); persist(tokens, profile, expected)
            }
            val result = try { action(tokens.accessToken) }
            catch (e: MalHttpException) {
                if (e.status != 401) throw e
                tokens = client.refresh(tokens); persist(tokens, profile, expected)
                action(tokens.accessToken)
            }
            currentCoroutineContext().ensureActive()
            check(isCurrent(profile, expected)) { "Account changed; retry from the current profile." }
            result
        }
    }

    suspend fun listStatus(id: Int) = authorized { client.listStatus(id, it) }
    suspend fun update(id: Int, status: MalStatus? = null, score: Int? = null) = authorized { client.update(id, it, status, score) }
    suspend fun search(name: String) = authorized { client.search(name, it) }
    suspend fun resolveId(meta: MetaDetails): Int? {
        val profile = ProfileRepository.activeProfileId
        store.getString("mapping_${profile}_${meta.type}:${meta.id}")?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        meta.links.firstNotNullOfOrNull { Regex("^https?://(?:www\\.)?myanimelist\\.net/anime/(\\d+)(?:/.*)?$")
            .find(it.url)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { id -> id > 0 } }?.let { return it }
        return NativeAiring.client.resolveMalId(meta.id)
    }
    fun link(meta: MetaDetails, id: Int) {
        require(id > 0)
        store.putString("mapping_${ProfileRepository.activeProfileId}_${meta.type}:${meta.id}", id.toString())
    }
}
internal actual fun clearMyAnimeListState() = MyAnimeListRepository.clearState()
