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
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/**
 * Asks once, shortly after the PC starts listening, when the firewall would hide it
 * from phones. "Not now" is remembered; the Phone remote window still offers the fix.
 */
@Composable
internal fun RemoteFirewallStartupPrompt(firewall: RemoteFirewallAccess, suppressed: Boolean) {
    val state by firewall.state.collectAsState()
    val hostReady by DesktopRemote.hostReady.collectAsState()
    val needsHelp = state == RemoteFirewallState.Blocked || state == RemoteFirewallState.PublicNetwork
    var handled by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(hostReady, needsHelp, handled, suppressed) {
        show = false
        // Someone who already saw the firewall card in the open window needs no second ask.
        if (suppressed && needsHelp) handled = true
        if (!hostReady || !needsHelp || handled || suppressed) return@LaunchedEffect
        if (withContext(Dispatchers.Default) { RemoteFirewallPromptMemory.dismissed() }) return@LaunchedEffect
        // Let startup screens settle before asking.
        delay(2_500)
        show = true
    }
    if (!show || handled) return
    val blocked = state == RemoteFirewallState.Blocked
    DialogSurface(
        onDismissRequest = { handled = true },
        title = stringResource(Res.string.remote_firewall_prompt_title),
        message = stringResource(
            if (blocked) Res.string.remote_firewall_prompt_blocked else Res.string.remote_firewall_prompt_public,
        ),
    ) {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.remote_firewall_not_now),
                onClick = {
                    handled = true
                    RemoteFirewallPromptMemory.dismiss()
                },
            )
            DialogButton(
                text = stringResource(
                    if (blocked) Res.string.remote_firewall_allow else Res.string.remote_firewall_open_settings,
                ),
                style = DialogButtonStyle.Primary,
                onClick = {
                    handled = true
                    if (blocked) firewall.requestAccess() else firewall.openNetworkSettings()
                },
            )
        }
    }
}
