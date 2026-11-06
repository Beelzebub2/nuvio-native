package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Credential changes are supported only on the server whose security settings we control. */
internal fun isOfficialCredentialServer(url: String): Boolean = isOfficialPasswordRecoveryServer(url)

internal enum class CredentialValidation {
    VALID,
    WEAK_PASSWORD,
    PASSWORDS_DIFFER,
    INVALID_EMAIL,
    EMAILS_DIFFER,
    EMAIL_UNCHANGED,
    INVALID_CODE,
}

internal fun validatePasswordChange(next: String, confirmation: String): CredentialValidation = when {
    next.length !in 12..128 -> CredentialValidation.WEAK_PASSWORD
    next != confirmation -> CredentialValidation.PASSWORDS_DIFFER
    else -> CredentialValidation.VALID
}

internal fun validateEmailChange(currentEmail: String, next: String, confirmation: String): CredentialValidation {
    val normalized = normalizePasswordRecoveryEmail(next) ?: return CredentialValidation.INVALID_EMAIL
    return when {
        normalizePasswordRecoveryEmail(confirmation) != normalized -> CredentialValidation.EMAILS_DIFFER
        currentEmail.equals(normalized, ignoreCase = true) -> CredentialValidation.EMAIL_UNCHANGED
        else -> CredentialValidation.VALID
    }
}

internal fun isValidCredentialNonce(value: String): Boolean =
    value.length in 6..8 && value.all { it in '0'..'9' }

enum class AccountCredentialAction {
    SUCCESS,
    UNAVAILABLE,
    SESSION_CHANGED,
    PASSWORD_CONFIRMATION_FAILED,
    CODE_DELIVERY_FAILED,
    CODE_INVALID_OR_EXPIRED,
    UPDATE_FAILED,
}

private data class CredentialSession(
    val userId: String,
    val email: String,
    val serverUrl: String,
    val client: SupabaseClient,
)

/**
 * Never handles service-role credentials or sends passwords through the app's own gateway.
 * GoTrue owns password-code expiry and double-confirmation links for email changes.
 * Pending state has only the account identity, never the current password, new password or OTP.
 */
object AccountCredentialsRepository {
    private val credentialChangeMutex = Mutex()
    private var pendingPasswordChange: Pair<String, String>? = null

    fun isAvailableOnActiveServer(): Boolean {
        val server = ServerConfigurationRepository.active.value
        return server.capabilities.emailPasswordAuth && isOfficialCredentialServer(server.backendUrl)
    }

    private fun session(): CredentialSession? {
        val account = (AuthRepository.state.value as? AuthState.Authenticated)
            ?.takeUnless { it.isAnonymous } ?: return null
        val server = ServerConfigurationRepository.active.value
        if (!isAvailableOnActiveServer() || account.email.isNullOrBlank()) return null
        val client = SupabaseProvider.client
        if (client.auth.currentSessionOrNull()?.user?.id != account.userId) return null
        return CredentialSession(account.userId, account.email, server.backendUrl, client)
    }

    private fun isSameSession(start: CredentialSession): Boolean {
        val account = AuthRepository.state.value as? AuthState.Authenticated
        return account?.isAnonymous == false && account.userId == start.userId &&
            ServerConfigurationRepository.active.value.backendUrl == start.serverUrl &&
            SupabaseProvider.client === start.client &&
            start.client.auth.currentSessionOrNull()?.user?.id == start.userId
    }

    suspend fun sendPasswordChangeCode(currentPassword: String): AccountCredentialAction =
        credentialChangeMutex.withLock {
            pendingPasswordChange = null
            val start = session() ?: return@withLock AccountCredentialAction.UNAVAILABLE
            if (currentPassword.isBlank()) return@withLock AccountCredentialAction.PASSWORD_CONFIRMATION_FAILED
            try {
                // Fresh password possession must be demonstrated before an email code is sent.
                PasskeyRepository.reauthenticate(currentPassword)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock AccountCredentialAction.PASSWORD_CONFIRMATION_FAILED
            }
            if (!isSameSession(start)) return@withLock AccountCredentialAction.SESSION_CHANGED
            try {
                start.client.auth.reauthenticate()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock AccountCredentialAction.CODE_DELIVERY_FAILED
            }
            if (!isSameSession(start)) return@withLock AccountCredentialAction.SESSION_CHANGED
            pendingPasswordChange = start.userId to start.serverUrl
            AccountCredentialAction.SUCCESS
        }

    suspend fun changePassword(newPassword: String, code: String): AccountCredentialAction =
        credentialChangeMutex.withLock {
            val start = session() ?: return@withLock AccountCredentialAction.UNAVAILABLE
            if (pendingPasswordChange != (start.userId to start.serverUrl)) {
                return@withLock AccountCredentialAction.SESSION_CHANGED
            }
            if (newPassword.length !in 12..128 || !isValidCredentialNonce(code)) {
                return@withLock AccountCredentialAction.CODE_INVALID_OR_EXPIRED
            }
            if (!isSameSession(start)) return@withLock AccountCredentialAction.SESSION_CHANGED
            try {
                start.client.auth.updateUser {
                    password = newPassword
                    nonce = code
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock AccountCredentialAction.CODE_INVALID_OR_EXPIRED
            }
            pendingPasswordChange = null
            if (!isSameSession(start)) return@withLock AccountCredentialAction.SESSION_CHANGED
            AccountCredentialAction.SUCCESS
        }

    suspend fun requestEmailChange(newEmail: String, currentPassword: String): AccountCredentialAction =
        credentialChangeMutex.withLock {
            pendingPasswordChange = null
            val start = session() ?: return@withLock AccountCredentialAction.UNAVAILABLE
            val normalized = normalizePasswordRecoveryEmail(newEmail)
                ?: return@withLock AccountCredentialAction.UPDATE_FAILED
            if (normalized.equals(start.email, ignoreCase = true)) {
                return@withLock AccountCredentialAction.UPDATE_FAILED
            }
            try {
                PasskeyRepository.reauthenticate(currentPassword)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock AccountCredentialAction.PASSWORD_CONFIRMATION_FAILED
            }
            if (!isSameSession(start)) return@withLock AccountCredentialAction.SESSION_CHANGED
            try {
                // An explicit null avoids a native-only redirect: the server's HTTPS site URL
                // receives users after GoTrue verifies BOTH old and new addresses.
                start.client.auth.updateUser(redirectUrl = null) { email = normalized }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock AccountCredentialAction.UPDATE_FAILED
            }
            if (!isSameSession(start)) return@withLock AccountCredentialAction.SESSION_CHANGED
            AccountCredentialAction.SUCCESS
        }

    /** Refresh only the confirmed address; never display email_change as already completed. */
    suspend fun refreshVerifiedEmail() {
        val start = session() ?: return
        try {
            val user = start.client.auth.retrieveUserForCurrentSession(false)
            if (user.id == start.userId && isSameSession(start)) {
                AuthRepository.applyConfirmedAccountEmail(start.userId, user.email)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline and transient errors must not sign out a valid account.
        }
    }
}