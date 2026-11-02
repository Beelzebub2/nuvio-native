package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.isDesktop
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.LocalSettingsImport
import com.nuvio.app.core.auth.PasskeyRepository
import com.nuvio.app.core.auth.RegisteredPasskey
import com.nuvio.app.core.auth.rememberPasskeyCeremony
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.auth_account_deletion_failed
import nuvio.composeapp.generated.resources.settings_account_delete_account
import nuvio.composeapp.generated.resources.settings_account_delete_account_description
import nuvio.composeapp.generated.resources.settings_account_delete_confirm_message
import nuvio.composeapp.generated.resources.settings_account_delete_confirm_title
import nuvio.composeapp.generated.resources.settings_account_email
import nuvio.composeapp.generated.resources.settings_account_not_signed_in
import nuvio.composeapp.generated.resources.settings_account_sign_out
import nuvio.composeapp.generated.resources.auth_add_passkey
import nuvio.composeapp.generated.resources.auth_passkey_added
import nuvio.composeapp.generated.resources.auth_passkey_confirm_password
import nuvio.composeapp.generated.resources.auth_passkey_manage
import nuvio.composeapp.generated.resources.auth_passkey_remove
import nuvio.composeapp.generated.resources.auth_passkey_removed
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_message
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_title
import nuvio.composeapp.generated.resources.settings_account_status
import nuvio.composeapp.generated.resources.settings_account_status_anonymous
import nuvio.composeapp.generated.resources.settings_account_status_signed_in
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.accountSettingsContent(
    isTablet: Boolean,
) {
    item {
        AccountSettingsBody(isTablet = isTablet)
    }
}

@Composable
private fun AccountSettingsBody(
    isTablet: Boolean,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var signOutErrorMessage by remember { mutableStateOf<String?>(null) }
    val signOutFallbackMessage = stringResource(Res.string.auth_sign_out_failed)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    val deleteAccountFallbackMessage = stringResource(Res.string.auth_account_deletion_failed)
    val canDeleteAccount = AppFeaturePolicy.accountDeletionEnabled &&
        ServerConfigurationRepository.active.value.supportsNuvioAccountFeatures && authState is AuthState.Authenticated

    if (isDesktop) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopAccountCard(authState = authState, onSignOutClick = { signOutErrorMessage = null; showSignOutConfirm = true })
            signOutErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LocalSettingsImportCard(authState)
            PasskeySecurityCard(authState)
            if (canDeleteAccount) {
                DesktopDeleteAccountRow(
                    errorMessage = deleteErrorMessage,
                    onDeleteClick = {
                        deleteErrorMessage = null
                        showDeleteConfirm = true
                    },
                )
            }
        }
    } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioSurfaceCard {
            Text(
                text = stringResource(Res.string.compose_settings_page_account),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (val state = authState) {
                is AuthState.Authenticated -> {
                    AccountInfoRow(
                        label = stringResource(Res.string.settings_account_status),
                        value = if (state.isAnonymous) {
                            stringResource(Res.string.settings_account_status_anonymous)
                        } else {
                            stringResource(Res.string.settings_account_status_signed_in)
                        },
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    state.email?.takeUnless { state.isAnonymous }?.let { email ->
                        Spacer(modifier = Modifier.height(8.dp))
                        AccountInfoRow(
                            label = stringResource(Res.string.settings_account_email),
                            value = email,
                        )
                    }
                }
                else -> {
                    Text(
                        text = stringResource(Res.string.settings_account_not_signed_in),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        NuvioPrimaryButton(
            text = stringResource(Res.string.settings_account_sign_out),
            onClick = { signOutErrorMessage = null; showSignOutConfirm = true },
        )

        signOutErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LocalSettingsImportCard(authState)
        PasskeySecurityCard(authState)
        if (canDeleteAccount) {
            DeleteAccountCard(
                errorMessage = deleteErrorMessage,
                onDeleteClick = {
                    deleteErrorMessage = null
                    showDeleteConfirm = true
                },
            )
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_sign_out_confirm_title),
        message = stringResource(if ((authState as? AuthState.Authenticated)?.isAnonymous == true)
            Res.string.settings_account_anonymous_sign_out_message else Res.string.settings_account_sign_out_confirm_message),
        isVisible = showSignOutConfirm,
        confirmText = stringResource(Res.string.settings_account_sign_out),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            showSignOutConfirm = false
            scope.launch {
                val result = AuthRepository.signOut()
                signOutErrorMessage = if (result.isSuccess) null else AuthRepository.error.value ?: signOutFallbackMessage
            }
        },
        onDismiss = { showSignOutConfirm = false },
    )

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_delete_confirm_title),
        message = stringResource(Res.string.settings_account_delete_confirm_message),
        isVisible = showDeleteConfirm,
        isBusy = isDeletingAccount,
        destructive = true,
        confirmText = stringResource(Res.string.settings_account_delete_account),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            if (isDeletingAccount) return@NuvioStatusModal
            isDeletingAccount = true
            scope.launch {
                val result = AuthRepository.deleteAccount()
                isDeletingAccount = false
                showDeleteConfirm = false
                deleteErrorMessage = if (result.isSuccess) {
                    null
                } else {
                    AuthRepository.error.value
                        ?: result.exceptionOrNull()?.message
                        ?: deleteAccountFallbackMessage
                }
            }
        },
        onDismiss = {
            if (!isDeletingAccount) {
                showDeleteConfirm = false
            }
        },
    )
}

