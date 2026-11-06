package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AccountCredentialAction
import com.nuvio.app.core.auth.AccountCredentialsRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.CredentialValidation
import com.nuvio.app.core.auth.PasswordRecoveryRepository
import com.nuvio.app.core.auth.PasswordRecoveryRequestResult
import com.nuvio.app.core.auth.isValidCredentialNonce
import com.nuvio.app.core.auth.validateEmailChange
import com.nuvio.app.core.auth.validatePasswordChange
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class CredentialsDialog { PASSWORD_CONFIRM, PASSWORD_CODE, EMAIL }

@Composable
internal fun AccountCredentialsCard(account: AuthState.Authenticated) {
    val activeServer by ServerConfigurationRepository.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dialog by remember(account.userId, activeServer.backendUrl) { mutableStateOf<CredentialsDialog?>(null) }
    var busy by remember(account.userId, activeServer.backendUrl) { mutableStateOf(false) }
    var currentPassword by remember(account.userId, activeServer.backendUrl) { mutableStateOf("") }
    var newPassword by remember(account.userId, activeServer.backendUrl) { mutableStateOf("") }
    var repeatValue by remember(account.userId, activeServer.backendUrl) { mutableStateOf("") }
    var verificationCode by remember(account.userId, activeServer.backendUrl) { mutableStateOf("") }
    var newEmail by remember(account.userId, activeServer.backendUrl) { mutableStateOf("") }
    var formError by remember(account.userId, activeServer.backendUrl) { mutableStateOf<String?>(null) }
    var feedback by remember(account.userId, activeServer.backendUrl) { mutableStateOf<String?>(null) }

    val passwordTitle = stringResource(Res.string.account_credentials_change_password)
    val emailTitle = stringResource(Res.string.account_credentials_change_email)
    val passwordDone = stringResource(Res.string.account_credentials_password_changed)
    val emailSent = stringResource(Res.string.account_credentials_email_sent)
    val resetSent = stringResource(Res.string.account_credentials_reset_sent)
    val resetFailed = stringResource(Res.string.auth_password_recovery_failed)
    val invalidPassword = stringResource(Res.string.account_credentials_password_weak)
    val mismatchPassword = stringResource(Res.string.account_credentials_password_mismatch)
    val invalidEmail = stringResource(Res.string.account_credentials_email_invalid)
    val mismatchEmail = stringResource(Res.string.account_credentials_email_mismatch)
    val sameEmail = stringResource(Res.string.account_credentials_email_same)
    val missingCode = stringResource(Res.string.account_credentials_code_invalid)
    val passwordFailed = stringResource(Res.string.account_credentials_password_auth_failed)
    val codeFailed = stringResource(Res.string.account_credentials_send_failed)
    val updateFailed = stringResource(Res.string.account_credentials_update_failed)
    val sessionChanged = stringResource(Res.string.account_credentials_session_changed)

    fun dismiss() {
        if (busy) return
        dialog = null
        currentPassword = ""
        newPassword = ""
        repeatValue = ""
        verificationCode = ""
        newEmail = ""
        formError = null
    }

    fun errorFor(result: AccountCredentialAction): String = when (result) {
        AccountCredentialAction.SUCCESS -> ""
        AccountCredentialAction.PASSWORD_CONFIRMATION_FAILED -> passwordFailed
        AccountCredentialAction.CODE_DELIVERY_FAILED -> codeFailed
        AccountCredentialAction.CODE_INVALID_OR_EXPIRED -> missingCode
        AccountCredentialAction.UNAVAILABLE, AccountCredentialAction.SESSION_CHANGED -> sessionChanged
        AccountCredentialAction.UPDATE_FAILED -> updateFailed
    }

    // A link clicked in a mail app returns to Starlit with a new confirmed profile.
    LaunchedEffect(account.userId, activeServer.backendUrl) {
        AccountCredentialsRepository.refreshVerifiedEmail()
    }
    DisposableEffect(lifecycle, account.userId, activeServer.backendUrl) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { AccountCredentialsRepository.refreshVerifiedEmail() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    NuvioSurfaceCard(modifier = Modifier.testTag("account-credentials-card")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.account_credentials_password_label),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.account_credentials_password_description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { dismiss(); dialog = CredentialsDialog.PASSWORD_CONFIRM },
                modifier = Modifier.testTag("account-change-password-button")) {
                Text(stringResource(Res.string.account_credentials_change))
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.AlternateEmail, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.account_credentials_email_label),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(account.email.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { dismiss(); dialog = CredentialsDialog.EMAIL },
                modifier = Modifier.testTag("account-change-email-button")) {
                Text(stringResource(Res.string.account_credentials_change))
            }
        }
    }

    if (dialog != null) {
        val activeDialog = dialog
        val changePasswordCode = activeDialog == CredentialsDialog.PASSWORD_CODE
        val isEmail = activeDialog == CredentialsDialog.EMAIL
        DialogSurface(
            onDismissRequest = { dismiss() },
            title = if (isEmail) emailTitle else passwordTitle,
            message = stringResource(when (activeDialog) {
                CredentialsDialog.PASSWORD_CONFIRM -> Res.string.account_credentials_password_step1
                CredentialsDialog.PASSWORD_CODE -> Res.string.account_credentials_password_step2
                CredentialsDialog.EMAIL -> Res.string.account_credentials_email_step
                null -> Res.string.account_credentials_password_step1
            }),
        ) {
            if (!changePasswordCode) {
                DialogTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = stringResource(Res.string.account_credentials_current_password),
                    modifier = Modifier.testTag("account-current-password-field"),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                )
            }
            if (changePasswordCode) {
                DialogTextField(
                    value = verificationCode,
                    onValueChange = { verificationCode = it.filter { c -> c in '0'..'9' }.take(8) },
                    label = stringResource(Res.string.account_credentials_verification_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    enabled = !busy,
                    modifier = Modifier.testTag("account-verification-code"),
                )
                DialogTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it.take(128) },
                    label = stringResource(Res.string.account_credentials_new_password),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                )
                DialogTextField(
                    value = repeatValue,
                    onValueChange = { repeatValue = it.take(128) },
                    label = stringResource(Res.string.account_credentials_confirm_password),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                )
            }
            if (isEmail) {
                DialogTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it.take(254) },
                    label = stringResource(Res.string.account_credentials_new_email),
                    modifier = Modifier.testTag("account-new-email-field"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !busy,
                )
                DialogTextField(
                    value = repeatValue,
                    onValueChange = { repeatValue = it.take(254) },
                    label = stringResource(Res.string.account_credentials_confirm_email),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !busy,
                )
            }
            formError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (activeDialog == CredentialsDialog.PASSWORD_CONFIRM) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        formError = null
                        scope.launch {
                            try {
                                val result = PasswordRecoveryRepository.requestPasswordReset(account.email.orEmpty())
                                if (result == PasswordRecoveryRequestResult.SENT) {
                                    busy = false
                                    dismiss()
                                    feedback = resetSent
                                } else {
                                    formError = resetFailed
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.account_credentials_use_reset_link)) }
            }
            DialogButtons {
                DialogButton(text = stringResource(Res.string.action_cancel), enabled = !busy,
                    modifier = Modifier.testTag("account-credentials-cancel"),
                    onClick = { dismiss() })
                DialogButton(
                    text = stringResource(if (changePasswordCode) Res.string.account_credentials_save_password
                        else if (isEmail) Res.string.account_credentials_send_confirmation
                        else Res.string.account_credentials_send_code),
                    style = DialogButtonStyle.Primary,
                    loading = busy,
                    enabled = !busy,
                    onClick = {
                        if (busy) return@DialogButton
                        formError = null
                        when (activeDialog) {
                            CredentialsDialog.PASSWORD_CONFIRM -> {
                                if (currentPassword.isBlank()) {
                                    formError = passwordFailed
                                } else {
                                    val password = currentPassword
                                    currentPassword = ""
                                    busy = true
                                    scope.launch {
                                        try {
                                            val result = AccountCredentialsRepository.sendPasswordChangeCode(password)
                                            if (result == AccountCredentialAction.SUCCESS) {
                                                dialog = CredentialsDialog.PASSWORD_CODE
                                            } else {
                                                formError = errorFor(result)
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }
                            CredentialsDialog.PASSWORD_CODE -> {
                                val validation = validatePasswordChange(newPassword, repeatValue)
                                formError = when {
                                    !isValidCredentialNonce(verificationCode) -> missingCode
                                    validation == CredentialValidation.WEAK_PASSWORD -> invalidPassword
                                    validation == CredentialValidation.PASSWORDS_DIFFER -> mismatchPassword
                                    else -> null
                                }
                                if (formError == null) {
                                    val password = newPassword
                                    val code = verificationCode
                                    newPassword = ""
                                    repeatValue = ""
                                    verificationCode = ""
                                    busy = true
                                    scope.launch {
                                        try {
                                            val result = AccountCredentialsRepository.changePassword(password, code)
                                            if (result == AccountCredentialAction.SUCCESS) {
                                                busy = false
                                                dismiss()
                                                feedback = passwordDone
                                            } else {
                                                formError = errorFor(result)
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }
                            CredentialsDialog.EMAIL -> {
                                val validation = validateEmailChange(account.email.orEmpty(), newEmail, repeatValue)
                                formError = when (validation) {
                                    CredentialValidation.INVALID_EMAIL -> invalidEmail
                                    CredentialValidation.EMAILS_DIFFER -> mismatchEmail
                                    CredentialValidation.EMAIL_UNCHANGED -> sameEmail
                                    else -> if (currentPassword.isBlank()) passwordFailed else null
                                }
                                if (formError == null) {
                                    val password = currentPassword
                                    val email = newEmail
                                    currentPassword = ""
                                    busy = true
                                    scope.launch {
                                        try {
                                            val result = AccountCredentialsRepository.requestEmailChange(email, password)
                                            if (result == AccountCredentialAction.SUCCESS) {
                                                busy = false
                                                dismiss()
                                                feedback = emailSent
                                            } else {
                                                formError = errorFor(result)
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }
                            null -> Unit
                        }
                    },
                )
            }
        }
    }
    NuvioStatusModal(
        title = stringResource(Res.string.account_credentials_notice_title),
        message = feedback.orEmpty(),
        isVisible = feedback != null,
        onConfirm = { feedback = null },
    )
}