package com.nuvio.app.features.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The firewall step in the desktop Phone remote page, drawn as a row of the connection group.
 * Hidden when nothing needs asking.
 */
@Composable
internal fun RemoteFirewallCard(firewall: RemoteFirewallAccess, modifier: Modifier = Modifier) {
    val state by firewall.state.collectAsState()
    when (state) {
        RemoteFirewallState.NotNeeded -> Unit
        RemoteFirewallState.Checking, RemoteFirewallState.Requesting -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Accent,
            icon = null,
            body = stringResource(
                if (state == RemoteFirewallState.Checking) Res.string.remote_firewall_checking
                else Res.string.remote_firewall_requesting,
            ),
        )
        RemoteFirewallState.Allowed -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Success,
            icon = Icons.Rounded.CheckCircle,
            body = stringResource(Res.string.remote_firewall_allowed),
        )
        RemoteFirewallState.DiscoveryRestricted -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Warning,
            icon = Icons.Rounded.Lan,
            title = stringResource(Res.string.remote_firewall_discovery_title),
            body = stringResource(Res.string.remote_firewall_discovery_body),
        ) {
            CheckAgainButton(firewall)
            NuvioPillButton(
                text = stringResource(Res.string.remote_firewall_allow),
                onClick = firewall::requestAccess,
                icon = Icons.Rounded.Security,
            )
        }
        RemoteFirewallState.Blocked -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Warning,
            icon = Icons.Rounded.GppMaybe,
            title = stringResource(Res.string.remote_firewall_blocked_title),
            body = stringResource(Res.string.remote_firewall_blocked_body),
        ) {
            NuvioPillButton(
                text = stringResource(Res.string.remote_firewall_allow),
                onClick = firewall::requestAccess,
                modifier = Modifier.testTag("remote-firewall-allow"),
                style = NuvioPillButtonStyle.Primary,
                icon = Icons.Rounded.Security,
            )
            CheckAgainButton(firewall)
        }
        RemoteFirewallState.Unverified -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Warning,
            icon = Icons.Rounded.GppMaybe,
            title = stringResource(Res.string.remote_firewall_unverified_title),
            body = stringResource(Res.string.remote_firewall_unverified_body),
        ) {
            CheckAgainButton(firewall)
            NuvioPillButton(
                text = stringResource(Res.string.remote_firewall_allow),
                onClick = firewall::requestAccess,
                icon = Icons.Rounded.Security,
            )
        }
        RemoteFirewallState.PublicNetwork -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Warning,
            icon = Icons.Rounded.Lan,
            title = stringResource(Res.string.remote_firewall_public_title),
            body = stringResource(Res.string.remote_firewall_public_body),
        ) {
            NuvioPillButton(
                text = stringResource(Res.string.remote_firewall_allow_public_ethernet),
                onClick = firewall::requestPublicEthernetAccess,
                modifier = Modifier.testTag("remote-firewall-allow-public-ethernet"),
                style = NuvioPillButtonStyle.Primary,
                icon = Icons.Rounded.Security,
            )
            NuvioPillButton(
                text = stringResource(Res.string.remote_firewall_open_settings),
                onClick = firewall::openNetworkSettings,
                icon = Icons.Rounded.Lan,
            )
            CheckAgainButton(firewall)
        }
        RemoteFirewallState.Failed, RemoteFirewallState.PublicEthernetFailed -> FirewallRow(
            modifier = modifier,
            tone = NuvioIconChipTone.Danger,
            icon = Icons.Rounded.ErrorOutline,
            title = stringResource(Res.string.remote_firewall_failed_title),
            body = stringResource(Res.string.remote_firewall_failed_body),
        ) {
            NuvioPillButton(
                text = stringResource(if (state == RemoteFirewallState.PublicEthernetFailed)
                    Res.string.remote_firewall_allow_public_ethernet else Res.string.remote_firewall_try_again),
                onClick = {
                    if (state == RemoteFirewallState.PublicEthernetFailed) firewall.requestPublicEthernetAccess()
                    else firewall.requestAccess()
                },
                style = NuvioPillButtonStyle.Primary,
                icon = Icons.Rounded.Security,
            )
            CheckAgainButton(firewall)
        }
    }
}

/** An icon chip, a title and explanation, and the actions that can fix the problem. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirewallRow(
    modifier: Modifier,
    tone: NuvioIconChipTone,
    icon: ImageVector?,
    body: String,
    title: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier
            .fillMaxWidth()
            .testTag("remote-firewall-card")
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top,
        ) {
            NuvioIconChip(size = 40.dp, tone = tone) {
                if (icon == null) {
                    NuvioLoadingIndicator(Modifier.size(20.dp), color = LocalContentColor.current, size = 20.dp)
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = tokens.colors.textPrimary)
                }
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (title == null) tokens.colors.textSecondary else tokens.colors.textMuted,
                )
            }
        }
        if (actions != null) {
            FlowRow(
                Modifier.padding(start = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions() }
        }
    }
}

@Composable
private fun CheckAgainButton(firewall: RemoteFirewallAccess) {
    NuvioPillButton(
        text = stringResource(Res.string.remote_firewall_check_again),
        onClick = firewall::refresh,
        icon = Icons.Rounded.Refresh,
    )
}
