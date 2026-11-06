package com.nuvio.app.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The desktop Phone remote window: QR code, firewall, connection state. */
@Composable
internal fun PhoneRemoteScreen(
    addresses: List<String>,
    firewall: RemoteFirewallAccess,
    pairing: RemotePairing?,
    pairingConfirmed: Boolean,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val status by DesktopRemote.status.collectAsState()
    val connected by DesktopRemote.connected.collectAsState()
    val hostReady by DesktopRemote.hostReady.collectAsState()
    val hostFailure by DesktopRemote.hostFailure.collectAsState()
    val discoveryReady by DesktopRemote.discoveryReady.collectAsState()
    val bluetoothHostReady by DesktopRemote.bluetoothHostReady.collectAsState()
    val disconnectedStatus = stringResource(Res.string.remote_disconnected_status)
    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteIconBadge(Icons.Rounded.PhoneAndroid, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.remote_qr_phone_remote), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
                Text(stringResource(Res.string.remote_window_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary)
            }
            RemoteStatusPill(
                text = stringResource(
                    when {
                        connected -> Res.string.remote_window_status_connected
                        hostReady -> Res.string.remote_window_status_ready
                        else -> Res.string.remote_window_status_offline
                    },
                ),
                tone = when {
                    connected -> RemoteTone.Success
                    hostReady -> RemoteTone.Accent
                    else -> RemoteTone.Warning
                },
            )
        }
        HorizontalDivider(color = tokens.colors.borderSubtle)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pairing != null) {
                    // A phone is asking to pair: the code check takes over the window.
                    Spacer(Modifier.height(12.dp))
                    RemotePairingPanel(pairing, pairingConfirmed, onConfirmPairing, onRejectPairing)
                } else if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        QrPanel(addresses, hostReady, hostFailure, status, Modifier.width(320.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DetailsColumn(addresses, firewall, hostReady, discoveryReady, connected, bluetoothHostReady)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        QrPanel(addresses, hostReady, hostFailure, status, Modifier.fillMaxWidth())
                        DetailsColumn(addresses, firewall, hostReady, discoveryReady, connected, bluetoothHostReady)
                    }
                }
            }
        }

        HorizontalDivider(color = tokens.colors.borderSubtle)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connected) {
                DialogButton(
                    text = stringResource(Res.string.remote_disconnect),
                    style = DialogButtonStyle.Destructive,
                    onClick = {
                        DesktopRemote.transport.disconnect()
                        DesktopRemote.status.value = disconnectedStatus
                    },
                )
            }
            DialogButton(
                text = stringResource(Res.string.action_close),
                style = if (connected) DialogButtonStyle.Secondary else DialogButtonStyle.Primary,
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun QrPanel(addresses: List<String>, hostReady: Boolean, hostFailure: Boolean, status: String, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    RemoteCard(modifier, contentPadding = 20.dp, spacing = 14.dp) {
        Text(stringResource(Res.string.remote_window_scan_title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
        when {
            hostReady && addresses.isNotEmpty() -> DesktopPairingQr(addresses)
            hostReady -> QrPlaceholder(Icons.Rounded.WifiOff, stringResource(Res.string.remote_qr_no_network))
            else -> QrPlaceholder(if (hostFailure) Icons.Rounded.ErrorOutline else null, status)
        }
    }
}

@Composable
private fun QrPlaceholder(icon: ImageVector?, text: String) {
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 228.dp)
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(36.dp))
        else NuvioLoadingIndicator(Modifier.size(28.dp), color = tokens.colors.accent, size = 28.dp)
        if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DetailsColumn(
    addresses: List<String>,
    firewall: RemoteFirewallAccess,
    hostReady: Boolean,
    discoveryReady: Boolean,
    connected: Boolean,
    bluetoothHostReady: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    if (connected) {
        RemoteCard(tone = RemoteTone.Success) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = tokens.colors.success, modifier = Modifier.size(22.dp))
                Text(stringResource(Res.string.remote_qr_connected), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
            }
        }
    }
    if (hostReady && !discoveryReady && !connected) {
        RemoteCard(tone = RemoteTone.Warning) {
            Text(
                stringResource(Res.string.remote_discovery_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
            )
        }
    }
    RemoteCard {
        Text(stringResource(Res.string.remote_window_steps_title), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
        listOf(
            Res.string.remote_window_step_open,
            Res.string.remote_window_step_scan,
            Res.string.remote_window_step_confirm,
        ).forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = tokens.colors.accent)
                }
                Text(stringResource(step), Modifier.weight(1f).padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary)
            }
        }
    }
    if (hostReady) {
        RemoteCard {
            Text(stringResource(Res.string.remote_window_other_ways), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
            if (addresses.isNotEmpty()) {
                InfoLine(Icons.Rounded.Lan, stringResource(Res.string.remote_pc_desktop_addresses, addresses.joinToString(", ")))
            }
            InfoLine(
                if (bluetoothHostReady) Icons.Rounded.Bluetooth else Icons.Rounded.BluetoothDisabled,
                stringResource(
                    if (bluetoothHostReady) Res.string.remote_bluetooth_host_ready
                    else Res.string.remote_bluetooth_host_unavailable,
                ),
            )
        }
    }
    if (hostReady && !connected) {
        var showNetworkHelp by remember { mutableStateOf(false) }
        LaunchedEffect(showNetworkHelp) {
            if (showNetworkHelp) firewall.refresh()
        }
        NuvioCompactActionButton(
            label = stringResource(Res.string.remote_network_help),
            icon = Icons.Rounded.Lan,
            tone = NuvioCompactActionTone.Ghost,
            modifier = Modifier.testTag("remote-network-help"),
            onClick = { showNetworkHelp = !showNetworkHelp },
        )
        if (showNetworkHelp) {
            Text(stringResource(Res.string.remote_network_help_body),
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            RemoteFirewallCard(firewall)
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    val tokens = MaterialTheme.nuvio
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
    }
}
