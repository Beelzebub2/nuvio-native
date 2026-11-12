package com.nuvio.app.features.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.formatDownloadBytes
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class PcControllerTab { Remote, Downloads, Pcs }

/** The phone's full-screen PC controller: find and pair a PC, then control playback. */
@Composable
internal fun PcControllerScreen(onClose: () -> Unit) {
    val connected by DesktopRemote.connected.collectAsState()
    val scanning by DesktopRemote.scanning.collectAsState()
    val connecting by DesktopRemote.connecting.collectAsState()
    var tab by rememberSaveable { mutableStateOf(PcControllerTab.Remote) }
    // Until a PC is paired, the only useful page is finding one.
    val shownTab = if (connected) tab else PcControllerTab.Pcs
    val disconnectedStatus = stringResource(Res.string.remote_disconnected_status)
    val disconnect = {
        DesktopRemote.transport.disconnect()
        DesktopRemote.status.value = disconnectedStatus
    }
    // Keep each destination's scroll position when the user switches tabs.
    val remoteScroll = rememberScrollState()
    val downloadsScroll = rememberScrollState()
    val pcsScroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .testTag("pc-controller-screen"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.action_close),
                    tint = MaterialTheme.nuvio.colors.textPrimary)
            }
            Text(
                stringResource(Res.string.remote_pc_controller_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            if (DesktopRemote.transport.supported) {
                RemoteStatusPill(
                    text = stringResource(
                        when {
                            connected -> Res.string.remote_pc_status_connected
                            connecting -> Res.string.remote_pc_status_connecting
                            scanning -> Res.string.remote_pc_status_searching
                            else -> Res.string.remote_pc_status_idle
                        },
                    ),
                    tone = when {
                        connected -> RemoteTone.Success
                        connecting || scanning -> RemoteTone.Accent
                        else -> RemoteTone.Neutral
                    },
                    busy = !connected && (scanning || connecting),
                )
            }
        }
        if (connected) {
            RemoteTabs(
                tabs = listOf(
                    PcControllerTab.Remote to stringResource(Res.string.remote_pc_tab_remote),
                    PcControllerTab.Downloads to stringResource(Res.string.remote_pc_tab_downloads),
                    PcControllerTab.Pcs to stringResource(Res.string.remote_pc_tab_pcs),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val scroll = when (shownTab) {
            PcControllerTab.Remote -> remoteScroll
            PcControllerTab.Downloads -> downloadsScroll
            PcControllerTab.Pcs -> pcsScroll
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            when (shownTab) {
                PcControllerTab.Remote -> RemotePlaybackPage()
                PcControllerTab.Downloads -> RemoteDownloadsPage()
                PcControllerTab.Pcs -> FindPcPage(onDisconnect = disconnect)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FindPcPage(onDisconnect: () -> Unit) {
    val status by DesktopRemote.status.collectAsState()
    val connected by DesktopRemote.connected.collectAsState()
    val scanning by DesktopRemote.scanning.collectAsState()
    val connecting by DesktopRemote.connecting.collectAsState()
    val desktops by DesktopRemote.desktops.collectAsState()
    val bluetoothDesktops by DesktopRemote.bluetoothDesktops.collectAsState()
    val savedPcs by DesktopRemote.remembered.collectAsState()
    val activeDesktop by DesktopRemote.activeDesktop.collectAsState()
    val activeDetail = activeDesktop?.name?.let {
        stringResource(if (activeDesktop?.vpn == true) Res.string.remote_vpn_connected_to
            else Res.string.remote_pc_connected_to, it)
    }
    val tokens = MaterialTheme.nuvio
    // Searching or connecting: other ways to connect wait until it ends.
    val busy = scanning || connecting

    // Status hero: what is happening right now.
    RemoteCard(contentPadding = 18.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteIconBadge(
                Icons.Rounded.DesktopWindows,
                tone = if (connected) RemoteTone.Success else RemoteTone.Accent,
                size = 56.dp,
                busy = !connected && busy,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when {
                            connected -> Res.string.remote_pc_hero_connected_title
                            connecting -> Res.string.remote_pc_hero_connecting_title
                            scanning -> Res.string.remote_pc_hero_searching_title
                            else -> Res.string.remote_pc_hero_idle_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
                val detail = when {
                    !DesktopRemote.transport.supported -> stringResource(Res.string.remote_pc_unsupported)
                    connected && activeDetail != null -> activeDetail
                    else -> status
                }
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
                }
            }
        }
        if (connected) {
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_disconnect),
                icon = Icons.Rounded.PowerSettingsNew,
                tone = NuvioCompactActionTone.Danger,
                size = NuvioActionSize.Small,
                onClick = onDisconnect,
            )
        }
    }
    if (!DesktopRemote.transport.supported) return

    // A concurrent discovery can update the search message, but never replace
    // the connected PC's identity in the status hero.
    ConnectOptions(busy = busy, scanStatus = status.takeIf { connected && scanning })

    if (desktops.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteSectionHeader(stringResource(Res.string.remote_pc_found_devices))
            desktops.forEach { desktop ->
                key(desktop.bluetooth, desktop.vpn, desktop.address) {
                    val active = isActiveRemoteDesktop(connected, activeDesktop, desktop.address, desktop.bluetooth, desktop.vpn)
                    RemoteDeviceRow(
                        name = desktop.name,
                        detail = desktop.address,
                        icon = if (desktop.vpn) Icons.Rounded.VpnKey else Icons.Rounded.Computer,
                        enabled = !connecting,
                        active = active,
                        onClick = { DesktopRemote.transport.connect(desktop) },
                    )
                }
            }
        }
    }
    if (bluetoothDesktops.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteSectionHeader(stringResource(Res.string.remote_bluetooth_found_devices))
            bluetoothDesktops.forEach { desktop ->
                key(desktop.bluetooth, desktop.address) {
                    val active = isActiveRemoteDesktop(connected, activeDesktop, desktop.address, desktop.bluetooth)
                    RemoteDeviceRow(
                        name = desktop.name,
                        detail = stringResource(Res.string.remote_bluetooth_device, desktop.address),
                        icon = Icons.AutoMirrored.Rounded.BluetoothSearching,
                        enabled = !connecting,
                        active = active,
                        onClick = { DesktopRemote.transport.connect(desktop) },
                    )
                }
            }
        }
    }
    if (savedPcs.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteSectionHeader(stringResource(Res.string.remote_pc_saved_devices))
            savedPcs.forEach { saved ->
                // Pairing updates move a saved PC to the top. Preserve its own menu
                // and Wake-on-LAN editor instead of moving that UI onto another row.
                key(saved.bluetooth, saved.vpn, saved.address) {
                    SavedPcRow(
                        saved = saved,
                        enabled = !busy,
                        active = isActiveRemoteDesktop(connected, activeDesktop, saved.address, saved.bluetooth, saved.vpn),
                        onDisconnect = onDisconnect,
                    )
                }
            }
            DialogHint(stringResource(Res.string.remote_pc_saved_notice))
        }
    }
    if (!connected) TroubleshootingCard(expandByDefault = !busy && desktops.isEmpty() && bluetoothDesktops.isEmpty())
}

