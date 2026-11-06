package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.storage.AnonymousWorkspaceStorage
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.SupabaseClient
import io.ktor.http.Url
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class GoogleLinkFeedback(val userId: String, val message: String, val completed: Boolean)

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

    // Cached desktop profiles can be used when the remote session is missing.
    // Give their Account page a way back to the existing authentication screen.
    private val _signInScreenRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signInScreenRequests = _signInScreenRequests.asSharedFlow()

    fun requestSignInScreen() {
        if (_state.value is AuthState.Unauthenticated) _signInScreenRequests.tryEmit(Unit)
    }

    private var detachedWorkspace: AuthState.Authenticated? = null
    private var remoteSignInRequested = false
    private var changingSession = false
    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null
    private data class PendingGoogleLink(val accountId: String, val serverUrl: String, val client: SupabaseClient)
    private var pendingGoogleLink: PendingGoogleLink? = null
    private val _googleLinkFeedback = MutableStateFlow<GoogleLinkFeedback?>(null)
    val googleLinkFeedback: StateFlow<GoogleLinkFeedback?> = _googleLinkFeedback.asStateFlow()
    private data class DesktopGoogleLinkSession(val accountId: String, val job: Job)
    private val activeDesktopGoogleLink = MutableStateFlow<DesktopGoogleLinkSession?>(null)
    val desktopGoogleLinkInProgress: StateFlow<Boolean> = activeDesktopGoogleLink
        .map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

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
                        cancelDesktopGoogleAccountLink()
                        _state.value = AuthState.Unauthenticated
                    }
                    is SessionStatus.Initializing -> {
                        if (AuthStorage.loadAnonymousUserId() == null) {
                            _state.value = AuthState.Loading
                        }
                    }
                    is SessionStatus.RefreshFailure -> {
                        cancelDesktopGoogleAccountLink()
                        _state.value = AuthState.Unauthenticated
                    }
                }
            }
        }
    }

    internal fun expectRemoteSession() { remoteSignInRequested = true }
    internal fun cancelRemoteSessionRequest() { remoteSignInRequested = false }

    internal suspend fun acceptRemoteSession(userId: String, email: String?) {
        if (activeDesktopGoogleLink.value?.accountId?.let { it != userId } == true) {
            cancelDesktopGoogleAccountLink()
        }
        val previous = (_state.value as? AuthState.Authenticated) ?: detachedWorkspace
        val accountChanged = previous != null && (previous.isAnonymous || previous.userId != userId)
        if (accountChanged) _googleLinkFeedback.value = null
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

    /** Called after a verified GoTrue profile fetch, never for a pending email-change request. */
    internal fun applyConfirmedAccountEmail(userId: String, verifiedEmail: String?) {
        if (verifiedEmail.isNullOrBlank()) return
        val current = _state.value as? AuthState.Authenticated ?: return
        if (current.isAnonymous || current.userId != userId) return
        if (current.email != verifiedEmail) _state.value = current.copy(email = verifiedEmail)
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

    /** Approved Google identities only. GoTrue signup is disabled on the Starlit server. */
    suspend fun signInWithGoogle(): Result<Unit> = runCatching {
        _error.value = null
        _notice.value = null
        check(GoogleOAuthAvailability.isEnabled()) { "Google sign-in is not enabled on this server" }
        launchGoogleOAuth(SupabaseProvider.client)
    }.onFailure { e ->
        cancelRemoteSessionRequest()
        if (e is CancellationException) throw e
        // Provider errors can contain callback URLs; keep them out of logs.
        _error.value = (e as? GoogleAuthorizationFailure)?.message
            ?: e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_google_sign_in_failed)
    }

    /**
     * Linking is an authenticated, password-confirmed operation, not Google sign-in.
     * Return the provider URL rather than allowing the SDK to import a session
     * before we have verified the account identity on the PKCE callback.
     */
    internal suspend fun prepareGoogleAccountLink(password: String, redirectUrl: String = "nuvio://auth"): Result<String> = runCatching {
        val account = requireNotNull(_state.value as? AuthState.Authenticated)
        require(!account.isAnonymous) { "Sign in to your approved account first" }
        check(pendingGoogleLink == null) { "Complete or cancel the current Google linking request first" }
        check(GoogleOAuthAvailability.isEnabled()) { "Google linking is not enabled on this server" }
        val client = SupabaseProvider.client
        val serverUrl = ServerConfigurationRepository.active.value.backendUrl
        require(GoogleOAuthAvailability.isOfficialServer()) { "Google linking requires the official Starlit server" }
        check(client.auth.currentSessionOrNull()?.user?.id == account.userId) { "Sign in again before linking Google" }
        PasskeyRepository.reauthenticate(password)
        check(ServerConfigurationRepository.active.value.backendUrl == serverUrl &&
            SupabaseProvider.client === client &&
            client.auth.currentSessionOrNull()?.user?.id == account.userId) {
            "The signed-in account changed during password confirmation"
        }
        check(client.auth.retrieveUserForCurrentSession(false).identities.orEmpty().none { it.provider == "google" }) {
            "This account already has Google linked"
        }
        val url = requireNotNull(client.auth.linkIdentity(Google, redirectUrl = redirectUrl) {
            automaticallyOpenUrl = false
        }) { "Google did not return an authorization URL" }
        check(isTrustedGoogleAuthorizationUrl(url)) { "Google returned an untrusted authorization address" }
        pendingGoogleLink = PendingGoogleLink(account.userId, serverUrl, client)
        _googleLinkFeedback.value = GoogleLinkFeedback(
            account.userId, "Complete the Google account link in your browser.", completed = false,
        )
        url
    }.onFailure { error ->
        if (error is CancellationException) throw error
        val account = _state.value as? AuthState.Authenticated
        if (account != null && !account.isAnonymous) {
            _googleLinkFeedback.value = GoogleLinkFeedback(
                account.userId, "Couldn't start Google linking. Check your password and server settings.",
                completed = false,
            )
        }
    }

    fun cancelGoogleAccountLink() {
        val accountId = pendingGoogleLink?.accountId
        pendingGoogleLink = null
        if (accountId != null) {
            _googleLinkFeedback.value = GoogleLinkFeedback(accountId, "Google linking was canceled.", completed = false)
        }
    }

    /** Keep the desktop browser callback alive when the Account screen is closed or navigated away from. */
    fun beginDesktopGoogleAccountLink(password: String, openUrl: (String) -> Unit): Boolean {
        val account = (_state.value as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }
            ?: return false
        if (!GoogleOAuthAvailability.isOfficialServer()) return false
        val expectedServer = ServerConfigurationRepository.active.value
        // The job is created lazily, then published atomically before it can run.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                launchGoogleAccountLink(password, openUrl)
            } catch (error: CancellationException) {
                cancelGoogleAccountLink()
                throw error
            } catch (error: Exception) {
                cancelGoogleAccountLink()
                // The desktop callback must never expose URLs, authorization codes or provider errors.
                _googleLinkFeedback.value = GoogleLinkFeedback(
                    account.userId,
                    (error as? GoogleAuthorizationFailure)?.message
                        ?: "Google linking couldn't be completed. Check your browser and try again.",
                    completed = false,
                )
            }
        }
        val session = DesktopGoogleLinkSession(account.userId, job)
        if (!activeDesktopGoogleLink.compareAndSet(null, session)) {
            job.cancel()
            return false
        }
        // Completion callbacks also run if cancellation wins before a lazy coroutine starts.
        job.invokeOnCompletion {
            activeDesktopGoogleLink.compareAndSet(session, null)
        }
        // A logout/server change could occur between the initial check and publication.
        // Once published, concurrent cancellation always sees this job.
        if ((_state.value as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }?.userId != account.userId ||
            ServerConfigurationRepository.active.value != expectedServer
        ) {
            job.cancel()
            return false
        }
        job.start()
        return true
    }

    fun cancelDesktopGoogleAccountLink() {
        activeDesktopGoogleLink.value?.job?.cancel()
        cancelGoogleAccountLink()
    }

    internal fun handleGoogleOAuthCallback(url: String) {
        if (!GoogleOAuthAvailability.isOfficialServer() || !isStarlitOAuthCallback(url)) return
        val client = SupabaseProvider.client
        val code = Url(url).parameters.getAll("code")!!.single()
        scope.launch {
            try {
                completeGoogleOAuth(client, code)
            } catch (error: CancellationException) {
                if (pendingGoogleLink != null) cancelGoogleAccountLink()
                else cancelRemoteSessionRequest()
                throw error
            } catch (_: Exception) {
                val link = pendingGoogleLink
                if (link != null) {
                    pendingGoogleLink = null
                    _googleLinkFeedback.value = GoogleLinkFeedback(
                        link.accountId, "Google could not be linked. The account was not changed.", completed = false,
                    )
                } else {
                    cancelRemoteSessionRequest()
                    _error.value = getString(Res.string.auth_google_sign_in_failed)
                }
            }
        }
    }

    internal suspend fun completeGoogleOAuth(client: SupabaseClient, code: String) {
        check(GoogleOAuthAvailability.isOfficialServer() && SupabaseProvider.client === client) {
            "The account server changed during Google sign-in"
        }
        client.auth.awaitInitialization()
        val session = client.auth.exchangeCodeForSession(code, saveSession = false)
        check(GoogleOAuthAvailability.isOfficialServer() && SupabaseProvider.client === client) {
            "The account server changed during Google verification"
        }
        val link = pendingGoogleLink
        if (link != null) {
            val currentAccount = (_state.value as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }
            val linkedAccountId = session.user?.id ?: client.auth.retrieveUser(session.accessToken).id
            check(link.client === client && link.serverUrl == ServerConfigurationRepository.active.value.backendUrl &&
                isValidGoogleLinkResult(link.accountId, currentAccount?.userId, linkedAccountId,
                    GoogleOAuthAvailability.isOfficialServer()) &&
                client.auth.currentSessionOrNull()?.user?.id == link.accountId) {
                "Google linking cannot replace the signed-in Starlit account"
            }
            client.auth.importSession(session)
            pendingGoogleLink = null
            _googleLinkFeedback.value = GoogleLinkFeedback(
                link.accountId, "Your Google account is now linked.", completed = true,
            )
            return
        }
        // A Google sign-in callback must never silently replace an already signed-in account.
        check((_state.value as? AuthState.Authenticated)?.isAnonymous != false) {
            "Unsolicited Google sign-in while another account is active"
        }
        // Restore intent after PKCE verification, including a callback after process restart.
        // Cached remote sessions must remain isolated from the guest workspace until then.
        _error.value = null
        expectRemoteSession()
        try {
            client.auth.importSession(session)
        } catch (error: Throwable) {
            cancelRemoteSessionRequest()
            throw error
        }
    }

    suspend fun signInWithPasskey(ceremony: PasskeyCeremony): Result<Unit> {
        _error.value = null
        _notice.value = null
        return try {
            PasskeyRepository.signIn(ceremony)
            Result.success(Unit)
        } catch (error: CancellationException) {
            cancelRemoteSessionRequest()
            throw error
        } catch (error: Throwable) {
            cancelRemoteSessionRequest()
            _error.value = error.message?.takeIf { it.length <= 180 }
                ?: "Couldn't sign in with your passkey. Try again."
            Result.failure(error)
        }
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        cancelDesktopGoogleAccountLink()
        pendingGoogleLink = null
        _googleLinkFeedback.value = null
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
        cancelDesktopGoogleAccountLink()
        _googleLinkFeedback.value = null
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
        cancelDesktopGoogleAccountLink()
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
        cancelDesktopGoogleAccountLink()
        _googleLinkFeedback.value = null
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
        cancelDesktopGoogleAccountLink()
        val account = _state.value as? AuthState.Authenticated
        val backendUrl = ServerConfigurationRepository.active.value.backendUrl
        SupabaseProvider.client.functions.invoke("delete-account")
        if (account != null && !account.isAnonymous) {
            LocalSettingsImport.forgetAccountDownloadBackups(backendUrl, account.userId)
        }
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
