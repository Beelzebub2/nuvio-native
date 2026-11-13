package com.nuvio.app.features.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// Desktop group metrics: 40 dp chips, 16 dp gaps and wider rows than the phone lists.
private val RowChip = 40.dp
private val RowGap = 16.dp
private val RowHorizontal = 20.dp
private val RowVertical = 16.dp
private val RowTextInset = RowHorizontal + RowChip + RowGap

/** The desktop Phone remote page: the pairing QR code, how to connect, and connection help. */
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
    val disconnect = {
        DesktopRemote.transport.disconnect()
        DesktopRemote.status.value = disconnectedStatus
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val gutter = if (wide) 40.dp else 24.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NuvioSquareIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.action_close),
                        onClick = onClose,
                        size = 36.dp,
                        style = NuvioSquareIconButtonStyle.Surface,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(Res.string.compose_settings_page_account),
                            style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted)
                        Text(stringResource(Res.string.remote_qr_phone_remote),
                            style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
                    }
                    PhoneRemoteStatusChip(
                        text = stringResource(
                            when {
                                connected -> Res.string.remote_window_status_connected
                                hostReady -> Res.string.remote_window_status_ready
                                else -> Res.string.remote_window_status_offline
                            },
                        ),
                        dot = when {
                            connected || hostReady -> tokens.colors.success
                            else -> tokens.colors.warning
                        },
                    )
                }
                Text(
                    stringResource(Res.string.remote_window_subtitle),
                    modifier = Modifier.padding(start = 48.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }

            if (pairing != null) {
                // A phone is asking to pair: the code check takes over the page.
                NuvioSurfaceCard(
                    modifier = Modifier.widthIn(max = 560.dp).align(Alignment.CenterHorizontally),
                    outlined = true,
                    contentPadding = 24.dp,
                ) {
                    RemotePairingPanel(pairing, pairingConfirmed, onConfirmPairing, onRejectPairing,
                        Modifier.align(Alignment.CenterHorizontally))
                }
            } else if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    QrPanel(addresses, hostReady, hostFailure, status, Modifier.width(340.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        DetailsColumn(firewall, hostReady, discoveryReady, connected, bluetoothHostReady, disconnect)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    QrPanel(addresses, hostReady, hostFailure, status, Modifier.fillMaxWidth())
                    DetailsColumn(firewall, hostReady, discoveryReady, connected, bluetoothHostReady, disconnect)
                }
            }
        }
    }
}

