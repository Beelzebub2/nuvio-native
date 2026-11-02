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

private class AndroidPasskeyCeremony(private val context: Context) : PasskeyCeremony {
    private val manager = CredentialManager.create(context)

    override suspend fun create(optionsJson: String): String {
        val response = manager.createCredential(
            context = context,
            request = CreatePublicKeyCredentialRequest(requestJson = optionsJson),
        )
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: error("The device did not create a passkey")
    }

    override suspend fun get(optionsJson: String): String {
        val response = manager.getCredential(
            context = context,
            request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson = optionsJson))),
        )
        return (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: error("No passkey was selected")
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