/** QR, network search, Bluetooth and manual address as one grid of choices. */
@Composable
private fun ConnectOptions(busy: Boolean, scanStatus: String?) {
    var manualIp by rememberSaveable { mutableStateOf("") }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var vpnEndpoint by rememberSaveable { mutableStateOf("") }
    var vpnOpen by rememberSaveable { mutableStateOf(false) }
    var qrError by remember { mutableStateOf(false) }
    val qrScanner = rememberRemoteQrScanner { payload ->
        val address = payload?.let(::parseRemotePairingQr)
        qrError = address == null
        if (address != null) {
            manualIp = address
            // The QR code names the PC, so check it and open the code check in one go.
            DesktopRemote.transport.discover(address, connect = true)
        }
    }
    val bluetoothSearch = rememberRemoteBluetoothSearch()
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if (qrScanner != null) add { modifier ->
            RemoteActionTile(
                icon = Icons.Rounded.QrCodeScanner,
                label = stringResource(Res.string.remote_qr_scan),
                caption = stringResource(Res.string.remote_pc_tile_qr_caption),
                primary = true,
                enabled = !busy,
                modifier = modifier.testTag("desktop-remote-scan-qr"),
                onClick = { qrError = false; qrScanner() },
            )
        }
        // Manual search stays available with an empty list, after a failed
        // search, and while connected to another PC.
        add { modifier ->
            RemoteActionTile(
                icon = Icons.Rounded.WifiFind,
                label = stringResource(Res.string.remote_pc_search_local_network),
                caption = stringResource(Res.string.remote_pc_tile_network_caption),
                primary = qrScanner == null,
                enabled = !busy,
                modifier = modifier.testTag("desktop-remote-search-local-network"),
                onClick = { DesktopRemote.transport.discover(deepScan = true) },
            )
        }
        if (bluetoothSearch != null) add { modifier ->
            RemoteActionTile(
                icon = Icons.AutoMirrored.Rounded.BluetoothSearching,
                label = stringResource(Res.string.remote_bluetooth_search),
                caption = stringResource(Res.string.remote_pc_tile_bluetooth_caption),
                enabled = !busy,
                modifier = modifier.testTag("desktop-remote-search-bluetooth"),
                onClick = bluetoothSearch,
            )
        }
        add { modifier ->
            RemoteActionTile(
                icon = Icons.Rounded.Keyboard,
                label = stringResource(Res.string.remote_pc_tile_address),
                caption = stringResource(Res.string.remote_pc_tile_address_caption),
                selected = manualOpen,
                modifier = modifier.testTag("desktop-remote-enter-address"),
                onClick = { manualOpen = !manualOpen },
            )
        }
        add { modifier ->
            RemoteActionTile(
                icon = Icons.Rounded.VpnKey,
                label = stringResource(Res.string.remote_vpn_tile),
                caption = stringResource(Res.string.remote_vpn_tile_caption),
                selected = vpnOpen,
                enabled = !busy,
                modifier = modifier.testTag("desktop-remote-enter-vpn"),
                onClick = { vpnOpen = !vpnOpen },
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteSectionHeader(stringResource(Res.string.remote_pc_ways_to_connect))
        RemoteTileGrid(tiles)
        if (scanStatus != null) DialogHint(scanStatus)
        if (qrError) DialogHint(stringResource(Res.string.remote_qr_scan_failed), isError = true)
        AnimatedVisibility(visible = manualOpen) {
            RemoteCard {
                Text(stringResource(Res.string.remote_pc_manual_section), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.nuvio.colors.textPrimary)
                DialogHint(stringResource(Res.string.remote_pc_manual_ip_hint))
                DialogTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it.take(45) },
                    label = stringResource(Res.string.remote_pc_manual_ip_label),
                    placeholder = "192.168.1.20",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("desktop-remote-manual-ip"),
                )
                NuvioCompactActionButton(
                    label = stringResource(Res.string.remote_pc_check_ip),
                    icon = Icons.Rounded.Search,
                    tone = NuvioCompactActionTone.Primary,
                    enabled = !busy && manualIp.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { DesktopRemote.transport.discover(manualIp.trim()) },
                )
            }
        }
        AnimatedVisibility(visible = vpnOpen) {
            RemoteCard {
                Text(stringResource(Res.string.remote_vpn_tile),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.nuvio.colors.textPrimary)
                DialogHint(stringResource(Res.string.remote_vpn_phone_hint))
                DialogTextField(
                    value = vpnEndpoint,
                    onValueChange = { vpnEndpoint = it.take(253) },
                    label = stringResource(Res.string.remote_vpn_address_label),
                    placeholder = "100.101.42.18",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("desktop-remote-vpn-address"),
                )
                NuvioCompactActionButton(
                    label = stringResource(Res.string.remote_vpn_check),
                    icon = Icons.Rounded.VpnKey,
                    tone = NuvioCompactActionTone.Primary,
                    enabled = !busy && vpnEndpoint.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("desktop-remote-vpn-connect"),
                    onClick = { DesktopRemote.transport.discoverVpn(vpnEndpoint.trim(), connect = true) },
                )
            }
        }
    }
}

