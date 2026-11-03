package com.nuvio.app.core.auth

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.CancellationException

private class AndroidPasskeyCeremony(private val context: Context) : PasskeyCeremony {
    private val manager = CredentialManager.create(context)

    override suspend fun create(optionsJson: String): String {
        try {
            val response = manager.createCredential(
                context = context,
                request = CreatePublicKeyCredentialRequest(requestJson = optionsJson),
            )
            return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                ?: error("The device did not create a passkey")
        } catch (error: CancellationException) {
            throw error
        } catch (error: CreateCredentialException) {
            throw IllegalStateException(passkeyProviderErrorMessage(error.message, registration = true), error)
        }
    }

    override suspend fun get(optionsJson: String): String {
        try {
            val response = manager.getCredential(
                context = context,
                request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson = optionsJson))),
            )
            return (response.credential as? PublicKeyCredential)?.authenticationResponseJson
                ?: error("No passkey was selected")
        } catch (error: CancellationException) {
            throw error
        } catch (error: GetCredentialException) {
            throw IllegalStateException(passkeyProviderErrorMessage(error.message, registration = false), error)
        }
    }
}

@Composable
actual fun rememberPasskeyCeremony(): PasskeyCeremony? {
    val context = LocalContext.current
    // Android 9+ supports native passkeys. Don't show a broken button on older devices.
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AndroidPasskeyCeremony(context) else null
    }
}
