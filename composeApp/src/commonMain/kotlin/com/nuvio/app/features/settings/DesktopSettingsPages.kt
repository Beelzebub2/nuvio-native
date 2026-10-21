package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.UpdatePreferences
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.about_licenses_attributions_subtitle
import nuvio.composeapp.generated.resources.about_supporters_contributors_subtitle
import nuvio.composeapp.generated.resources.changelog_description
import nuvio.composeapp.generated.resources.changelog_title
import nuvio.composeapp.generated.resources.compose_settings_page_licenses_attributions
import nuvio.composeapp.generated.resources.compose_settings_page_privacy_policy
import nuvio.composeapp.generated.resources.compose_settings_page_supporters_contributors
import nuvio.composeapp.generated.resources.compose_settings_root_privacy_policy_description
import nuvio.composeapp.generated.resources.settings_account_delete_account
import nuvio.composeapp.generated.resources.settings_account_delete_account_description
import nuvio.composeapp.generated.resources.settings_account_not_signed_in
import nuvio.composeapp.generated.resources.settings_account_sign_out
import nuvio.composeapp.generated.resources.settings_account_status_anonymous
import nuvio.composeapp.generated.resources.settings_account_status_signed_in
import nuvio.composeapp.generated.resources.settings_desktop_check_for_updates
import nuvio.composeapp.generated.resources.settings_desktop_current_version
import nuvio.composeapp.generated.resources.settings_desktop_current_version_detail
import nuvio.composeapp.generated.resources.updates_debug_test_description
import nuvio.composeapp.generated.resources.updates_debug_test_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.desktopAboutContent(
    showSupporters: Boolean,
    showChangelog: Boolean,
    onSupportersClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onChangelogClick: () -> Unit,
) {
    item(key = "desktop-about-identity") {
        SettingsGroup(isTablet = true) {
            SettingsAttribution(isTablet = true, centered = false)
        }
    }
    item(key = "desktop-about-links") {
        val uriHandler = LocalUriHandler.current
        val tokens = MaterialTheme.nuvio
        SettingsGroup(isTablet = true) {
            if (showSupporters) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_supporters_contributors),
                    description = stringResource(Res.string.about_supporters_contributors_subtitle),
                    icon = Icons.Rounded.Favorite,
                    isTablet = true,
                    onClick = onSupportersClick,
                )
                SettingsGroupDivider(isTablet = true)
            }
            if (showChangelog) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.changelog_title),
                    description = stringResource(Res.string.changelog_description),
                    icon = Icons.AutoMirrored.Rounded.ListAlt,
                    isTablet = true,
                    onClick = onChangelogClick,
                )
                SettingsGroupDivider(isTablet = true)
            }
            SettingsNavigationRow(
                title = stringResource(Res.string.compose_settings_page_licenses_attributions),
                description = stringResource(Res.string.about_licenses_attributions_subtitle),
                icon = Icons.Rounded.Gavel,
                isTablet = true,
                onClick = onLicensesClick,
            )
            SettingsGroupDivider(isTablet = true)
            SettingsNavigationRow(
                title = stringResource(Res.string.compose_settings_page_privacy_policy),
                description = stringResource(Res.string.compose_settings_root_privacy_policy_description),
                icon = Icons.Rounded.Policy,
                isTablet = true,
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
            )
        }
    }
}

internal fun LazyListScope.desktopUpdatesContent(
    onCheckForUpdatesClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onTestUpdateBannerClick: (() -> Unit)?,
) {
    item(key = "desktop-updates-version") {
        val tokens = MaterialTheme.nuvio
        val channel by UpdatePreferences.shared.channel.collectAsState()
        SettingsGroup(isTablet = true) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_desktop_current_version, AppVersionPolicy.displayVersionName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                    Text(
                        text = buildString {
                            append(stringResource(Res.string.settings_desktop_current_version_detail, AppVersionPolicy.displayVersionCode.toString()))
                            if (!AppUpdaterPlatform.hasSingleUpdateChannel) {
                                append(" · ")
                                append(updateChannelLabel(channel))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                NuvioCompactActionButton(
                    label = stringResource(Res.string.settings_desktop_check_for_updates),
                    icon = Icons.Rounded.SystemUpdateAlt,
                    onClick = onCheckForUpdatesClick,
                    tone = NuvioCompactActionTone.Primary,
                    size = NuvioActionSize.Small,
                )
            }
        }
    }
    item(key = "desktop-updates-options") {
        SettingsGroup(isTablet = true) {
            if (!AppUpdaterPlatform.hasSingleUpdateChannel) {
                UpdateChannelSettingsRow(isTablet = true)
                SettingsGroupDivider(isTablet = true)
            }
            SettingsNavigationRow(
                title = stringResource(Res.string.changelog_title),
                description = stringResource(Res.string.changelog_description),
                icon = Icons.AutoMirrored.Rounded.ListAlt,
                isTablet = true,
                onClick = onChangelogClick,
            )
            if (onTestUpdateBannerClick != null) {
                SettingsGroupDivider(isTablet = true)
                SettingsNavigationRow(
                    title = stringResource(Res.string.updates_debug_test_title),
                    description = stringResource(Res.string.updates_debug_test_description),
                    icon = Icons.Rounded.BugReport,
                    isTablet = true,
                    onClick = onTestUpdateBannerClick,
                )
            }
        }
    }
}

/** Desktop account summary: who is signed in, with Sign out beside it rather than a full-width button. */
@Composable
internal fun DesktopAccountCard(
    authState: AuthState,
    onSignOutClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val authenticated = authState as? AuthState.Authenticated
    SettingsGroup(isTablet = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tokens.colors.textPrimary.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = tokens.colors.textSecondary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        authenticated == null -> stringResource(Res.string.settings_account_not_signed_in)
                        authenticated.isAnonymous -> stringResource(Res.string.settings_account_status_anonymous)
                        else -> authenticated.email ?: stringResource(Res.string.settings_account_status_signed_in)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (authenticated != null) {
                    Text(
                        text = stringResource(
                            if (authenticated.isAnonymous) {
                                Res.string.settings_account_status_anonymous
                            } else {
                                Res.string.settings_account_status_signed_in
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
            if (authenticated != null) {
                NuvioCompactActionButton(
                    label = stringResource(Res.string.settings_account_sign_out),
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    onClick = onSignOutClick,
                    tone = NuvioCompactActionTone.Ghost,
                    size = NuvioActionSize.Small,
                )
            }
        }
    }
}

/** Account deletion as one quiet row; the confirmation dialog carries the warning. */
@Composable
internal fun DesktopDeleteAccountRow(
    errorMessage: String?,
    onDeleteClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsGroup(isTablet = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.settings_account_delete_account),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.settings_account_delete_account_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                errorMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger)
                }
            }
            NuvioCompactActionButton(
                label = stringResource(Res.string.settings_account_delete_account),
                icon = null,
                onClick = onDeleteClick,
                tone = NuvioCompactActionTone.Danger,
                size = NuvioActionSize.Small,
            )
        }
    }
}