@Composable
private fun SavedPcRow(
    saved: RememberedRemoteDevice,
    enabled: Boolean,
    active: Boolean,
    onDisconnect: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var editingWake by remember { mutableStateOf(false) }
    var macDraft by remember(saved.wakeMac) { mutableStateOf(saved.wakeMac.orEmpty()) }
    var macError by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    val connect = { connectRememberedRemoteDevice(DesktopRemote.transport, saved) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteDeviceRow(
            name = saved.name,
            detail = saved.address,
            icon = if (saved.bluetooth) Icons.AutoMirrored.Rounded.BluetoothSearching
                else if (saved.vpn) Icons.Rounded.VpnKey else Icons.Rounded.History,
            enabled = enabled,
            active = active,
            modifier = Modifier.testTag("remote-saved-pc-${saved.address}"),
            onClick = connect,
        ) {
            if (active) {
                Text(
                    stringResource(Res.string.remote_pc_status_connected),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.remote_pc_more_options, saved.name),
                        tint = tokens.colors.textSecondary)
                }
                Menu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem(
                        text = stringResource(if (active) Res.string.remote_disconnect else Res.string.remote_pc_connect),
                        enabled = enabled,
                        onClick = { menuOpen = false; if (active) onDisconnect() else connect() },
                    )
                    if (!saved.bluetooth && !saved.vpn) {
                        MenuItem(
                            text = stringResource(Res.string.remote_pc_set_wake_mac),
                            selected = editingWake,
                            onClick = { menuOpen = false; editingWake = !editingWake; macError = false },
                        )
                    }
                    MenuItem(
                        text = stringResource(Res.string.remote_pc_forget),
                        onClick = { menuOpen = false; DesktopRemote.forget(saved.address) },
                    )
                }
            }
        }
        if (!saved.bluetooth && !saved.vpn && !active) {
            // Keep the wake action readable on narrow phones instead of
            // squeezing the PC name/address into an already crowded row.
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_pc_wake),
                icon = Icons.Rounded.PowerSettingsNew,
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
                modifier = Modifier.align(Alignment.End).testTag("remote-wake-pc-${saved.address}"),
                onClick = {
                    val mac = saved.wakeMac
                    if (mac != null) DesktopRemote.transport.wake(saved.address, mac)
                    else {
                        editingWake = true
                        macError = false
                    }
                },
            )
        }
        AnimatedVisibility(visible = editingWake) {
            RemoteCard {
                DialogHint(stringResource(Res.string.remote_pc_mac_help))
                DialogTextField(
                    value = macDraft,
                    onValueChange = { macDraft = it.take(20); macError = false },
                    label = stringResource(Res.string.remote_pc_mac_label),
                    isError = macError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (macError) DialogHint(stringResource(Res.string.remote_pc_invalid_mac), isError = true)
                NuvioCompactActionButton(
                    label = stringResource(Res.string.remote_pc_save_mac),
                    icon = null,
                    tone = NuvioCompactActionTone.Primary,
                    size = NuvioActionSize.Small,
                    onClick = {
                        if (DesktopRemote.saveWakeAddress(saved.address, macDraft)) editingWake = false
                        else macError = true
                    },
                )
            }
        }
    }
}