/** Passkey management never appears on guest/custom/upstream servers. */
@Composable
private fun PasskeySecurityCard(authState: AuthState) {
    val account = authState as? AuthState.Authenticated ?: return
    val ceremony = rememberPasskeyCeremony() ?: return
    if (account.isAnonymous || !PasskeyRepository.canUseOnActiveServer()) return

    var serverPasskeysEnabled by remember(account.userId) { mutableStateOf(false) }
    LaunchedEffect(account.userId) {
        serverPasskeysEnabled = PasskeyRepository.isEnabledOnActiveServer()
    }
    if (!serverPasskeysEnabled) return

    val scope = rememberCoroutineScope()
    var keys by remember(account.userId) { mutableStateOf<List<RegisteredPasskey>>(emptyList()) }
    var showPasswordConfirm by remember(account.userId) { mutableStateOf(false) }
    var removing by remember(account.userId) { mutableStateOf<RegisteredPasskey?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val added = stringResource(Res.string.auth_passkey_added)
    val removed = stringResource(Res.string.auth_passkey_removed)

    LaunchedEffect(account.userId) {
        keys = runCatching { PasskeyRepository.list() }.getOrDefault(emptyList())
    }

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.auth_passkey_manage),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Passkeys let you sign in using your device lock, fingerprint or face instead of a password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        keys.forEach { key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(key.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    removing = key
                    showPasswordConfirm = true
                    message = null
                }, enabled = !busy) {
                    Text(stringResource(Res.string.auth_passkey_remove))
                }
            }
        }
        NuvioPrimaryButton(
            text = stringResource(Res.string.auth_add_passkey),
            onClick = {
                removing = null
                password = ""
                message = null
                showPasswordConfirm = true
            },
        )
        message?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showPasswordConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) { password = ""; showPasswordConfirm = false }
            },
            title = { Text(stringResource(Res.string.auth_passkey_manage)) },
            text = {
                Column {
                    Text(stringResource(Res.string.auth_passkey_confirm_password))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(Res.string.compose_auth_password)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (busy || password.isBlank()) return@TextButton
                    busy = true
                    val secret = password
                    password = ""
                    val selectedKey = removing
                    scope.launch {
                        try {
                            if (selectedKey == null) PasskeyRepository.register(ceremony, secret)
                            else PasskeyRepository.revoke(selectedKey.id, secret)
                            keys = PasskeyRepository.list()
                            message = if (selectedKey == null) added else removed
                            showPasswordConfirm = false
                            removing = null
                        } catch (error: Throwable) {
                            message = error.message?.takeIf { it.length <= 180 }
                                ?: "Unable to update passkeys. Try again."
                            showPasswordConfirm = false
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy && password.isNotBlank()) {
                    Text(if (removing == null) stringResource(Res.string.auth_add_passkey)
                        else stringResource(Res.string.auth_passkey_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { password = ""; showPasswordConfirm = false }, enabled = !busy) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeleteAccountCard(
    errorMessage: String?,
    onDeleteClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.settings_account_delete_account),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.settings_account_delete_account_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onDeleteClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
            shape = tokens.shapes.button,
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.colors.danger,
                contentColor = tokens.colors.textInverse,
            ),
        ) {
            Text(
                text = stringResource(Res.string.settings_account_delete_account),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocalSettingsImportCard(authState: AuthState) {
    val signedIn = authState as? AuthState.Authenticated ?: return
    if (signedIn.isAnonymous || !LocalSettingsImport.isAvailable) return
    val profileState by com.nuvio.app.features.profiles.ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = profileState.activeProfile?.profileIndex
    val progress by LocalSettingsImport.progress.collectAsStateWithLifecycle()
    val current = progress?.takeIf(LocalSettingsImport::isCurrentProgress)
    var confirming by remember(signedIn.userId, profileId) { mutableStateOf(false) }
    val pending = LocalSettingsImport.hasPendingImport()
    val damaged = LocalSettingsImport.hasDamagedPendingImport()
    NuvioSurfaceCard {
        Text(stringResource(Res.string.settings_account_import_local), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.settings_account_import_description), style = MaterialTheme.typography.bodyMedium)
        val message = when {
            current?.outcome == com.nuvio.app.core.auth.LocalImportOutcome.Synced -> Res.string.settings_account_import_success
            current?.outcome == com.nuvio.app.core.auth.LocalImportOutcome.Failed || damaged -> Res.string.settings_account_import_failed
            pending -> Res.string.settings_account_import_pending
            else -> null
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        NuvioPrimaryButton(
            text = stringResource(if (pending) Res.string.settings_account_import_retry else Res.string.settings_account_import_local),
            enabled = current?.isRunning != true,
            onClick = {
                if (pending) LocalSettingsImport.startImport(retry = true) else confirming = true
            },
        )
    }
    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_import_local),
        message = stringResource(Res.string.settings_account_import_description),
        isVisible = confirming,
        confirmText = stringResource(Res.string.settings_account_import_local),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            confirming = false
            LocalSettingsImport.startImport()
        },
        onDismiss = { confirming = false },
    )
}