@Composable
private fun PhoneRemoteStatusChip(text: String, dot: androidx.compose.ui.graphics.Color) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier
            .height(32.dp)
            .clip(tokens.shapes.chip)
            .background(tokens.colors.surface)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, tokens.shapes.chip)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, style = MaterialTheme.nuvioTypeScale.bodySm, fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun QrPanel(addresses: List<String>, hostReady: Boolean, hostFailure: Boolean, status: String, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(
        modifier = modifier,
        outlined = true,
        contentPadding = 20.dp,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.remote_window_scan_title), style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary)
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
            .heightIn(min = 260.dp)
            .clip(tokens.shapes.button)
            .background(tokens.colors.surfaceCard)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) {
            NuvioIconChip(icon, size = 48.dp, tone = NuvioIconChipTone.Warning)
        } else {
            NuvioLoadingIndicator(Modifier.size(28.dp), color = tokens.colors.accent, size = 28.dp)
        }
        if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DetailsColumn(
    firewall: RemoteFirewallAccess,
    hostReady: Boolean,
    discoveryReady: Boolean,
    connected: Boolean,
    bluetoothHostReady: Boolean,
    onDisconnect: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val firewallState by firewall.state.collectAsState()
    LaunchedEffect(hostReady, connected, firewall) {
        if (hostReady && !connected) firewall.refresh()
    }
    if (connected) {
        NuvioGroup(isTablet = true) {
            NuvioGroupRow(
                title = stringResource(Res.string.remote_qr_connected),
                verticalPadding = RowVertical,
                startPadding = RowHorizontal,
                endPadding = RowHorizontal,
                spacing = RowGap,
                titleMaxLines = 3,
                leading = { NuvioIconChip(Icons.Rounded.CheckCircle, size = RowChip, tone = NuvioIconChipTone.Success) },
                trailing = {
                    DialogButton(
                        text = stringResource(Res.string.remote_disconnect),
                        onClick = onDisconnect,
                        style = DialogButtonStyle.Destructive,
                    )
                },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_window_steps_title).uppercase())
        NuvioGroup(isTablet = true) {
            listOf(
                Res.string.remote_window_step_open,
                Res.string.remote_window_step_scan,
                Res.string.remote_window_step_confirm,
            ).forEachIndexed { index, step ->
                if (index > 0) NuvioGroupDivider(startInset = RowTextInset)
                NuvioGroupRow(
                    title = stringResource(step),
                    verticalPadding = RowVertical,
                    startPadding = RowHorizontal,
                    endPadding = RowHorizontal,
                    spacing = RowGap,
                    titleMaxLines = 4,
                    leading = {
                        NuvioIconChip(size = RowChip) {
                            Text("${index + 1}", style = MaterialTheme.typography.bodyLarge.tabular, fontWeight = FontWeight.Bold)
                        }
                    },
                )
            }
        }
    }

    // Keep the VPN opt-in and revoke controls reachable even when the remote
    // listener is unavailable; otherwise recovery would require LAN access.
    if (isDesktop && DesktopRemote.transport.vpnHostSupported) {
        RemoteVpnSettingsCard(firewall)
    }
    if (!hostReady) return
    val showHelp = !connected
    var helpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(helpOpen) {
        if (helpOpen) firewall.refresh()
    }
    // A blocked Ethernet profile needs to be visible before the user
    // struggles with QR pairing or opens the help row.
    val firewallNeedsAttention = firewallState != RemoteFirewallState.NotNeeded &&
        firewallState != RemoteFirewallState.Allowed
    val showFirewall = showHelp && firewallState != RemoteFirewallState.NotNeeded && (helpOpen || firewallNeedsAttention)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_window_other_ways).uppercase())
        NuvioGroup(isTablet = true) {
            if (!discoveryReady && !connected) {
                InfoRow(Icons.Rounded.WarningAmber, NuvioIconChipTone.Warning, stringResource(Res.string.remote_discovery_unavailable))
                NuvioGroupDivider(startInset = RowTextInset)
            }
            if (bluetoothHostReady) {
                InfoRow(Icons.Rounded.Bluetooth, NuvioIconChipTone.Accent, stringResource(Res.string.remote_bluetooth_host_ready))
            } else {
                InfoRow(Icons.Rounded.BluetoothDisabled, NuvioIconChipTone.Warning,
                    stringResource(Res.string.remote_bluetooth_host_unavailable))
            }
            if (showHelp) {
                NuvioGroupDivider(startInset = RowTextInset)
                NuvioGroupRow(
                    title = stringResource(Res.string.remote_network_help),
                    description = stringResource(Res.string.remote_window_trouble_caption),
                    modifier = Modifier.testTag("remote-network-help"),
                    onClick = { helpOpen = !helpOpen },
                    verticalPadding = RowVertical,
                    startPadding = RowHorizontal,
                    endPadding = RowHorizontal,
                    spacing = RowGap,
                    leading = {
                        NuvioIconChip(Icons.AutoMirrored.Rounded.HelpOutline, size = RowChip, tone = NuvioIconChipTone.Neutral)
                    },
                    trailing = {
                        Icon(
                            if (helpOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = tokens.colors.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
                AnimatedVisibility(visible = helpOpen) {
                    Text(
                        stringResource(Res.string.remote_network_help_body),
                        modifier = Modifier.padding(start = RowTextInset, end = RowHorizontal, bottom = RowVertical),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                }
            }
            if (showFirewall) {
                NuvioGroupDivider(startInset = RowTextInset)
                RemoteFirewallCard(firewall)
            }
        }
    }
}

/** Private VPN access requires an active adapter and an explicit desktop opt-in. */
@Composable
private fun RemoteVpnSettingsCard(firewall: RemoteFirewallAccess) {
    val transport = DesktopRemote.transport
    var addresses by remember { mutableStateOf(transport.vpnInterfaces()) }
    var approved by remember { mutableStateOf(transport.approvedVpnAddress()) }
    var error by remember { mutableStateOf(false) }
    val firewallState by firewall.vpnState.collectAsState()
    val tokens = MaterialTheme.nuvio

    LaunchedEffect(approved) { firewall.refreshPrivateVpnAccess(approved) }
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NuvioIconChip(Icons.Rounded.VpnKey, tone = NuvioIconChipTone.Accent)
            Text(stringResource(Res.string.remote_vpn_pc_title),
                style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
        }
        Text(stringResource(Res.string.remote_vpn_pc_desc),
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
        if (approved != null) {
            Text(stringResource(Res.string.remote_vpn_pc_enabled, approved.orEmpty()),
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
            DialogButton(
                text = stringResource(Res.string.remote_vpn_disable),
                modifier = Modifier.testTag("remote-vpn-disable"),
                style = DialogButtonStyle.Destructive,
                onClick = {
                    error = !transport.approveVpnAddress(null)
                    approved = transport.approvedVpnAddress()
                    if (!error) firewall.removePrivateVpnAccess()
                },
            )
        } else {
            if (addresses.isEmpty()) {
                Text(stringResource(Res.string.remote_vpn_pc_not_found),
                    style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
            }
            addresses.forEach { address ->
                DialogButton(
                    text = stringResource(Res.string.remote_vpn_enable, address),
                    modifier = Modifier.testTag("remote-vpn-enable"),
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        error = !transport.approveVpnAddress(address)
                        approved = transport.approvedVpnAddress()
                    },
                )
            }
        }
        if (approved != null) {
            when (firewallState) {
                RemoteFirewallState.Allowed -> Text(stringResource(Res.string.remote_vpn_firewall_ready),
                    style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary)
                RemoteFirewallState.Requesting, RemoteFirewallState.Checking ->
                    Text(stringResource(Res.string.remote_vpn_firewall_checking),
                        style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
                else -> DialogButton(
                    text = stringResource(Res.string.remote_vpn_firewall_allow),
                    modifier = Modifier.testTag("remote-vpn-firewall-allow"),
                    style = DialogButtonStyle.Primary,
                    onClick = { approved?.let(firewall::requestPrivateVpnAccess) },
                )
            }
        }
        DialogButton(
            text = stringResource(Res.string.remote_vpn_refresh),
            onClick = {
                addresses = transport.vpnInterfaces()
                approved = transport.approvedVpnAddress()
                error = false
            },
        )
        if (error) Text(stringResource(Res.string.remote_vpn_pc_error),
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary)
        if (firewallState == RemoteFirewallState.Failed) Text(
            stringResource(Res.string.remote_vpn_firewall_failed),
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary)
        Text(stringResource(Res.string.remote_vpn_pc_firewall),
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
    }
}

/** A status line in a group. The first sentence is the title; the rest explains it. */
@Composable
private fun InfoRow(icon: ImageVector, tone: NuvioIconChipTone, text: String) {
    val (title, detail) = splitLeadSentence(text)
    NuvioGroupRow(
        title = title,
        description = detail,
        verticalPadding = RowVertical,
        startPadding = RowHorizontal,
        endPadding = RowHorizontal,
        spacing = RowGap,
        titleMaxLines = 4,
        descriptionMaxLines = 6,
        leading = { NuvioIconChip(icon, size = RowChip, tone = tone) },
    )
}

/** "Bluetooth is available. Pair your phone…" becomes a title and its explanation. */
internal fun splitLeadSentence(text: String): Pair<String, String?> {
    val index = listOf(". ", "。", "؟ ", "। ").map { text.indexOf(it) }.filter { it > 0 }.minOrNull()
        ?: return text to null
    val title = text.substring(0, index).trim()
    val rest = text.substring(index + 1).trim()
    return if (rest.isEmpty()) text to null else title to rest
}
