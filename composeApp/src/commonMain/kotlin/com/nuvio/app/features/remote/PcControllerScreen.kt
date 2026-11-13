package com.nuvio.app.features.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioEmptyBlock
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLabeledField
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSlider
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.NuvioStatusEyebrow
import com.nuvio.app.core.ui.NuvioStatusTone
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTag
import com.nuvio.app.core.ui.NuvioTagStyle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
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
    val activeDesktop by DesktopRemote.activeDesktop.collectAsState()
    var tab by rememberSaveable { mutableStateOf(PcControllerTab.Remote) }
    // Until a PC is paired, the useful page is finding one, so that is where the tabs start.
    var offlineTab by rememberSaveable { mutableStateOf(PcControllerTab.Pcs) }
    LaunchedEffect(connected) { if (!connected) offlineTab = PcControllerTab.Pcs }
    val shownTab = if (connected) tab else offlineTab
    val disconnectedStatus = stringResource(Res.string.remote_disconnected_status)
    val disconnect = {
        DesktopRemote.transport.disconnect()
        DesktopRemote.status.value = disconnectedStatus
    }
    // Keep each destination's scroll position when the user switches tabs.
    val remoteScroll = rememberScrollState()
    val downloadsScroll = rememberScrollState()
    val pcsScroll = rememberScrollState()
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .testTag("pc-controller-screen"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = tokens.spacing.screenHorizontal, end = tokens.spacing.screenHorizontal, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioSquareIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(Res.string.action_close),
                onClick = onClose,
                size = 36.dp,
                style = NuvioSquareIconButtonStyle.Surface,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (DesktopRemote.transport.supported) {
                    val pcName = activeDesktop?.name?.takeIf { it.isNotBlank() }
                    NuvioStatusEyebrow(
                        text = when {
                            connected && pcName != null -> stringResource(Res.string.remote_pc_connected_to, pcName)
                            connected -> stringResource(Res.string.remote_pc_status_connected)
                            connecting -> stringResource(Res.string.remote_pc_status_connecting)
                            scanning -> stringResource(Res.string.remote_pc_status_searching)
                            else -> stringResource(Res.string.remote_pc_status_idle)
                        },
                        tone = when {
                            connected -> NuvioStatusTone.Success
                            connecting || scanning -> NuvioStatusTone.Accent
                            else -> NuvioStatusTone.Muted
                        },
                        busy = !connected && (scanning || connecting),
                        modifier = Modifier.testTag("remote-status"),
                    )
                }
                Text(
                    stringResource(Res.string.remote_pc_controller_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NuvioSegmentedControl(
            segments = listOf(
                NuvioSegment(PcControllerTab.Remote, stringResource(Res.string.remote_pc_tab_remote)),
                NuvioSegment(PcControllerTab.Downloads, stringResource(Res.string.remote_pc_tab_downloads)),
                NuvioSegment(PcControllerTab.Pcs, stringResource(Res.string.remote_pc_tab_pcs)),
            ),
            selected = shownTab,
            onSelect = { if (connected) tab = it else offlineTab = it },
            fillWidth = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tokens.spacing.screenHorizontal, end = tokens.spacing.screenHorizontal, top = 16.dp),
        )
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
                .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                shownTab == PcControllerTab.Pcs -> FindPcPage(onDisconnect = disconnect)
                !connected -> NotConnectedNotice(onFindPc = { offlineTab = PcControllerTab.Pcs })
                shownTab == PcControllerTab.Remote -> RemotePlaybackPage()
                else -> RemoteDownloadsPage()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Remote and Downloads need a PC; point the way to the PCs tab instead of showing a blank page. */
@Composable
private fun NotConnectedNotice(onFindPc: () -> Unit) {
    NuvioSurfaceCard(outlined = true) {
        NuvioEmptyBlock(
            icon = Icons.Rounded.DesktopWindows,
            title = stringResource(Res.string.remote_pc_status_idle),
            message = stringResource(Res.string.remote_pc_hero_hint),
            tone = NuvioIconChipTone.Neutral,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            NuvioPillButton(
                text = stringResource(Res.string.remote_pc_hero_idle_title),
                onClick = onFindPc,
                style = NuvioPillButtonStyle.Primary,
            )
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
    val autoReconnect by DesktopRemote.autoReconnect.collectAsState()
    val groupedPcs = groupRememberedRemotePcs(savedPcs)
    val reachableLan = desktops.mapTo(mutableSetOf()) { it.address }
    val visibleLan = desktops.filterNot { found ->
        savedPcs.any { !it.bluetooth && !it.vpn && it.address == found.address }
    }
    val visibleBluetooth = bluetoothDesktops.filterNot { found ->
        savedPcs.any { it.bluetooth && it.address.equals(found.address, ignoreCase = true) }
    }
    val activeDesktop by DesktopRemote.activeDesktop.collectAsState()
    val tokens = MaterialTheme.nuvio
    val supported = DesktopRemote.transport.supported
    // Searching or connecting: other ways to connect wait until it ends.
    val busy = scanning || connecting
    // Help opens by itself only after a search that came back empty.
    var searched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scanning) { if (scanning) searched = true }
    val search = { DesktopRemote.transport.discover(deepScan = true) }

    // Status hero: what is happening right now, and the one main action.
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            NuvioIconChip(
                icon = Icons.Rounded.DesktopWindows,
                size = 48.dp,
                tone = if (connected) NuvioIconChipTone.Success else NuvioIconChipTone.Accent,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        when {
                            connected -> Res.string.remote_pc_hero_connected_title
                            connecting -> Res.string.remote_pc_hero_connecting_title
                            else -> Res.string.remote_pc_hero_idle_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                // The header already names the connected PC, and a concurrent search must not
                // replace that identity, so the hero only repeats the PC's name.
                val detail = when {
                    !supported -> stringResource(Res.string.remote_pc_unsupported)
                    connected -> if (activeDesktop?.vpn == true) {
                        stringResource(Res.string.remote_vpn_connected_to, activeDesktop?.name.orEmpty())
                    } else activeDesktop?.name.orEmpty()
                    busy && status.isNotBlank() -> status
                    else -> stringResource(Res.string.remote_pc_hero_hint)
                }
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                }
            }
        }
        if (supported) {
            if (connected) {
                // Searching stays available while connected, to switch to another PC.
                SideBySideOrStacked {
                    DialogButton(
                        text = stringResource(Res.string.remote_pc_search_local_network),
                        onClick = search,
                        modifier = Modifier.testTag("desktop-remote-search-local-network"),
                        enabled = !busy,
                        loading = scanning,
                    )
                    DialogButton(
                        text = stringResource(Res.string.remote_disconnect),
                        onClick = onDisconnect,
                        style = DialogButtonStyle.Destructive,
                    )
                }
            } else {
                NuvioPrimaryButton(
                    text = stringResource(
                        if (scanning) Res.string.remote_pc_hero_searching_title else Res.string.remote_pc_search_local_network,
                    ),
                    modifier = Modifier.testTag("desktop-remote-search-local-network"),
                    enabled = !busy,
                    loading = scanning,
                    icon = Icons.Rounded.WifiFind,
                    onClick = search,
                )
                if (!busy && status.isNotBlank()) {
                    Text(status, style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textSecondary)
                }
            }
        }
    }
    if (!supported) return

    NuvioGroup {
        NuvioGroupRow(
            title = stringResource(Res.string.remote_pc_auto_reconnect_title),
            description = stringResource(Res.string.remote_pc_auto_reconnect_caption),
            modifier = Modifier.testTag("remote-auto-reconnect"),
            onClick = { DesktopRemote.setAutoReconnect(!autoReconnect) },
            leading = { NuvioIconChip(Icons.Rounded.Wifi, tone = NuvioIconChipTone.Neutral) },
            trailing = {
                Switch(
                    checked = autoReconnect,
                    onCheckedChange = DesktopRemote::setAutoReconnect,
                    modifier = Modifier.testTag("remote-auto-reconnect-switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = tokens.colors.onAccent,
                        checkedTrackColor = tokens.colors.accent,
                        uncheckedThumbColor = tokens.colors.textMuted,
                        uncheckedTrackColor = tokens.colors.borderDefault,
                    ),
                )
            },
        )
    }

    if (visibleLan.isNotEmpty()) {
        FoundPcGroup(
            title = stringResource(Res.string.remote_pc_found_devices),
            devices = visibleLan,
            icon = Icons.Rounded.Computer,
            connected = connected,
            connecting = connecting,
            activeDesktop = activeDesktop,
            detail = { it.address },
        )
    }
    if (visibleBluetooth.isNotEmpty()) {
        val bluetoothLabel = stringResource(Res.string.remote_bluetooth_device, "%s")
        FoundPcGroup(
            title = stringResource(Res.string.remote_bluetooth_found_devices),
            devices = visibleBluetooth,
            icon = Icons.Rounded.Bluetooth,
            connected = connected,
            connecting = connecting,
            activeDesktop = activeDesktop,
            detail = { bluetoothLabel.replace("%s", it.address) },
        )
    }

    // A concurrent discovery can update the search message, but never replace
    // the connected PC's identity in the status hero.
    ConnectOptions(busy = busy, scanStatus = status.takeIf { connected && scanning })

    if (groupedPcs.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NuvioSectionLabel(stringResource(Res.string.remote_pc_saved_devices).uppercase())
            NuvioGroup {
                groupedPcs.forEachIndexed { index, pc ->
                    // Only a verified identity combines transports; keep each PC's editor state
                    // attached to its identity when the recent-connection order changes.
                    key(pc.key) {
                        if (index > 0) NuvioGroupDivider()
                        SavedPcRow(
                            pc = pc,
                            enabled = !busy,
                            active = pc.devices.any { device ->
                                isActiveRemoteDesktop(
                                    connected, activeDesktop, device.address, device.bluetooth, device.vpn,
                                )
                            },
                            reachable = pc.bluetooth != null || pc.vpn != null ||
                                pc.lan?.address?.let(reachableLan::contains) == true,
                            reachableLanAddresses = reachableLan,
                            onDisconnect = onDisconnect,
                        )
                    }
                }
            }
        }
    }
    if (!connected) {
        TroubleshootingCard(
            expandByDefault = searched && !busy && desktops.isEmpty() && bluetoothDesktops.isEmpty(),
        )
    }
}

