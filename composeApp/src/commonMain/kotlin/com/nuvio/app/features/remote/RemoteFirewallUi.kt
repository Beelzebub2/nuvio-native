package com.nuvio.app.features.remote

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The firewall step in the desktop Phone remote window. Hidden when nothing needs asking. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RemoteFirewallCard(firewall: RemoteFirewallAccess, modifier: Modifier = Modifier) {
    val state by firewall.state.collectAsState()
    val tokens = MaterialTheme.nuvio
    when (state) {
        RemoteFirewallState.NotNeeded -> Unit
        RemoteFirewallState.Checking, RemoteFirewallState.Requesting -> RemoteCard(modifier.testTag("remote-firewall-card")) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NuvioLoadingIndicator(Modifier.size(20.dp), color = tokens.colors.accent, size = 20.dp)
                Text(
                    stringResource(
                        if (state == RemoteFirewallState.Checking) Res.string.remote_firewall_checking
                        else Res.string.remote_firewall_requesting,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                )
            }
        }
        RemoteFirewallState.Allowed -> RemoteCard(modifier.testTag("remote-firewall-card"), tone = RemoteTone.Success) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Rounded.CheckCircle, contentDescription = null,
                    tint = tokens.colors.success, modifier = Modifier.size(20.dp))
                Text(stringResource(Res.string.remote_firewall_allowed), style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
        RemoteFirewallState.DiscoveryRestricted -> FirewallNotice(
            modifier = modifier,
            tone = RemoteTone.Warning,
            icon = Icons.Rounded.Lan,
            title = stringResource(Res.string.remote_firewall_discovery_title),
            body = stringResource(Res.string.remote_firewall_discovery_body),
        ) {
            CheckAgainButton(firewall)
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_firewall_allow),
                icon = Icons.Rounded.Security,
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
                onClick = firewall::requestAccess,
            )
        }
        RemoteFirewallState.Blocked -> FirewallNotice(
            modifier = modifier,
            tone = RemoteTone.Warning,
            icon = Icons.Rounded.GppMaybe,
            title = stringResource(Res.string.remote_firewall_blocked_title),
            body = stringResource(Res.string.remote_firewall_blocked_body),
        ) {
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_firewall_allow),
                icon = Icons.Rounded.Security,
                tone = NuvioCompactActionTone.Primary,
                size = NuvioActionSize.Small,
                modifier = Modifier.testTag("remote-firewall-allow"),
                onClick = firewall::requestAccess,
            )
            CheckAgainButton(firewall)
        }
        RemoteFirewallState.Unverified -> FirewallNotice(
            modifier = modifier,
            tone = RemoteTone.Warning,
            icon = Icons.Rounded.GppMaybe,
            title = stringResource(Res.string.remote_firewall_unverified_title),
            body = stringResource(Res.string.remote_firewall_unverified_body),
        ) {
            CheckAgainButton(firewall)
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_firewall_allow),
                icon = Icons.Rounded.Security,
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
                onClick = firewall::requestAccess,
            )
        }
        RemoteFirewallState.PublicNetwork -> FirewallNotice(
            modifier = modifier,
            tone = RemoteTone.Warning,
            icon = Icons.Rounded.Lan,
            title = stringResource(Res.string.remote_firewall_public_title),
            body = stringResource(Res.string.remote_firewall_public_body),
        ) {
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_firewall_open_settings),
                icon = Icons.Rounded.Lan,
                tone = NuvioCompactActionTone.Primary,
                size = NuvioActionSize.Small,
                onClick = firewall::openNetworkSettings,
            )
            CheckAgainButton(firewall)
        }
        RemoteFirewallState.Failed -> FirewallNotice(
            modifier = modifier,
            tone = RemoteTone.Danger,
            icon = Icons.Rounded.ErrorOutline,
            title = stringResource(Res.string.remote_firewall_failed_title),
            body = stringResource(Res.string.remote_firewall_failed_body),
        ) {
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_firewall_try_again),
                icon = Icons.Rounded.Security,
                tone = NuvioCompactActionTone.Primary,
                size = NuvioActionSize.Small,
                onClick = firewall::requestAccess,
            )
            CheckAgainButton(firewall)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FirewallNotice(
    modifier: Modifier,
    tone: RemoteTone,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    RemoteCard(modifier.testTag("remote-firewall-card"), tone = tone) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteIconBadge(icon, tone = tone, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary)
                Text(body, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
            }
        }
        FlowRow(
            Modifier.padding(start = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
    }
}

@Composable
private fun CheckAgainButton(firewall: RemoteFirewallAccess) {
    NuvioCompactActionButton(
        label = stringResource(Res.string.remote_firewall_check_again),
        icon = Icons.Rounded.Refresh,
        tone = NuvioCompactActionTone.Ghost,
        size = NuvioActionSize.Small,
        onClick = firewall::refresh,
    )
}