@Composable
private fun TroubleshootingCard(expandByDefault: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(expandByDefault) }
    var userToggled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expandByDefault) {
        if (expandByDefault && !userToggled) expanded = true
    }
    val tokens = MaterialTheme.nuvio
    RemoteCard(contentPadding = 0.dp, spacing = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { userToggled = true; expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Help, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.remote_pc_troubleshoot_title), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null,
                tint = tokens.colors.textMuted)
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(
                    Res.string.remote_pc_troubleshoot_open,
                    Res.string.remote_pc_troubleshoot_network,
                    Res.string.remote_pc_troubleshoot_firewall,
                    Res.string.remote_pc_troubleshoot_fallback,
                ).forEachIndexed { index, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                color = tokens.colors.accent)
                        }
                        Text(stringResource(line), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemotePlaybackPage() {
    val playback by DesktopRemote.remotePlayback.collectAsState()
    val tokens = MaterialTheme.nuvio
    RemotePlaybackQuickActions(playback)
    if (playback.playbackId.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Tv,
            title = stringResource(Res.string.remote_playback_idle_title),
            body = stringResource(Res.string.remote_playback_idle_body),
        )
        return
    }
    RemoteCard(contentPadding = 18.dp, spacing = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.remote_playback_now_playing).uppercase(), style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = tokens.colors.accent)
            Text(playback.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (playback.message.isNotBlank()) DialogHint(playback.message)
        }
        SeekBar(playback)
        TransportRow(playback)
        VolumeRow(playback)
    }
    PlaybackOptions(playback)
}

