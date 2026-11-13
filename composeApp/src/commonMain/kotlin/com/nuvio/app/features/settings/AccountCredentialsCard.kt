package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLabeledField
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.nuvio.app.core.ui.NuvioStatusModal
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

    NuvioGroup(modifier = Modifier.testTag("account-credentials-card")) {
        CredentialRow(
            icon = Icons.Rounded.AlternateEmail,
            title = stringResource(Res.string.account_credentials_email_label),
            description = account.email.orEmpty(),
            buttonTag = "account-change-email-button",
            onChange = { dismiss(); dialog = CredentialsDialog.EMAIL },
        )
        NuvioGroupDivider()
        CredentialRow(
            icon = Icons.Rounded.Lock,
            title = stringResource(Res.string.account_credentials_password_label),
            description = stringResource(Res.string.account_credentials_password_description),
            buttonTag = "account-change-password-button",
            onChange = { dismiss(); dialog = CredentialsDialog.PASSWORD_CONFIRM },
        )
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
                NuvioLabeledField(label = stringResource(Res.string.account_credentials_current_password)) {
                    PasswordInput(
                        value = currentPassword,
                        onValueChange = { currentPassword = it },
                        enabled = !busy,
                        modifier = Modifier.testTag("account-current-password-field"),
                    )
                }
            }
            if (changePasswordCode) {
                NuvioLabeledField(label = stringResource(Res.string.account_credentials_verification_code)) {
                    NuvioInputField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it.filter { c -> c in '0'..'9' }.take(8) },
                        placeholder = stringResource(Res.string.account_credentials_code_placeholder),
                        minHeight = 48.dp,
                        enabled = !busy,
                        isError = formError == missingCode,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = MaterialTheme.typography.bodyLarge.tabular,
                        modifier = Modifier.testTag("account-verification-code"),
                    )
                }
                val passwordRuleBroken = formError == invalidPassword
                NuvioLabeledField(
                    label = stringResource(Res.string.account_credentials_new_password),
                    helper = invalidPassword,
                    isError = passwordRuleBroken,
                ) {
                    PasswordInput(
                        value = newPassword,
                        onValueChange = { newPassword = it.take(128) },
                        enabled = !busy,
                        isError = passwordRuleBroken,
                    )
                }
                NuvioLabeledField(label = stringResource(Res.string.account_credentials_confirm_password)) {
                    PasswordInput(
                        value = repeatValue,
                        onValueChange = { repeatValue = it.take(128) },
                        enabled = !busy,
                        isError = formError == mismatchPassword,
                    )
                }
            }
            if (isEmail) {
                NuvioLabeledField(label = stringResource(Res.string.account_credentials_new_email)) {
                    NuvioInputField(
                        value = newEmail,
                        onValueChange = { newEmail = it.take(254) },
                        placeholder = "name@example.com",
                        minHeight = 48.dp,
                        enabled = !busy,
                        isError = formError == invalidEmail || formError == sameEmail,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.testTag("account-new-email-field"),
                    )
                }
                NuvioLabeledField(label = stringResource(Res.string.account_credentials_confirm_email)) {
                    NuvioInputField(
                        value = repeatValue,
                        onValueChange = { repeatValue = it.take(254) },
                        placeholder = "name@example.com",
                        minHeight = 48.dp,
                        enabled = !busy,
                        isError = formError == mismatchEmail,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                }
            }
            formError?.takeIf { it != invalidPassword }?.let {
                Text(it, style = MaterialTheme.nuvioTypeScale.bodySm, color = MaterialTheme.nuvio.colors.danger)
            }
            if (activeDialog == CredentialsDialog.PASSWORD_CONFIRM) {
                NuvioActionLabel(
                    text = stringResource(Res.string.account_credentials_use_reset_link),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 6.dp).alpha(if (busy) MaterialTheme.nuvio.opacity.disabled else 1f),
                    onClick = {
                        if (busy) return@NuvioActionLabel
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
                )
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

/** An account detail with its value and a Change pill. */
@Composable
private fun CredentialRow(
    icon: ImageVector,
    title: String,
    description: String,
    buttonTag: String,
    onChange: () -> Unit,
) {
    NuvioGroupRow(
        title = title,
        description = description,
        titleMaxLines = 1,
        descriptionMaxLines = 2,
        leading = { NuvioIconChip(icon) },
        trailing = {
            NuvioPillButton(
                text = stringResource(Res.string.account_credentials_change),
                onClick = onChange,
                modifier = Modifier.testTag(buttonTag),
            )
        },
    )
}

/** A password field with a show/hide button. */
@Composable
private fun PasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    NuvioInputField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "",
        modifier = modifier,
        minHeight = 48.dp,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingContent = {
            NuvioSquareIconButton(
                icon = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = stringResource(if (visible) Res.string.cd_hide_password else Res.string.cd_show_password),
                onClick = { visible = !visible },
                size = 36.dp,
                style = NuvioSquareIconButtonStyle.Ghost,
                tint = MaterialTheme.nuvio.colors.textMuted,
            )
        },
    )
}
