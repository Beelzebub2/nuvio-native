package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.storage.AnonymousWorkspaceStorage
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private const val pendingAccountCleanupKey = "account_cleanup_required"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Neutral auth messages, such as an account that is waiting for the server owner's approval. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var detachedWorkspace: AuthState.Authenticated? = null
    private var remoteSignInRequested = false
    private var changingSession = false
    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null

    fun initialize() {
        if (initialized) return
        initialized = true

        val cleanupRecovery = runCatching {
            if (AnonymousWorkspaceStorage.loadValue(pendingAccountCleanupKey) != null) {
                wipeAccountDataRecoverably()
            }
        }
        if (cleanupRecovery.isFailure) {
            val error = checkNotNull(cleanupRecovery.exceptionOrNull())
            log.e(error) { "Previous account cleanup is incomplete; refusing to expose cached settings" }
            _error.value = error.message
            _state.value = AuthState.Unauthenticated
            return
        }

        // Personal builds need no upstream service secrets to browse or play local addons.
        val server = ServerConfigurationRepository.active.value
        if (server.backendUrl.isBlank() || server.publishableKey.isBlank()) {
            val existingId = AuthStorage.loadAnonymousUserId()
            if (existingId == null) signInAnonymously() else {
                _state.value = AuthState.Authenticated(userId = existingId, email = null, isAnonymous = true)
            }
            return
        }

        val savedAnonId = AuthStorage.loadAnonymousUserId()
        if (savedAnonId != null) {
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
        }

        sessionStatusJob = scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                if (changingSession) return@collect
                if (AuthStorage.loadAnonymousUserId() != null &&
                    (!remoteSignInRequested || status !is SessionStatus.Authenticated)) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        val userId = user?.id.orEmpty()
                        if (!validateRemoteSession(userId)) return@collect
                        acceptRemoteSession(userId, user?.email)
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _state.value = AuthState.Unauthenticated
                    }
                    is SessionStatus.Initializing -> {
                        if (AuthStorage.loadAnonymousUserId() == null) {
                            _state.value = AuthState.Loading
                        }
                    }
                    is SessionStatus.RefreshFailure -> {
                        _state.value = AuthState.Unauthenticated
                    }
                }
            }
        }
    }

    internal fun expectRemoteSession() { remoteSignInRequested = true }
    internal fun cancelRemoteSessionRequest() { remoteSignInRequested = false }

    internal suspend fun acceptRemoteSession(userId: String, email: String?) {
        val previous = (_state.value as? AuthState.Authenticated) ?: detachedWorkspace
        val accountChanged = previous != null && (previous.isAnonymous || previous.userId != userId)
        if (accountChanged) {
            val backup = runCatching {
                val old = checkNotNull(previous)
                if (old.isAnonymous) LocalSettingsImport.saveAnonymousWorkspace(old.userId)
                else LocalSettingsImport.savePendingBeforeSignOut()
            }
            if (backup.isFailure) {
                val failure = checkNotNull(backup.exceptionOrNull())
                log.e(failure) { "Could not preserve the previous account before accepting another session" }
                _error.value = failure.message ?: "Could not save local settings before switching accounts"
                remoteSignInRequested = false
                validatedRemoteUserId = null
                runCatching { SupabaseProvider.client.auth.clearSession() }
                    .onFailure { error -> log.w(error) { "Failed to discard the new remote session after backup failure" } }
                return
            }
        }
        if (accountChanged || AnonymousWorkspaceStorage.loadValue(pendingAccountCleanupKey) != null) {
            val cleaned = runCatching {
                _state.value = AuthState.Loading
                AuthStorage.clearAnonymousUserId()
                wipeAccountDataRecoverably()
            }
            if (cleaned.isFailure) {
                val failure = checkNotNull(cleaned.exceptionOrNull())
                log.e(failure) { "Could not clean previous account data before accepting a new session" }
                _error.value = failure.message ?: "Could not switch accounts safely"
                remoteSignInRequested = false
                validatedRemoteUserId = null
                detachedWorkspace = null
                _state.value = AuthState.Unauthenticated
                runCatching { SupabaseProvider.client.auth.clearSession() }
                    .onFailure { error -> log.w(error) { "Failed to discard new session after account cleanup failure" } }
                return
            }
        }
        detachedWorkspace = null
        remoteSignInRequested = false
        _state.value = AuthState.Authenticated(userId = userId, email = email, isAnonymous = false)
    }

    private suspend fun validateRemoteSession(userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            validatedRemoteUserId = userId
            true
        }.getOrElse { e ->
            if (isInvalidRemoteSessionError(e)) {
                log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                true
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        remoteSignInRequested = false
        if ((_state.value as? AuthState.Authenticated)?.isAnonymous == true) return
        runCatching {
            check(_state.value !is AuthState.Authenticated) { "Sign out before changing accounts" }
            if (detachedWorkspace?.isAnonymous == false ||
                AnonymousWorkspaceStorage.loadValue(pendingAccountCleanupKey) != null
            ) wipeAccountDataRecoverably()
            detachedWorkspace = null
            val userId = AnonymousWorkspaceStorage.restore() ?: Uuid.random().toString()
            AuthStorage.saveAnonymousUserId(userId)
            _state.value = AuthState.Authenticated(userId = userId, email = null, isAnonymous = true)
        }.onFailure {
            _error.value = it.message
            if (_state.value is AuthState.Loading) _state.value = AuthState.Unauthenticated
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        val isStarlitRequest = !ServerConfigurationRepository.active.value.supportsNuvioAccountFeatures
        return runCatching {
            _error.value = null
            _notice.value = null
            if (isStarlitRequest) {
                require(password.length >= 12) { getString(Res.string.auth_request_password_minimum) }
                // Account requests must never adopt a remote session before owner approval.
                cancelRemoteSessionRequest()
                changingSession = true
            } else {
                expectRemoteSession()
            }
            try {
                SupabaseProvider.client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
                if (isStarlitRequest) {
                    if (SupabaseProvider.client.auth.currentSessionOrNull() != null) {
                        SupabaseProvider.client.auth.clearSession()
                        val message = getString(Res.string.auth_request_unexpected_session)
                        _error.value = message
                        error(message)
                    }
                    _notice.value = getString(Res.string.auth_request_submitted_subtitle)
                } else if (SupabaseProvider.client.auth.currentSessionOrNull() == null) {
                    // Other servers can require email confirmation before sign-in.
                    cancelRemoteSessionRequest()
                    _notice.value = getString(Res.string.auth_sign_up_pending_confirmation)
                }
            } finally {
                if (isStarlitRequest) changingSession = false
            }
            Unit
        }.onFailure { e ->
            cancelRemoteSessionRequest()
            log.e(e) { "Email sign-up failed" }
            _error.value = (if (isStarlitRequest && e.isApprovalNotificationFailure()) {
                getString(Res.string.auth_request_notification_failed)
            } else null)
                ?: e.safeAuthErrorDescription()
                ?: (e as? IllegalArgumentException)?.message
                ?: _error.value
                ?: getString(Res.string.auth_sign_up_failed)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        _notice.value = null
        expectRemoteSession()
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }.onFailure { e ->
        cancelRemoteSessionRequest()
        log.e(e) { "Email sign-in failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_in_failed)
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        if (wasAnonymous) {
            val saved = runCatching { LocalSettingsImport.saveAnonymousWorkspace(anonymousRead.getOrThrow()!!) }
            if (saved.isFailure) {
                _error.value = saved.exceptionOrNull()?.message
                return saved
            }
        }
        if (!wasAnonymous) {
            val saved = runCatching { LocalSettingsImport.savePendingBeforeSignOut() }
            if (saved.isFailure) {
                _error.value = saved.exceptionOrNull()?.message
                return saved
            }
        }
        changingSession = true
        remoteSignInRequested = false
        _state.value = AuthState.Loading
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { wipeAccountDataRecoverably() }
        _state.value = AuthState.Unauthenticated
        changingSession = false

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        _error.value = null
        val current = _state.value as? AuthState.Authenticated
        detachedWorkspace = current
        if (current?.isAnonymous == true) {
            val saved = runCatching { LocalSettingsImport.saveAnonymousWorkspace(current.userId) }
            if (saved.isFailure) return saved
        } else LocalSettingsImport.savePendingBeforeSignOut()
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        val server = ServerConfigurationRepository.active.value
        // Anonymous workspaces have no remote session to clear.
        val sessionClear = if (
            current?.isAnonymous == true ||
            server.backendUrl.isBlank() ||
            server.publishableKey.isBlank()
        ) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.clearSession() }
        }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!isInvalidRemoteSessionError(error)) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        _error.value = null
        val current = _state.value as? AuthState.Authenticated
        val saved = runCatching {
            if (current?.isAnonymous == true) LocalSettingsImport.saveAnonymousWorkspace(current.userId)
            else LocalSettingsImport.savePendingBeforeSignOut()
        }
        saved.onFailure { error ->
            log.e(error) { "Could not save local settings before invalidating the session; continuing auth cleanup" }
            _error.value = error.message
        }
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        val localCleanup = runCatching { wipeAccountDataRecoverably() }
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after remote session invalidation" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            wipeAccountDataRecoverably()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
        _notice.value = null
    }

    private fun wipeAccountDataRecoverably() {
        // Survives a failed file removal and app restart, so no later login trusts partial data.
        AnonymousWorkspaceStorage.saveValue(pendingAccountCleanupKey, "true")
        LocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.saveValue(pendingAccountCleanupKey, null)
    }

    private fun isInvalidRemoteSessionError(error: Throwable): Boolean {
        val restError = error.findCause<RestException>()
        if (restError?.statusCode == 401 || restError?.statusCode == 403) return true

        val message = buildString {
            append(error.message.orEmpty())
            if (restError != null) {
                append(' ')
                append(restError.error)
                append(' ')
                append(restError.description)
            }
        }.lowercase()

        return (
            "jwt" in message &&
                ("invalid" in message || "expired" in message || "malformed" in message)
            ) || (
            "user" in message &&
                ("does not exist" in message || "not found" in message || "deleted" in message)
            ) || (
            "foreign key" in message &&
                ("auth.users" in message || "user_id" in message)
            )
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun Throwable.isApprovalNotificationFailure(): Boolean {
        val errorCode = findCause<RestException>()?.error?.trim()?.lowercase().orEmpty()
        return errorCode.endsWith("_failed") &&
            ("notification" in errorCode || "notifier" in errorCode || "notify" in errorCode)
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