@Composable
private fun RemotePlaybackQuickActions(playback: RemotePlayback) {
    if (!playback.fullscreenSupported) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteSectionHeader(stringResource(Res.string.remote_playback_quick_actions))
        // The transport row already owns Next episode; playback options own Skip interval.
        // Only desktop-window actions belong here.
        RemoteActionTile(
            icon = if (playback.fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            label = stringResource(
                if (playback.fullscreen) Res.string.remote_playback_fullscreen_exit
                else Res.string.remote_playback_fullscreen_enter,
            ),
            caption = null,
            selected = playback.fullscreen,
            modifier = Modifier.fillMaxWidth().testTag("remote-toggle-fullscreen"),
            onClick = { DesktopRemote.command("setFullscreen", if (playback.fullscreen) 0.0 else 1.0) },
        )
    }
}

@Composable
private fun SeekBar(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val duration = playback.duration.coerceAtLeast(1)
    var seek by remember { mutableStateOf<Float?>(null) }
    Column {
        Slider(
            value = seek ?: (playback.position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
            onValueChange = { seek = it },
            onValueChangeFinished = { seek?.let { DesktopRemote.command("seek", it.toDouble() * duration) }; seek = null },
            enabled = playback.duration > 0,
            colors = remoteSliderColors(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(remoteTime(seek?.let { (it * duration).toLong() } ?: playback.position),
                style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            Text(remoteTime(playback.duration), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun TransportRow(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TransportButton(Icons.Rounded.SkipPrevious, stringResource(Res.string.remote_playback_previous)) { DesktopRemote.command("previous") }
        TransportButton(Icons.Rounded.Replay10, stringResource(Res.string.remote_playback_rewind)) { DesktopRemote.command("seekBy", -10_000.0) }
        val playLabel = stringResource(if (playback.playing) Res.string.remote_playback_pause else Res.string.remote_playback_play)
        FilledIconButton(
            onClick = { DesktopRemote.command(if (playback.playing) "pause" else "play") },
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = tokens.colors.accent,
                contentColor = tokens.colors.onAccent,
            ),
        ) {
            Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = playLabel,
                modifier = Modifier.size(36.dp))
        }
        TransportButton(Icons.Rounded.Forward10, stringResource(Res.string.remote_playback_forward)) { DesktopRemote.command("seekBy", 10_000.0) }
        TransportButton(
            Icons.Rounded.SkipNext,
            stringResource(Res.string.remote_playback_next),
            enabled = playback.nextEpisodeAvailable,
        ) { DesktopRemote.command("next") }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.nuvio.colors.textPrimary else MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun VolumeRow(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val maxVolume = playback.effectiveVolumeMax()
    val pcVolume = playback.volume.takeIf { it.isFinite() }?.coerceIn(0f, REMOTE_MAX_VOLUME_LEVEL) ?: 1f
    var draggedVolume by remember(playback.playbackId) { mutableStateOf<Float?>(null) }
    var awaitingVolume by remember(playback.playbackId) { mutableStateOf<Float?>(null) }

    // Keep the thumb at the requested level until the PC has actually acknowledged it.
    // Otherwise the previous 300-ms snapshot makes it jump backwards on finger release.
    LaunchedEffect(pcVolume, awaitingVolume) {
        val requested = awaitingVolume ?: return@LaunchedEffect
        if (abs(pcVolume - requested) <= 0.01f) awaitingVolume = null
    }
    LaunchedEffect(awaitingVolume) {
        if (awaitingVolume != null) {
            delay(2_500)
            // Recover if the command was rejected or the remote connection stalled.
            awaitingVolume = null
        }
    }
    // Preview a stationary drag without flooding the encrypted command queue.
    // The final exact value is always sent immediately on release.
    LaunchedEffect(draggedVolume) {
        val preview = draggedVolume ?: return@LaunchedEffect
        delay(350)
        DesktopRemote.command("volume", preview.toDouble())
    }
    val shownVolume = draggedVolume ?: awaitingVolume ?: pcVolume
    Row(
        Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = {
            draggedVolume = null
            awaitingVolume = null
            DesktopRemote.command("mute")
        }) {
            Icon(
                if (shownVolume <= 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(Res.string.remote_playback_mute),
                tint = tokens.colors.textPrimary,
            )
        }
        Slider(
            value = shownVolume.coerceIn(0f, maxVolume),
            valueRange = 0f..maxVolume,
            onValueChange = { draggedVolume = it.coerceIn(0f, maxVolume) },
            onValueChangeFinished = {
                draggedVolume?.let { requested ->
                    awaitingVolume = requested
                    DesktopRemote.command("volume", requested.toDouble())
                }
                draggedVolume = null
            },
            colors = remoteSliderColors(),
            modifier = Modifier.weight(1f).testTag("remote-volume-slider"),
        )
        Text(
            "${(shownVolume * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.widthIn(min = 44.dp).padding(end = 10.dp).testTag("remote-volume-value"),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun PlaybackOptions(playback: RemotePlayback) {
    // Dismiss choices from a previous movie/episode when playback changes.
    var openList by remember(playback.playbackId) { mutableStateOf<String?>(null) }
    val episodesTitle = stringResource(Res.string.remote_playback_episodes)
    val subtitlesTitle = stringResource(Res.string.remote_playback_subtitles)
    val audioTracksTitle = stringResource(Res.string.compose_player_audio_tracks)
    val sourcesTitle = stringResource(Res.string.remote_playback_sources)
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if (playback.skip) add { modifier ->
            RemoteActionTile(
                Icons.Rounded.SkipNext,
                stringResource(if (playback.skipIntro) Res.string.remote_playback_skip_intro else Res.string.remote_playback_skip),
                null,
                primary = true,
                modifier = modifier.testTag("remote-skip-interval"),
                onClick = { DesktopRemote.command("skipInterval") },
            )
        }
        if (playback.episodes.isNotEmpty()) add { modifier ->
            RemoteActionTile(Icons.Rounded.VideoLibrary, episodesTitle, playback.episodes.firstOrNull { it.selected }?.label,
                modifier = modifier, onClick = { openList = "episode" })
        }
        if (playback.subtitles.isNotEmpty()) add { modifier ->
            RemoteActionTile(Icons.Rounded.ClosedCaption, subtitlesTitle, playback.subtitles.firstOrNull { it.selected }?.label,
                modifier = modifier, onClick = { openList = "subtitle" })
        }
        if (playback.audioTracks.isNotEmpty()) add { modifier ->
            RemoteActionTile(Icons.Rounded.GraphicEq, audioTracksTitle, playback.audioTracks.firstOrNull { it.selected }?.label,
                modifier = modifier.testTag("remote-audio-tracks"), onClick = { openList = "audioTrack" })
        }
        add { modifier ->
            RemoteActionTile(Icons.Rounded.Subtitles, stringResource(Res.string.remote_playback_find_subtitles), null,
                modifier = modifier, onClick = { DesktopRemote.command("fetchSubtitles") })
        }
        if (playback.sources.isNotEmpty()) add { modifier ->
            RemoteActionTile(Icons.Rounded.Source, sourcesTitle, playback.sources.firstOrNull { it.selected }?.label,
                modifier = modifier, onClick = { openList = "source" })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteSectionHeader(stringResource(Res.string.remote_playback_options))
        RemoteTileGrid(tiles)
    }
    when (openList) {
        "episode" -> RemoteChoiceList(episodesTitle, playback.episodes, "episode") { openList = null }
        "subtitle" -> RemoteChoiceList(subtitlesTitle, playback.subtitles, "subtitle") { openList = null }
        "audioTrack" -> RemoteChoiceList(audioTracksTitle, playback.audioTracks, "audioTrack") { openList = null }
        "source" -> RemoteChoiceList(sourcesTitle, playback.sources, "source") { openList = null }
    }
}

@Composable
private fun RemoteDownloadsPage() {
    val playback by DesktopRemote.remotePlayback.collectAsState()
    val tokens = MaterialTheme.nuvio
    if (playback.downloadProfileId == null || playback.downloadProfileId != ProfileRepository.activeProfileId) {
        EmptyState(Icons.Rounded.PersonOff, stringResource(Res.string.remote_downloads_title),
            stringResource(Res.string.remote_downloads_profile_mismatch))
        return
    }
    if (playback.downloads.isEmpty()) {
        EmptyState(Icons.Rounded.Download, stringResource(Res.string.remote_downloads_title),
            stringResource(Res.string.remote_downloads_empty))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteSectionHeader(stringResource(Res.string.remote_downloads_title))
        playback.downloads.forEach { task ->
            RemoteCard(spacing = 8.dp, contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val size = task.totalBytes?.let {
                            "${formatDownloadBytes(task.downloadedBytes)} / ${formatDownloadBytes(it)}"
                        } ?: formatDownloadBytes(task.downloadedBytes)
                        Text("${task.state} · $size", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                    }
                    when (task.state) {
                        DownloadStatus.Downloading -> FilledTonalIconButton(
                            onClick = { DesktopRemote.downloadCommand("downloadPause", task.id) },
                        ) { Icon(Icons.Rounded.Pause, contentDescription = stringResource(Res.string.remote_downloads_pause)) }
                        DownloadStatus.Paused -> FilledTonalIconButton(
                            onClick = { DesktopRemote.downloadCommand("downloadResume", task.id) },
                        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.remote_downloads_resume)) }
                        else -> Unit
                    }
                }
                if (task.totalBytes != null && task.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = tokens.colors.accent,
                        trackColor = tokens.colors.textPrimary.copy(alpha = 0.08f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteIconBadge(icon, tone = RemoteTone.Neutral, size = 72.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun remoteSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.nuvio.colors.accent,
    activeTrackColor = MaterialTheme.nuvio.colors.accent,
    inactiveTrackColor = MaterialTheme.nuvio.colors.textPrimary.copy(alpha = 0.14f),
)