/** PCs found by the last search, each with a Connect button. */
@Composable
private fun FoundPcGroup(
    title: String,
    devices: List<RemoteDesktop>,
    icon: ImageVector,
    connected: Boolean,
    connecting: Boolean,
    activeDesktop: RemoteDesktop?,
    detail: (RemoteDesktop) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(title.uppercase())
        NuvioGroup {
            devices.forEachIndexed { index, desktop ->
                key(desktop.bluetooth, desktop.vpn, desktop.address) {
                    if (index > 0) NuvioGroupDivider()
                    val active = isActiveRemoteDesktop(connected, activeDesktop, desktop.address, desktop.bluetooth, desktop.vpn)
                    val connect = { DesktopRemote.transport.connect(desktop) }
                    NuvioGroupRow(
                        title = desktop.name,
                        description = detail(desktop),
                        onClick = if (active) null else connect,
                        enabled = !connecting,
                        titleMaxLines = 1,
                        descriptionMaxLines = 1,
                        verticalPadding = 12.dp,
                        leading = { NuvioIconChip(if (desktop.vpn) Icons.Rounded.VpnKey else icon) },
                        trailing = {
                            if (active) {
                                NuvioTag(stringResource(Res.string.remote_pc_status_connected), style = NuvioTagStyle.Success)
                            } else {
                                NuvioPillButton(
                                    text = stringResource(Res.string.remote_pc_connect),
                                    onClick = connect,
                                    style = NuvioPillButtonStyle.Primary,
                                    enabled = !connecting,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** QR, Bluetooth and a typed address: the ways to connect besides searching the network. */
@Composable
private fun ConnectOptions(busy: Boolean, scanStatus: String?) {
    var manualIp by rememberSaveable { mutableStateOf("") }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var vpnEndpoint by rememberSaveable { mutableStateOf("") }
    var vpnOpen by rememberSaveable { mutableStateOf(false) }
    var qrError by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_window_other_ways).uppercase())
        NuvioGroup {
            if (qrScanner != null) {
                NuvioGroupRow(
                    title = stringResource(Res.string.remote_qr_scan),
                    description = stringResource(Res.string.remote_pc_tile_qr_caption),
                    modifier = Modifier.testTag("desktop-remote-scan-qr"),
                    onClick = { qrError = false; qrScanner() },
                    enabled = !busy,
                    leading = { NuvioIconChip(Icons.Rounded.QrCodeScanner) },
                    trailing = { RowChevron() },
                )
                NuvioGroupDivider()
            }
            if (bluetoothSearch != null) {
                NuvioGroupRow(
                    title = stringResource(Res.string.remote_bluetooth_search),
                    description = stringResource(Res.string.remote_pc_tile_bluetooth_caption),
                    modifier = Modifier.testTag("desktop-remote-search-bluetooth"),
                    onClick = bluetoothSearch,
                    enabled = !busy,
                    leading = { NuvioIconChip(Icons.Rounded.Bluetooth) },
                    trailing = { RowChevron() },
                )
                NuvioGroupDivider()
            }
            // A typed address stays available with an empty list, after a failed
            // search, and while connected to another PC.
            NuvioGroupRow(
                title = stringResource(Res.string.remote_pc_tile_address),
                description = stringResource(Res.string.remote_pc_tile_address_caption),
                modifier = Modifier.testTag("desktop-remote-enter-address"),
                onClick = { manualOpen = !manualOpen },
                leading = { NuvioIconChip(Icons.Rounded.Keyboard) },
                trailing = {
                    Icon(
                        if (manualOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            NuvioGroupDivider()
            NuvioGroupRow(
                title = stringResource(Res.string.remote_vpn_tile),
                description = stringResource(Res.string.remote_vpn_tile_caption),
                modifier = Modifier.testTag("desktop-remote-enter-vpn"),
                onClick = { vpnOpen = !vpnOpen },
                enabled = !busy,
                leading = { NuvioIconChip(Icons.Rounded.VpnKey) },
                trailing = {
                    Icon(
                        if (vpnOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }
        if (scanStatus != null) DialogHint(scanStatus)
        if (qrError) DialogHint(stringResource(Res.string.remote_qr_scan_failed), isError = true)
        AnimatedVisibility(visible = manualOpen) {
            NuvioGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NuvioLabeledField(
                        label = stringResource(Res.string.remote_pc_manual_ip_label),
                        helper = stringResource(Res.string.remote_pc_manual_ip_hint),
                    ) {
                        NuvioInputField(
                            value = manualIp,
                            onValueChange = { manualIp = it.take(45) },
                            placeholder = "192.168.1.20",
                            minHeight = 48.dp,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            modifier = Modifier.testTag("desktop-remote-manual-ip"),
                        )
                    }
                    DialogButton(
                        text = stringResource(Res.string.remote_pc_check_ip),
                        onClick = { DesktopRemote.transport.discover(manualIp.trim()) },
                        modifier = Modifier.fillMaxWidth(),
                        style = DialogButtonStyle.Primary,
                        enabled = !busy && manualIp.isNotBlank(),
                    )
                }
            }
        }
        AnimatedVisibility(visible = vpnOpen) {
            NuvioGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NuvioLabeledField(
                        label = stringResource(Res.string.remote_vpn_address_label),
                        helper = stringResource(Res.string.remote_vpn_phone_hint),
                    ) {
                        NuvioInputField(
                            value = vpnEndpoint,
                            onValueChange = { vpnEndpoint = it.take(253) },
                            placeholder = "100.101.42.18",
                            minHeight = 48.dp,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            modifier = Modifier.testTag("desktop-remote-vpn-address"),
                        )
                    }
                    DialogButton(
                        text = stringResource(Res.string.remote_vpn_check),
                        onClick = { DesktopRemote.transport.discoverVpn(vpnEndpoint.trim(), connect = true) },
                        modifier = Modifier.fillMaxWidth().testTag("desktop-remote-vpn-connect"),
                        style = DialogButtonStyle.Primary,
                        enabled = !busy && normalizePrivateVpnAddress(vpnEndpoint) != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowChevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun SavedPcRow(
    pc: RememberedRemotePc,
    enabled: Boolean,
    active: Boolean,
    reachable: Boolean,
    reachableLanAddresses: Set<String>,
    onDisconnect: () -> Unit,
) {
    val saved = pc.primary
    val lan = pc.lan
    val bluetooth = pc.bluetooth
    val vpn = pc.vpn
    var menuOpen by remember { mutableStateOf(false) }
    var editingWake by remember { mutableStateOf(false) }
    var macDraft by remember(lan?.wakeMac) { mutableStateOf(lan?.wakeMac.orEmpty()) }
    var macError by remember { mutableStateOf(false) }
    val connect = { connectRememberedRemotePc(DesktopRemote.transport, pc, reachableLanAddresses) }
    val wifiDescription = stringResource(Res.string.remote_pc_saved_wifi)
    val bluetoothDescription = stringResource(Res.string.remote_pc_saved_bluetooth)
    val vpnDescription = stringResource(Res.string.remote_vpn_tile)
    val description = listOfNotNull(
        wifiDescription.takeIf { lan != null },
        bluetoothDescription.takeIf { bluetooth != null },
        vpn?.let { "$vpnDescription · ${it.address}" },
    ).joinToString(" · ")
    Column {
        NuvioGroupRow(
            title = pc.name,
            description = description,
            modifier = Modifier.testTag("remote-saved-pc-${saved.address}"),
            onClick = if (active) null else connect,
            enabled = enabled,
            titleMaxLines = 1,
            verticalPadding = 12.dp,
            endPadding = 8.dp,
            spacing = 12.dp,
            leading = {
                NuvioIconChip(
                    icon = when {
                        lan != null -> Icons.Rounded.Wifi
                        vpn != null -> Icons.Rounded.VpnKey
                        else -> Icons.Rounded.Bluetooth
                    },
                    tone = NuvioIconChipTone.Neutral,
                )
            },
            trailing = {
                when {
                    active -> NuvioTag(stringResource(Res.string.remote_pc_status_connected), style = NuvioTagStyle.Success)
                    reachable || lan == null -> NuvioPillButton(
                        text = stringResource(Res.string.remote_pc_connect),
                        onClick = connect,
                        style = NuvioPillButtonStyle.Primary,
                        enabled = enabled,
                    )
                    else -> NuvioPillButton(
                        text = stringResource(Res.string.remote_pc_wake),
                        onClick = {
                            lan?.let { savedLan ->
                                val mac = savedLan.wakeMac
                                if (mac != null) DesktopRemote.transport.wake(savedLan.address, mac)
                                else {
                                    editingWake = true
                                    macError = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("remote-wake-pc-${lan?.address}"),
                    )
                }
                Box {
                    NuvioSquareIconButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(Res.string.remote_pc_more_options, pc.name),
                        onClick = { menuOpen = true },
                        size = 36.dp,
                        style = NuvioSquareIconButtonStyle.Ghost,
                        tint = MaterialTheme.nuvio.colors.textMuted,
                    )
                    Menu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem(
                            text = stringResource(if (active) Res.string.remote_disconnect else Res.string.remote_pc_connect),
                            enabled = enabled,
                            onClick = { menuOpen = false; if (active) onDisconnect() else connect() },
                        )
                        if (lan != null) {
                            MenuItem(
                                text = stringResource(Res.string.remote_pc_set_wake_mac),
                                selected = editingWake,
                                onClick = { menuOpen = false; editingWake = !editingWake; macError = false },
                            )
                        }
                        MenuItem(
                            text = stringResource(Res.string.remote_pc_forget),
                            onClick = { menuOpen = false; DesktopRemote.forgetPc(pc) },
                        )
                    }
                }
            },
        )
        AnimatedVisibility(visible = editingWake && lan != null) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NuvioLabeledField(
                    label = stringResource(Res.string.remote_pc_mac_label),
                    helper = stringResource(if (macError) Res.string.remote_pc_invalid_mac else Res.string.remote_pc_mac_help),
                    isError = macError,
                ) {
                    NuvioInputField(
                        value = macDraft,
                        onValueChange = { macDraft = it.take(20); macError = false },
                        placeholder = "00:1A:2B:3C:4D:5E",
                        minHeight = 48.dp,
                        isError = macError,
                    )
                }
                NuvioPillButton(
                    text = stringResource(Res.string.remote_pc_save_mac),
                    onClick = {
                        if (lan != null && DesktopRemote.saveWakeAddress(lan.address, macDraft)) editingWake = false
                        else macError = true
                    },
                    style = NuvioPillButtonStyle.Primary,
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
    NuvioGroup {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { userToggled = true; expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Help, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.remote_pc_troubleshoot_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary,
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    Res.string.remote_pc_troubleshoot_open,
                    Res.string.remote_pc_troubleshoot_network,
                    Res.string.remote_pc_troubleshoot_firewall,
                    Res.string.remote_pc_troubleshoot_fallback,
                ).forEachIndexed { index, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NuvioIconChip(size = 24.dp, shape = CircleShape) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.nuvioTypeScale.labelXs.tabular,
                                color = tokens.colors.accent,
                            )
                        }
                        Text(
                            stringResource(line),
                            Modifier.weight(1f).padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemotePlaybackPage() {
    val playback by DesktopRemote.remotePlayback.collectAsState()
    val idle = playback.playbackId.isEmpty()
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (idle) {
            NuvioEmptyBlock(
                icon = Icons.Rounded.Tv,
                title = stringResource(Res.string.remote_playback_idle_title),
                message = stringResource(Res.string.remote_playback_idle_body),
                tone = NuvioIconChipTone.Neutral,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            NuvioSectionLabel(stringResource(Res.string.remote_playback_now_playing).uppercase())
            NowPlayingSummary(playback)
            SeekBar(playback)
            TransportRow(playback)
            VolumeRow(playback)
        }
        RemoteWindowActions(playback, playing = !idle)
    }
    if (!idle) PlaybackOptions(playback)
}

/** Poster, title and episode line. Older PCs send one combined title and no artwork. */
@Composable
private fun NowPlayingSummary(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val split = playback.mediaTitle.isNotBlank()
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        val posterShape = tokens.shapes.compactCard
        Box(
            Modifier
                .size(width = 56.dp, height = 84.dp)
                .clip(posterShape)
                .background(tokens.colors.surfaceCard)
                .border(tokens.borders.thin, tokens.colors.borderSubtle, posterShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Movie, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(24.dp))
            if (playback.poster.isNotBlank()) {
                NuvioAsyncImage(
                    model = playback.poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (split) playback.mediaTitle else playback.title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                maxLines = if (split) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (split && playback.episodeLine.isNotBlank()) {
                Text(
                    playback.episodeLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playback.message.isNotBlank()) {
                Text(playback.message, style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted)
            }
        }
    }
}

/** Desktop-window actions. Fullscreen works while idle too; Skip only shows while it applies. */
@Composable
private fun RemoteWindowActions(playback: RemotePlayback, playing: Boolean) {
    val showSkip = playing && playback.skip
    if (!playback.fullscreenSupported && !showSkip) return
    SideBySideOrStacked {
        if (playback.fullscreenSupported) {
            RemoteWideButton(
                icon = if (playback.fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                label = stringResource(
                    if (playback.fullscreen) Res.string.remote_playback_fullscreen_exit
                    else Res.string.remote_playback_fullscreen_enter,
                ),
                modifier = Modifier.testTag("remote-toggle-fullscreen"),
                onClick = { DesktopRemote.command("setFullscreen", if (playback.fullscreen) 0.0 else 1.0) },
            )
        }
        if (showSkip) {
            RemoteWideButton(
                icon = Icons.Rounded.FastForward,
                label = stringResource(if (playback.skipIntro) Res.string.remote_playback_skip_intro else Res.string.remote_playback_skip),
                emphasized = true,
                modifier = Modifier.testTag("remote-skip-interval"),
                onClick = { DesktopRemote.command("skipInterval") },
            )
        }
    }
}

/**
 * Equal-width buttons on one line when every label fits, otherwise one full-width button per
 * line, so long translations never clip.
 */
@Composable
private fun SideBySideOrStacked(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val width = constraints.maxWidth
        val count = measurables.size.coerceAtLeast(1)
        val itemWidth = (width - gap * (count - 1)) / count
        val sideBySide = measurables.all { it.maxIntrinsicWidth(constraints.maxHeight) <= itemWidth }
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(if (sideBySide) itemWidth else width)) }
        if (sideBySide) {
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * (itemWidth + gap), 0) }
            }
        } else {
            layout(width, placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

/** A 44 dp button with an icon: white 8 %, or accent-soft with an accent outline when [emphasized]. */
@Composable
private fun RemoteWideButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.button
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(
                if (emphasized) tokens.colors.accent.copy(alpha = tokens.opacity.pressed)
                else tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover),
            )
            .then(if (emphasized) Modifier.border(tokens.borders.thin, tokens.colors.borderSelected, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun SeekBar(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val duration = playback.duration.coerceAtLeast(1)
    var seek by remember { mutableStateOf<Float?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NuvioSlider(
            value = seek ?: (playback.position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
            onValueChange = { seek = it },
            onValueChangeFinished = { seek?.let { DesktopRemote.command("seek", it.toDouble() * duration) }; seek = null },
            enabled = playback.duration > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val timeStyle = MaterialTheme.nuvioTypeScale.labelSm.tabular.copy(fontWeight = FontWeight.Normal)
            Text(remoteTime(seek?.let { (it * duration).toLong() } ?: playback.position), style = timeStyle, color = tokens.colors.textMuted)
            Text(remoteTime(playback.duration), style = timeStyle, color = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun TransportRow(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        NuvioSquareIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(Res.string.remote_playback_previous),
            onClick = { DesktopRemote.command("previous") },
            size = 44.dp,
            style = NuvioSquareIconButtonStyle.Ghost,
            iconSize = 24.dp,
            shape = tokens.shapes.button,
        )
        NuvioSquareIconButton(
            icon = Icons.Rounded.Replay10,
            contentDescription = stringResource(Res.string.remote_playback_rewind),
            onClick = { DesktopRemote.command("seekBy", -10_000.0) },
            size = 52.dp,
            iconSize = 26.dp,
            shape = tokens.shapes.button,
        )
        NuvioSquareIconButton(
            icon = if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (playback.playing) Res.string.remote_playback_pause else Res.string.remote_playback_play),
            onClick = { DesktopRemote.command(if (playback.playing) "pause" else "play") },
            size = 64.dp,
            style = NuvioSquareIconButtonStyle.Accent,
            iconSize = 32.dp,
            shape = CircleShape,
        )
        NuvioSquareIconButton(
            icon = Icons.Rounded.Forward10,
            contentDescription = stringResource(Res.string.remote_playback_forward),
            onClick = { DesktopRemote.command("seekBy", 10_000.0) },
            size = 52.dp,
            iconSize = 26.dp,
            shape = tokens.shapes.button,
        )
        NuvioSquareIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(Res.string.remote_playback_next),
            onClick = { DesktopRemote.command("next") },
            size = 44.dp,
            style = NuvioSquareIconButtonStyle.Ghost,
            enabled = playback.nextEpisodeAvailable,
            iconSize = 24.dp,
            shape = tokens.shapes.button,
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
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvioSquareIconButton(
            icon = if (shownVolume <= 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(Res.string.remote_playback_mute),
            onClick = {
                draggedVolume = null
                awaitingVolume = null
                DesktopRemote.command("mute")
            },
            size = 32.dp,
            style = NuvioSquareIconButtonStyle.Ghost,
        )
        NuvioSlider(
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
            modifier = Modifier.weight(1f).testTag("remote-volume-slider"),
        )
        Text(
            "${(shownVolume * 100).roundToInt()}%",
            style = MaterialTheme.nuvioTypeScale.bodySm.tabular,
            color = tokens.colors.textMuted,
            modifier = Modifier.widthIn(min = 40.dp).testTag("remote-volume-value"),
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
    val findSubtitles = stringResource(Res.string.remote_playback_find_subtitles)
    val fetchSubtitles = { DesktopRemote.command("fetchSubtitles") }
    val rows = buildList<@Composable () -> Unit> {
        if (playback.episodes.isNotEmpty()) add {
            PlaybackOptionRow(Icons.Rounded.VideoLibrary, episodesTitle, playback.episodes.firstOrNull { it.selected }?.label) {
                openList = "episode"
            }
        }
        // An older PC sends no subtitle list; the row then looks for add-on subtitles directly.
        add {
            if (playback.subtitles.isNotEmpty()) {
                PlaybackOptionRow(Icons.Rounded.ClosedCaption, subtitlesTitle, playback.subtitles.firstOrNull { it.selected }?.label) {
                    openList = "subtitle"
                }
            } else {
                PlaybackOptionRow(Icons.Rounded.ClosedCaption, subtitlesTitle, findSubtitles, onClick = fetchSubtitles)
            }
        }
        if (playback.audioTracks.isNotEmpty()) add {
            PlaybackOptionRow(
                Icons.Rounded.GraphicEq,
                audioTracksTitle,
                playback.audioTracks.firstOrNull { it.selected }?.label,
                Modifier.testTag("remote-audio-tracks"),
            ) { openList = "audioTrack" }
        }
        if (playback.sources.isNotEmpty()) add {
            PlaybackOptionRow(Icons.Rounded.Storage, sourcesTitle, playback.sources.firstOrNull { it.selected }?.label) {
                openList = "source"
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_playback_options).uppercase())
        NuvioGroup {
            rows.forEachIndexed { index, row ->
                if (index > 0) NuvioGroupDivider()
                row()
            }
        }
    }
    when (openList) {
        "episode" -> RemoteChoiceList(episodesTitle, playback.episodes, "episode") { openList = null }
        "subtitle" -> RemoteChoiceList(
            title = subtitlesTitle,
            items = playback.subtitles,
            command = "subtitle",
            actionLabel = findSubtitles,
            onAction = fetchSubtitles,
        ) { openList = null }
        "audioTrack" -> RemoteChoiceList(audioTracksTitle, playback.audioTracks, "audioTrack") { openList = null }
        "source" -> RemoteChoiceList(sourcesTitle, playback.sources, "source") { openList = null }
    }
}

@Composable
private fun PlaybackOptionRow(
    icon: ImageVector,
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NuvioGroupRow(
        title = title,
        description = value,
        modifier = modifier,
        onClick = onClick,
        titleMaxLines = 1,
        descriptionMaxLines = 1,
        leading = { NuvioIconChip(icon) },
        trailing = { RowChevron() },
    )
}

@Composable
private fun RemoteDownloadsPage() {
    val playback by DesktopRemote.remotePlayback.collectAsState()
    val tokens = MaterialTheme.nuvio
    if (playback.downloadProfileId == null || playback.downloadProfileId != ProfileRepository.activeProfileId) {
        RemoteEmptyCard(Icons.Rounded.PersonOff, stringResource(Res.string.remote_downloads_title),
            stringResource(Res.string.remote_downloads_profile_mismatch))
        return
    }
    if (playback.downloads.isEmpty()) {
        RemoteEmptyCard(Icons.Rounded.Download, stringResource(Res.string.remote_downloads_title),
            stringResource(Res.string.remote_downloads_empty))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_downloads_title).uppercase())
        NuvioGroup {
            playback.downloads.forEachIndexed { index, task ->
                if (index > 0) NuvioGroupDivider(startInset = 16.dp)
                Column(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val size = task.totalBytes?.let {
                                "${formatDownloadBytes(task.downloadedBytes)} / ${formatDownloadBytes(it)}"
                            } ?: formatDownloadBytes(task.downloadedBytes)
                            Text("${task.state} · $size", style = MaterialTheme.nuvioTypeScale.bodySm.tabular,
                                color = tokens.colors.textMuted)
                        }
                        when (task.state) {
                            DownloadStatus.Downloading -> NuvioSquareIconButton(
                                icon = Icons.Rounded.Pause,
                                contentDescription = stringResource(Res.string.remote_downloads_pause),
                                onClick = { DesktopRemote.downloadCommand("downloadPause", task.id) },
                            )
                            DownloadStatus.Paused -> NuvioSquareIconButton(
                                icon = Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(Res.string.remote_downloads_resume),
                                onClick = { DesktopRemote.downloadCommand("downloadResume", task.id) },
                            )
                            else -> Unit
                        }
                    }
                    if (task.totalBytes != null && task.totalBytes > 0L) {
                        NuvioProgressBar(
                            progress = (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f),
                            modifier = Modifier.padding(end = 4.dp),
                            trackColor = tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover),
                            fillColor = if (task.state == DownloadStatus.Paused) tokens.colors.textMuted else tokens.colors.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteEmptyCard(icon: ImageVector, title: String, body: String) {
    NuvioSurfaceCard(outlined = true) {
        NuvioEmptyBlock(
            icon = icon,
            title = title,
            message = body,
            tone = NuvioIconChipTone.Neutral,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}
