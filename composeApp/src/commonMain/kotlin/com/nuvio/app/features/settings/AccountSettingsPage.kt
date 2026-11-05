package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.GoogleOAuthAvailability
import com.nuvio.app.core.auth.launchGoogleAccountLink
import com.nuvio.app.core.auth.LocalSettingsImport
import com.nuvio.app.core.auth.PasskeyRepository
import com.nuvio.app.core.auth.PasskeyRegistrationStep
import com.nuvio.app.core.auth.RegisteredPasskey
import com.nuvio.app.core.auth.rememberPasskeyCeremony
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.GoogleBrandMark
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioSectionLabel
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
    val desktopGoogleLinkBusy by AuthRepository.desktopGoogleLinkInProgress.collectAsStateWithLifecycle()
    val activeServer by ServerConfigurationRepository.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var signOutErrorMessage by remember { mutableStateOf<String?>(null) }
    val signOutFallbackMessage = stringResource(Res.string.auth_sign_out_failed)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    val deleteAccountFallbackMessage = stringResource(Res.string.auth_account_deletion_failed)
    val canDeleteAccount = AppFeaturePolicy.accountDeletionEnabled &&
        activeServer.supportsNuvioAccountFeatures &&
        (authState as? AuthState.Authenticated)?.isAnonymous == false
    val namedAccount = (authState as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }
    val canCheckGoogle = namedAccount != null && !isIos && GoogleOAuthAvailability.isOfficialServer()
    var googleRetry by remember(namedAccount?.userId, activeServer) { mutableStateOf(0) }
    var googleLinkAvailable by remember(namedAccount?.userId, activeServer) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(namedAccount?.userId, activeServer, googleRetry) {
        googleLinkAvailable = if (canCheckGoogle) GoogleOAuthAvailability.isEnabled() else false
    }

    if (isDesktop) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopAccountCard(
                authState = authState,
                onSignInClick = AuthRepository::requestSignInScreen,
                onSignOutClick = { signOutErrorMessage = null; showSignOutConfirm = true },
            )
            signOutErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (namedAccount != null && (canCheckGoogle || PasskeyRepository.canUseOnActiveServer())) {
                MobileAccountSectionHeading(stringResource(Res.string.settings_account_section_security))
                if (canCheckGoogle) {
                    when (googleLinkAvailable) {
                        true -> GoogleAccountLinkCard(authState, isAvailable = true)
                        false -> GoogleLinkAvailabilityRetry(isChecking = false, linkPending = desktopGoogleLinkBusy) { googleRetry++ }
                        null -> GoogleLinkAvailabilityRetry(isChecking = true, linkPending = desktopGoogleLinkBusy) { googleRetry++ }
                    }
                }
                PasskeySecurityCard(authState)
            }
            if (namedAccount != null && LocalSettingsImport.isAvailable) {
                MobileAccountSectionHeading(stringResource(Res.string.settings_account_section_data))
                LocalSettingsImportCard(authState)
            }
            MobileAccountSectionHeading(stringResource(Res.string.remote_qr_devices_heading))
            DesktopPhoneConnectionCard(authState)
            if (canDeleteAccount) {
                MobileAccountSectionHeading(stringResource(Res.string.settings_account_section_actions))
                DesktopDeleteAccountRow(
                    errorMessage = deleteErrorMessage,
                    onDeleteClick = {
                        deleteErrorMessage = null
                        showDeleteConfirm = true
                    },
                )
            }
        }
    } else {
        val canImportLocal = namedAccount != null && LocalSettingsImport.isAvailable
        val hasSignInMethods = namedAccount != null && (
            canCheckGoogle || PasskeyRepository.canUseOnActiveServer()
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 12.dp),
        ) {
            MobileAccountOverview(authState)

            if (hasSignInMethods) {
                MobileAccountSectionHeading(stringResource(Res.string.settings_account_section_security))
                if (canCheckGoogle) {
                    when (googleLinkAvailable) {
                        true -> GoogleAccountLinkCard(authState, isAvailable = true)
                        false -> GoogleLinkAvailabilityRetry(isChecking = false, linkPending = desktopGoogleLinkBusy) { googleRetry++ }
                        null -> GoogleLinkAvailabilityRetry(isChecking = true, linkPending = desktopGoogleLinkBusy) { googleRetry++ }
                    }
                }
                PasskeySecurityCard(authState)
            }

            if (canImportLocal) {
                MobileAccountSectionHeading(stringResource(Res.string.settings_account_section_data))
                LocalSettingsImportCard(authState)
            }

            if (authState is AuthState.Authenticated) {
                MobileAccountSectionHeading(stringResource(Res.string.settings_account_section_actions))
                NuvioSurfaceCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(Res.string.settings_account_sign_out_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            signOutErrorMessage = null
                            showSignOutConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                            .testTag("account-sign-out-button"),
                        shape = MaterialTheme.nuvio.shapes.button,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(
                            stringResource(Res.string.settings_account_sign_out),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                signOutErrorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
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

@Composable
private fun MobileAccountOverview(authState: AuthState) {
    val account = authState as? AuthState.Authenticated
    val accountDetail = when {
        account == null -> stringResource(Res.string.settings_account_not_signed_in)
        account.isAnonymous -> stringResource(Res.string.settings_account_guest_profile)
        else -> account.email ?: stringResource(Res.string.settings_account_status_signed_in)
    }
    val status = when {
        account == null -> stringResource(Res.string.settings_account_not_signed_in)
        account.isAnonymous -> stringResource(Res.string.settings_account_status_anonymous)
        else -> stringResource(Res.string.settings_account_status_signed_in)
    }
    val colors = MaterialTheme.colorScheme
    val badgeColor = if (account != null && !account.isAnonymous) colors.primary else colors.onSurfaceVariant

    NuvioSurfaceCard(modifier = Modifier.testTag("account-overview-card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(60.dp).background(
                    color = colors.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(18.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.settings_account_profile_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = accountDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_account_status),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(50.dp),
                color = badgeColor.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (account != null && !account.isAnonymous) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.PersonOutline
                        },
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileAccountSectionHeading(title: String) {
    NuvioSectionLabel(
        text = title,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun GoogleLinkAvailabilityRetry(isChecking: Boolean, linkPending: Boolean, onRetry: () -> Unit) {
    NuvioSurfaceCard {
        when {
            isDesktop && linkPending -> {
                Text(stringResource(Res.string.auth_google_link_browser_pending),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { AuthRepository.cancelDesktopGoogleAccountLink() }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
            isChecking -> {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.auth_google_link_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                Text(stringResource(Res.string.auth_google_link_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.auth_passkey_retry)) }
            }
        }
    }
}

/** Google linking is distinct from Google login: it never replaces the current account. */
@Composable
internal fun GoogleAccountLinkCard(
    authState: AuthState,
    isAvailable: Boolean = false,
    loadIdentity: suspend () -> Boolean = {
        SupabaseProvider.client.auth.retrieveUserForCurrentSession(false).identities.orEmpty()
            .any { it.provider == "google" }
    },
) {
    if (isIos || !isAvailable) return
    val account = (authState as? AuthState.Authenticated)?.takeUnless { it.isAnonymous } ?: return
    if (!GoogleOAuthAvailability.isOfficialServer()) return

    val feedback by AuthRepository.googleLinkFeedback.collectAsStateWithLifecycle()
    val accountFeedback = feedback?.takeIf { it.userId == account.userId }
    var linked by remember(account.userId) { mutableStateOf<Boolean?>(null) }
    var identityLoading by remember(account.userId) { mutableStateOf(true) }
    var identityRetry by remember(account.userId) { mutableStateOf(0) }
    // The Google consent screen is in a separate browser. Returning to Settings must
    // refresh the linked identities even if the app missed its callback while paused.
    var resumeCount by remember(account.userId) { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, account.userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(account.userId, resumeCount, accountFeedback?.completed, identityRetry) {
        identityLoading = true
        try {
            linked = loadIdentity()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            linked = null
        } finally {
            identityLoading = false
        }
    }

    var showPasswordConfirm by remember(account.userId) { mutableStateOf(false) }
    var password by remember(account.userId) { mutableStateOf("") }
    var localBusy by remember(account.userId) { mutableStateOf(false) }
    var linkJob by remember(account.userId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val desktopLinkBusy by AuthRepository.desktopGoogleLinkInProgress.collectAsStateWithLifecycle()
    val busy = if (isDesktop) desktopLinkBusy else localBusy
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    NuvioSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GoogleBrandMark(modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(Res.string.auth_google_link_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (linked == true || accountFeedback?.completed == true) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                linked == true || accountFeedback?.completed == true ->
                    stringResource(Res.string.auth_google_link_connected)
                else -> stringResource(Res.string.auth_google_link_description)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (linked == false && accountFeedback?.completed != true) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                enabled = !busy,
                onClick = { if (!busy) { password = ""; showPasswordConfirm = true } },
                modifier = (if (isDesktop) Modifier.widthIn(max = 280.dp).height(40.dp)
                    else Modifier.fillMaxWidth().height(52.dp))
                    .testTag("account-link-google-button"),
                shape = MaterialTheme.nuvio.shapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.nuvio.colors.accent,
                    contentColor = MaterialTheme.nuvio.colors.onAccent,
                ),
            ) {
                GoogleBrandMark(modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(Res.string.auth_google_link_button), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (identityLoading) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (linked == null) {
            Text(stringResource(Res.string.auth_google_link_status_failed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { identityRetry++ }) { Text(stringResource(Res.string.auth_passkey_retry)) }
        }
        if (busy && isDesktop) {
            TextButton(onClick = { AuthRepository.cancelDesktopGoogleAccountLink() }) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
        accountFeedback?.let { status ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showPasswordConfirm) {
        DialogSurface(
            onDismissRequest = { if (!busy) { password = ""; showPasswordConfirm = false } },
            title = stringResource(Res.string.auth_google_link_button),
            message = stringResource(Res.string.auth_google_link_password_prompt),
        ) {
            DialogTextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(Res.string.compose_auth_password),
                visualTransformation = PasswordVisualTransformation(),
            )
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    enabled = !busy,
                    onClick = { password = ""; showPasswordConfirm = false },
                )
                DialogButton(
                    text = stringResource(Res.string.auth_google_link_button),
                    style = DialogButtonStyle.Primary,
                    enabled = !busy && password.isNotBlank(),
                    onClick = {
                        if (busy || password.isBlank()) return@DialogButton
                        val confirmationPassword = password
                        password = ""
                        showPasswordConfirm = false
                        if (isDesktop) {
                            // Browser consent may outlive this screen; the repository owns the callback job.
                            AuthRepository.beginDesktopGoogleAccountLink(confirmationPassword) { uriHandler.openUri(it) }
                        } else {
                            localBusy = true
                            linkJob = scope.launch {
                                try {
                                    launchGoogleAccountLink(confirmationPassword) { uriHandler.openUri(it) }
                                } catch (error: CancellationException) {
                                    AuthRepository.cancelGoogleAccountLink()
                                    throw error
                                } catch (_: Exception) {
                                    AuthRepository.cancelGoogleAccountLink()
                                } finally {
                                    localBusy = false
                                    showPasswordConfirm = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

private data class PasskeyActionFeedback(val title: String, val message: String, val success: Boolean)

/** Passkeys belong to the account, not the current device or its chosen passkey provider. */
@Composable
internal fun PasskeySecurityCard(
    authState: AuthState,
    loadCapability: suspend () -> Boolean = { PasskeyRepository.isEnabledOnActiveServer() },
    loadPasskeys: suspend () -> List<RegisteredPasskey> = { PasskeyRepository.list() },
) {
    val account = authState as? AuthState.Authenticated ?: return
    if (account.isAnonymous || !PasskeyRepository.canUseOnActiveServer()) return
    val ceremony = rememberPasskeyCeremony()
    val canRegister = ceremony?.supportsRegistration == true
    val scope = rememberCoroutineScope()

    var serverPasskeysEnabled by remember(account.userId) { mutableStateOf<Boolean?>(null) }
    var capabilityRetry by remember(account.userId) { mutableStateOf(0) }
    LaunchedEffect(account.userId, capabilityRetry) {
        serverPasskeysEnabled = null
        serverPasskeysEnabled = loadCapability()
    }

    var keys by remember(account.userId) { mutableStateOf<List<RegisteredPasskey>?>(null) }
    var refreshingKeys by remember(account.userId) { mutableStateOf(false) }
    var listError by remember(account.userId) { mutableStateOf(false) }
    var listRefresh by remember(account.userId) { mutableStateOf(0) }
    var showPasswordConfirm by remember(account.userId) { mutableStateOf(false) }
    var removing by remember(account.userId) { mutableStateOf<RegisteredPasskey?>(null) }
    var password by remember(account.userId) { mutableStateOf("") }
    var busy by remember(account.userId) { mutableStateOf(false) }
    var operationStage by remember(account.userId) { mutableStateOf("") }
    var feedback by remember(account.userId) { mutableStateOf<PasskeyActionFeedback?>(null) }
    var showResultModal by remember(account.userId) { mutableStateOf(false) }
    val added = stringResource(Res.string.auth_passkey_added)
    val removed = stringResource(Res.string.auth_passkey_removed)
    val addSuccessTitle = stringResource(Res.string.auth_passkey_added_title)
    val removeSuccessTitle = stringResource(Res.string.auth_passkey_removed_title)
    val addFailureTitle = stringResource(Res.string.auth_passkey_add_failed_title)
    val removeFailureTitle = stringResource(Res.string.auth_passkey_remove_failed_title)
    val failureFallback = stringResource(Res.string.auth_passkey_operation_failed)
    val confirmingAccount = stringResource(Res.string.auth_passkey_confirming_account)
    val verifying = stringResource(Res.string.auth_passkey_verifying)
    val waitingForManager = stringResource(Res.string.auth_passkey_waiting_for_manager)
    val removingProgress = stringResource(Res.string.auth_passkey_removing)

    LaunchedEffect(account.userId, serverPasskeysEnabled, listRefresh) {
        if (serverPasskeysEnabled != true) return@LaunchedEffect
        refreshingKeys = true
        try {
            keys = loadPasskeys()
            listError = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep an old list if present, but never pretend a failed request means zero keys.
            listError = true
        } finally {
            refreshingKeys = false
        }
    }

    NuvioSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!isDesktop) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(Res.string.auth_passkey_manage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.auth_passkey_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (serverPasskeysEnabled == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.auth_passkey_loading), style = MaterialTheme.typography.bodySmall)
            }
        } else if (serverPasskeysEnabled == false) {
            Text(
                stringResource(Res.string.auth_passkey_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { capabilityRetry++ }) {
                Text(stringResource(Res.string.auth_passkey_retry))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.auth_passkey_registered),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = { listRefresh++ }, enabled = !refreshingKeys && !busy) {
                    Text(stringResource(Res.string.auth_passkey_refresh))
                }
            }
            if (refreshingKeys) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.auth_passkey_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (listError) {
                Text(
                    stringResource(Res.string.auth_passkey_list_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            keys?.let { registered ->
                if (registered.isEmpty() && !refreshingKeys && !listError) {
                    Text(
                        stringResource(Res.string.auth_passkey_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (registered.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.auth_passkey_count, registered.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    registered.forEach { key ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    key.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (key.createdAt.isNotBlank()) {
                                    Text(
                                        stringResource(Res.string.auth_passkey_created_at, key.createdAt.take(10)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                removing = key
                                password = ""
                                feedback = null
                                showPasswordConfirm = true
                            }, enabled = !busy) {
                                Text(stringResource(Res.string.auth_passkey_remove))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (canRegister) {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.auth_add_passkey),
                    modifier = if (isDesktop) Modifier.widthIn(max = 280.dp).height(40.dp) else Modifier,
                    enabled = !busy,
                    onClick = {
                        removing = null
                        password = ""
                        feedback = null
                        showPasswordConfirm = true
                    },
                )
            } else {
                Text(
                    stringResource(Res.string.auth_passkey_register_on_android),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            feedback?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showPasswordConfirm) {
        DialogSurface(
            onDismissRequest = {
                if (!busy) { password = ""; showPasswordConfirm = false; removing = null }
            },
            title = stringResource(if (removing == null) Res.string.auth_add_passkey else Res.string.auth_passkey_remove),
        ) {
            if (busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.nuvio.shapes.compactCard)
                        .background(MaterialTheme.nuvio.colors.textPrimary.copy(alpha = MaterialTheme.nuvio.opacity.subtle))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NuvioLoadingIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.nuvio.colors.accent)
                    Text(operationStage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                }
            } else {
                Text(
                    stringResource(if (removing == null)
                        Res.string.auth_passkey_confirm_password else Res.string.auth_passkey_remove_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(Res.string.compose_auth_password),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    enabled = !busy,
                    onClick = { password = ""; showPasswordConfirm = false; removing = null },
                )
                DialogButton(
                    text = stringResource(if (removing == null) Res.string.auth_add_passkey else Res.string.auth_passkey_remove),
                    style = if (removing == null) DialogButtonStyle.Primary else DialogButtonStyle.Destructive,
                    enabled = !busy && password.isNotBlank(),
                    onClick = {
                    if (busy || password.isBlank()) return@DialogButton
                    busy = true
                    val secret = password
                    password = ""
                    val selectedKey = removing
                    operationStage = if (selectedKey == null) confirmingAccount else removingProgress
                    scope.launch {
                        try {
                            if (selectedKey == null) {
                                PasskeyRepository.register(requireNotNull(ceremony), secret) { step ->
                                    operationStage = when (step) {
                                        PasskeyRegistrationStep.WAITING_FOR_MANAGER -> waitingForManager
                                        PasskeyRegistrationStep.VERIFYING_WITH_SERVER -> verifying
                                    }
                                }
                            } else {
                                PasskeyRepository.revoke(selectedKey.id, secret)
                            }
                            feedback = PasskeyActionFeedback(
                                if (selectedKey == null) addSuccessTitle else removeSuccessTitle,
                                if (selectedKey == null) added else removed,
                                success = true,
                            )
                            // Verification succeeded. Refresh independently so a list/network
                            // failure can never turn a successful registration into "failed".
                            listRefresh++
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            feedback = PasskeyActionFeedback(
                                if (selectedKey == null) addFailureTitle else removeFailureTitle,
                                error.message?.takeIf { it.length in 1..180 } ?: failureFallback,
                                success = false,
                            )
                        } finally {
                            busy = false
                            showPasswordConfirm = false
                            removing = null
                        }
                        showResultModal = true
                    }
                    },
                )
            }
        }
    }
    feedback?.let { result ->
        NuvioStatusModal(
            title = result.title,
            message = result.message,
            isVisible = showResultModal,
            onConfirm = { showResultModal = false },
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = tokens.colors.danger,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(Res.string.settings_account_delete_account),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.danger,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
internal fun LocalSettingsImportCard(authState: AuthState, isAvailable: Boolean = LocalSettingsImport.isAvailable) {
    val signedIn = authState as? AuthState.Authenticated ?: return
    if (signedIn.isAnonymous || !isAvailable) return
    val profileState by com.nuvio.app.features.profiles.ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = profileState.activeProfile?.profileIndex
    val progress by LocalSettingsImport.progress.collectAsStateWithLifecycle()
    val current = progress?.takeIf(LocalSettingsImport::isCurrentProgress)
    var confirming by remember(signedIn.userId, profileId) { mutableStateOf(false) }
    val pending = LocalSettingsImport.hasPendingImport()
    val damaged = LocalSettingsImport.hasDamagedPendingImport()
    NuvioSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!isDesktop) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                stringResource(Res.string.settings_account_import_local),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
            modifier = if (isDesktop) Modifier.widthIn(max = 280.dp).height(40.dp) else Modifier,
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
