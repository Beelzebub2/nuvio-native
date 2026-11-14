package com.nuvio.app.features.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.VpnKey
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
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
import com.nuvio.app.core.ui.NuvioSectionLabel
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
import com.nuvio.app.core.ui.skeleton
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

private enum class PcControllerTab { Remote, Downloads, Pcs, Tv }

/** Phone's PC and smart-TV controller; the TV remote does not depend on a PC connection. */
@Composable
internal fun PcControllerScreen(onClose: () -> Unit) {
    val connected by DesktopRemote.connected.collectAsState()
    val scanning by DesktopRemote.scanning.collectAsState()
    val connecting by DesktopRemote.connecting.collectAsState()
    val activeDesktop by DesktopRemote.activeDesktop.collectAsState()
    val activeTv by SmartTvRemote.active.collectAsState()
    var tab by rememberSaveable { mutableStateOf(PcControllerTab.Remote) }
    // Until a PC is paired, the useful page is finding one, so that is where the tabs start.
    var offlineTab by rememberSaveable { mutableStateOf(PcControllerTab.Pcs) }
    LaunchedEffect(connected) {
        if (!connected) offlineTab = if (tab == PcControllerTab.Tv) PcControllerTab.Tv else PcControllerTab.Pcs
    }
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
    val tvScroll = rememberScrollState()
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
                icon = RemoteIcons.Back,
                contentDescription = stringResource(Res.string.action_close),
                onClick = onClose,
                size = 44.dp,
                style = NuvioSquareIconButtonStyle.Surface,
                iconSize = 22.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (shownTab == PcControllerTab.Tv) {
                    NuvioStatusEyebrow(
                        text = activeTv?.name ?: stringResource(Res.string.remote_tv_find_title),
                        tone = if (activeTv != null) NuvioStatusTone.Success else NuvioStatusTone.Muted,
                        modifier = Modifier.testTag("remote-status"),
                    )
                } else if (DesktopRemote.transport.supported) {
                    val pcName = activeDesktop?.name?.takeIf { it.isNotBlank() }
                    val statusText = when {
                        connected && pcName != null -> stringResource(Res.string.remote_pc_connected_to, pcName)
                        connected -> stringResource(Res.string.remote_pc_status_connected)
                        connecting -> stringResource(Res.string.remote_pc_status_connecting)
                        scanning -> stringResource(Res.string.remote_pc_status_searching)
                        else -> stringResource(Res.string.remote_pc_status_idle)
                    }
                    if (connected) {
                        // A live dot: the connection is up and the PC answers.
                        Row(
                            Modifier.testTag("remote-status"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RemoteLiveDot(tokens.colors.success)
                            Text(
                                statusText,
                                style = MaterialTheme.nuvioTypeScale.bodySm,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        NuvioStatusEyebrow(
                            text = statusText,
                            tone = if (connecting || scanning) NuvioStatusTone.Accent else NuvioStatusTone.Muted,
                            busy = scanning || connecting,
                            modifier = Modifier.testTag("remote-status"),
                        )
                    }
                }
                Text(
                    stringResource(if (shownTab == PcControllerTab.Tv) Res.string.remote_tv_title else Res.string.remote_pc_controller_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connected && shownTab != PcControllerTab.Tv) {
                NuvioSquareIconButton(
                    icon = when {
                        activeDesktop?.vpn == true -> Icons.Rounded.VpnKey
                        activeDesktop?.bluetooth == true -> RemoteIcons.Bluetooth
                        else -> RemoteIcons.Wifi
                    },
                    contentDescription = stringResource(Res.string.remote_pc_manage),
                    onClick = { tab = PcControllerTab.Pcs },
                    size = 44.dp,
                    style = NuvioSquareIconButtonStyle.Surface,
                    iconSize = 22.dp,
                    tint = tokens.colors.success,
                )
            }
        }
        val playback by DesktopRemote.remotePlayback.collectAsState()
        val activeDownloads = if (connected && playback.downloadProfileId == ProfileRepository.activeProfileId) {
            playback.downloads.count { it.state == DownloadStatus.Downloading }
        } else 0
        RemoteControllerTabs(
            tabs = listOf(
                RemoteTabItem(PcControllerTab.Remote, stringResource(Res.string.remote_pc_tab_remote), RemoteIcons.Remote),
                RemoteTabItem(
                    PcControllerTab.Downloads,
                    stringResource(Res.string.remote_pc_tab_downloads),
                    RemoteIcons.Download,
                    badge = activeDownloads.takeIf { it > 0 }?.toString(),
                    badgeDescription = stringResource(Res.string.remote_pc_tab_downloads_active, activeDownloads),
                ),
                RemoteTabItem(PcControllerTab.Pcs, stringResource(Res.string.remote_pc_tab_pcs), RemoteIcons.Pc),
                RemoteTabItem(PcControllerTab.Tv, stringResource(Res.string.remote_tv_tab), RemoteIcons.Tv),
            ),
            selected = shownTab,
            onSelect = {
                if (connected) tab = it else offlineTab = it
                if (it == PcControllerTab.Tv) {
                    tab = it
                    offlineTab = it
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tokens.spacing.screenHorizontal, end = tokens.spacing.screenHorizontal, top = 18.dp),
        )
        val scroll = when (shownTab) {
            PcControllerTab.Remote -> remoteScroll
            PcControllerTab.Downloads -> downloadsScroll
            PcControllerTab.Pcs -> pcsScroll
            PcControllerTab.Tv -> tvScroll
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Each page rises in when its tab opens; scroll positions stay with their tab.
            key(shownTab) {
                Column(
                    Modifier.remoteRiseIn(distance = 8.dp, durationMillis = NuvioTokens.Motion.normalMillis),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when {
                        shownTab == PcControllerTab.Pcs -> FindPcPage(onDisconnect = disconnect)
                        shownTab == PcControllerTab.Tv -> SmartTvRemotePage()
                        !connected -> NotConnectedNotice(onFindPc = { offlineTab = PcControllerTab.Pcs })
                        shownTab == PcControllerTab.Remote -> RemotePlaybackPage()
                        else -> RemoteDownloadsPage()
                    }
                }
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
            icon = RemoteIcons.Pc,
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
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemoteSearchRadar(
                searching = supported && busy,
                connected = connected,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    textAlign = TextAlign.Center,
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
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
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
                    icon = RemoteIcons.Search,
                    onClick = search,
                )
                if (!busy && status.isNotBlank()) {
                    Text(
                        status,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.nuvioTypeScale.bodySm,
                        color = tokens.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
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
            leading = { NuvioIconChip(RemoteIcons.Wifi, tone = NuvioIconChipTone.Neutral) },
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

    // While a search runs, a shimmering row says more PCs may still appear.
    if (visibleLan.isNotEmpty() || scanning) {
        FoundPcGroup(
            title = stringResource(Res.string.remote_pc_found_devices),
            devices = visibleLan,
            icon = RemoteIcons.Pc,
            connected = connected,
            connecting = connecting,
            activeDesktop = activeDesktop,
            searching = scanning,
            detail = { it.address },
        )
    }
    if (visibleBluetooth.isNotEmpty()) {
        val bluetoothLabel = stringResource(Res.string.remote_bluetooth_device, "%s")
        FoundPcGroup(
            title = stringResource(Res.string.remote_bluetooth_found_devices),
            devices = visibleBluetooth,
            icon = RemoteIcons.Bluetooth,
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
    searching: Boolean = false,
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
                        // Each PC rises in as the search finds it, a beat after the one before.
                        modifier = Modifier.remoteRiseIn(delayMillis = (index * 120).coerceAtMost(480)),
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
            if (searching) {
                if (devices.isNotEmpty()) NuvioGroupDivider()
                SearchingPlaceholderRow()
            }
        }
    }
}

/** A shimmering stand-in row while the search may still find more PCs. */
@Composable
private fun SearchingPlaceholderRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).skeleton(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(11.dp).skeleton(RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(0.35f).height(9.dp).skeleton(RoundedCornerShape(5.dp)))
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
                    leading = { NuvioIconChip(RemoteIcons.Qr) },
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
                    leading = { NuvioIconChip(RemoteIcons.Bluetooth) },
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
                leading = { NuvioIconChip(RemoteIcons.Address) },
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
        RemoteIcons.Chevron,
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
                        lan != null -> RemoteIcons.Wifi
                        vpn != null -> Icons.Rounded.VpnKey
                        else -> RemoteIcons.Bluetooth
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
    val activeDesktop by DesktopRemote.activeDesktop.collectAsState()
    if (playback.playbackId.isEmpty()) {
        RemoteIdleCard(pcName = activeDesktop?.name?.takeIf { it.isNotBlank() })
        // Fullscreen works while idle too, so the PC window keeps its one control here.
        if (playback.fullscreenSupported) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NuvioSectionLabel(stringResource(Res.string.remote_playback_pc_window).uppercase())
                RemoteFullscreenToggle(playback)
            }
        }
        return
    }
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NowPlayingSummary(playback)
        SeekBar(playback)
        // Skip only appears while an intro or credits interval applies.
        AnimatedVisibility(
            visible = playback.skip,
            enter = expandVertically(tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard)) +
                fadeIn(tween(NuvioTokens.Motion.normalMillis)),
            exit = shrinkVertically(tween(NuvioTokens.Motion.fastMillis)) + fadeOut(tween(NuvioTokens.Motion.fastMillis)),
        ) {
            RemoteSkipPill(
                label = stringResource(if (playback.skipIntro) Res.string.remote_playback_skip_intro else Res.string.remote_playback_skip),
                onClick = { DesktopRemote.command("skipInterval") },
                modifier = Modifier.testTag("remote-skip-interval"),
            )
        }
        TransportRow(playback)
        VolumeRow(playback)
    }
    PlaybackOptions(playback)
}

/** Connected but idle: the PC breathes softly while the controller waits for a video. */
@Composable
private fun RemoteIdleCard(pcName: String?) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(outlined = true) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RemoteIdleIllustration(Modifier.remoteRiseIn())
            Text(
                stringResource(Res.string.remote_playback_idle_title),
                Modifier.padding(top = 24.dp).remoteRiseIn(delayMillis = 80),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.remote_playback_idle_body),
                Modifier.padding(top = 6.dp).widthIn(max = 280.dp).remoteRiseIn(delayMillis = 160),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (pcName != null) {
                Row(
                    Modifier
                        .padding(top = 20.dp)
                        .remoteRiseIn(delayMillis = 240)
                        .clip(CircleShape)
                        .background(tokens.colors.success.copy(alpha = 0.08f))
                        .border(tokens.borders.thin, tokens.colors.success.copy(alpha = 0.22f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteLiveDot(tokens.colors.success)
                    Text(
                        stringResource(Res.string.remote_playback_waiting_on, pcName),
                        style = MaterialTheme.nuvioTypeScale.labelSm,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.success,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Poster, title and episode line. Older PCs send one combined title and no artwork. */
@Composable
private fun NowPlayingSummary(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val split = playback.mediaTitle.isNotBlank()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        val posterShape = tokens.shapes.compactCard
        Box(
            Modifier
                .size(width = 76.dp, height = 114.dp)
                .clip(posterShape)
                .background(tokens.colors.surfaceCard)
                .border(tokens.borders.thin, tokens.colors.borderSubtle, posterShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RemoteIcons.Poster, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(26.dp))
            if (playback.poster.isNotBlank()) {
                NuvioAsyncImage(
                    model = playback.poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteNowPlayingBars(playing = playback.playing, color = tokens.colors.accent)
                Text(
                    stringResource(Res.string.remote_playback_now_playing).uppercase(),
                    style = MaterialTheme.nuvioTypeScale.labelXs,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = NuvioTokens.LetterSpacing.label,
                    color = tokens.colors.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            val timeStyle = MaterialTheme.nuvioTypeScale.labelSm.tabular.copy(fontWeight = FontWeight.Medium)
            val position = seek?.let { (it * duration).toLong() } ?: playback.position
            Text(remoteTime(position), style = timeStyle, color = tokens.colors.textMuted)
            // Time left reads better than the total while watching from the couch.
            val remaining = if (playback.duration > 0) "−" + remoteTime((playback.duration - position).coerceAtLeast(0)) else remoteTime(0)
            Text(remaining, style = timeStyle, color = tokens.colors.textMuted)
        }
    }
}

/** Previous, back 10 s, play/pause, forward 10 s and next: evenly spaced, the main key largest. */
@Composable
private fun TransportRow(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val soft = tokens.colors.textPrimary.copy(alpha = 0.07f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The row needs about 296 dp at full size; narrow phones get proportionally smaller keys.
        val scale = (maxWidth / 296.dp).coerceIn(0.8f, 1f)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.remote_playback_previous),
                onClick = { DesktopRemote.command("previous") },
                size = 48.dp * scale,
            ) {
                Icon(RemoteIcons.Previous, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(26.dp * scale))
            }
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.remote_playback_rewind),
                onClick = { DesktopRemote.command("seekBy", -10_000.0) },
                size = 60.dp * scale,
                container = soft,
                pressRotation = -45f,
            ) {
                RemoteSeekTenIcon(forward = false, tint = tokens.colors.textPrimary, size = 30.dp * scale)
            }
            RemotePlayPauseButton(
                playing = playback.playing,
                contentDescription = stringResource(if (playback.playing) Res.string.remote_playback_pause else Res.string.remote_playback_play),
                onClick = { DesktopRemote.command(if (playback.playing) "pause" else "play") },
                size = 76.dp * scale,
            )
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.remote_playback_forward),
                onClick = { DesktopRemote.command("seekBy", 10_000.0) },
                size = 60.dp * scale,
                container = soft,
                pressRotation = 45f,
            ) {
                RemoteSeekTenIcon(forward = true, tint = tokens.colors.textPrimary, size = 30.dp * scale)
            }
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.remote_playback_next),
                onClick = { DesktopRemote.command("next") },
                size = 48.dp * scale,
                enabled = playback.nextEpisodeAvailable,
            ) {
                Icon(RemoteIcons.Next, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(26.dp * scale))
            }
        }
    }
}

@Composable
private fun VolumeRow(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val maxVolume = playback.effectiveVolumeMax()
    val pcVolume = playback.volume.takeIf { it.isFinite() }?.coerceIn(0f, REMOTE_MAX_VOLUME_LEVEL) ?: 1f
    var draggedVolume by remember(playback.playbackId) { mutableStateOf<Float?>(null) }
    var awaitingVolume by remember(playback.playbackId) { mutableStateOf<Float?>(null) }

    // Keep the fill at the requested level until the PC has actually acknowledged it.
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteRoundButton(
            contentDescription = stringResource(Res.string.remote_playback_mute),
            onClick = {
                draggedVolume = null
                awaitingVolume = null
                DesktopRemote.command("mute")
            },
            size = 52.dp,
            container = tokens.colors.textPrimary.copy(alpha = 0.06f),
            shape = RoundedCornerShape(16.dp),
        ) {
            RemoteSpeakerIcon(level = shownVolume, tint = tokens.colors.textPrimary)
        }
        RemoteVolumePill(
            value = shownVolume,
            max = maxVolume,
            label = stringResource(Res.string.remote_playback_volume),
            boostLabel = stringResource(Res.string.remote_playback_volume_boost),
            onValueChange = { draggedVolume = it.coerceIn(0f, maxVolume) },
            onValueChangeFinished = {
                draggedVolume?.let { requested ->
                    awaitingVolume = requested
                    DesktopRemote.command("volume", requested.toDouble())
                }
                draggedVolume = null
            },
            modifier = Modifier.weight(1f).testTag("remote-volume-slider"),
            valueModifier = Modifier.testTag("remote-volume-value"),
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
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        // An older PC sends no subtitle list; the tile then looks for add-on subtitles directly.
        add { modifier ->
            if (playback.subtitles.isNotEmpty()) {
                RemoteOptionTile(RemoteIcons.Subtitles, subtitlesTitle, playback.subtitles.firstOrNull { it.selected }?.label,
                    onClick = { openList = "subtitle" }, modifier = modifier)
            } else {
                RemoteOptionTile(RemoteIcons.Subtitles, subtitlesTitle, findSubtitles, onClick = fetchSubtitles, modifier = modifier)
            }
        }
        if (playback.audioTracks.isNotEmpty()) add { modifier ->
            RemoteOptionTile(
                RemoteIcons.Audio,
                audioTracksTitle,
                playback.audioTracks.firstOrNull { it.selected }?.label,
                onClick = { openList = "audioTrack" },
                modifier = modifier.testTag("remote-audio-tracks"),
            )
        }
        if (playback.episodes.isNotEmpty()) add { modifier ->
            RemoteOptionTile(RemoteIcons.Episodes, episodesTitle, playback.episodes.firstOrNull { it.selected }?.label,
                onClick = { openList = "episode" }, modifier = modifier)
        }
        if (playback.sources.isNotEmpty()) add { modifier ->
            RemoteOptionTile(RemoteIcons.Source, sourcesTitle, playback.sources.firstOrNull { it.selected }?.label,
                onClick = { openList = "source" }, modifier = modifier)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_playback_options).uppercase())
        tiles.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        if (playback.fullscreenSupported) RemoteFullscreenToggle(playback)
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

/** Fullscreen on the PC as a switch, so the row says what the PC window is doing right now. */
@Composable
private fun RemoteFullscreenToggle(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(18.dp)
    val fullscreen = playback.fullscreen
    val interaction = remember { MutableInteractionSource() }
    val border by animateColorAsState(
        targetValue = if (fullscreen) tokens.colors.accent.copy(alpha = 0.5f) else tokens.colors.borderSubtle,
        animationSpec = tween(NuvioTokens.Motion.normalMillis),
        label = "remote_fullscreen_border",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("remote-toggle-fullscreen")
            .remotePressScale(interaction, pressedScale = 0.98f)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(tokens.borders.thin, border, shape)
            .toggleable(
                value = fullscreen,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = { DesktopRemote.command("setFullscreen", if (it) 1.0 else 0.0) },
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteIconBox(RemoteIcons.Fullscreen)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                stringResource(Res.string.remote_playback_fullscreen_enter),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(if (fullscreen) Res.string.remote_playback_window_fullscreen else Res.string.remote_playback_window_windowed),
                style = MaterialTheme.nuvioTypeScale.bodySm,
                color = tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = fullscreen,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textMuted,
                uncheckedTrackColor = tokens.colors.borderDefault,
            ),
        )
    }
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
        RemoteEmptyCard(RemoteIcons.Download, stringResource(Res.string.remote_downloads_title),
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
                            Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                                color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val size = task.totalBytes?.let {
                                "${formatDownloadBytes(task.downloadedBytes)} / ${formatDownloadBytes(it)}"
                            } ?: formatDownloadBytes(task.downloadedBytes)
                            Text("${task.state} · $size", style = MaterialTheme.nuvioTypeScale.bodySm.tabular,
                                color = tokens.colors.textMuted)
                        }
                        val soft = tokens.colors.textPrimary.copy(alpha = 0.07f)
                        when (task.state) {
                            DownloadStatus.Downloading -> RemoteRoundButton(
                                contentDescription = stringResource(Res.string.remote_downloads_pause),
                                onClick = { DesktopRemote.downloadCommand("downloadPause", task.id) },
                                size = 44.dp,
                                container = soft,
                            ) {
                                Icon(RemoteIcons.Pause, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(18.dp))
                            }
                            DownloadStatus.Paused -> RemoteRoundButton(
                                contentDescription = stringResource(Res.string.remote_downloads_resume),
                                onClick = { DesktopRemote.downloadCommand("downloadResume", task.id) },
                                size = 44.dp,
                                container = soft,
                            ) {
                                Icon(
                                    RemoteIcons.Play,
                                    contentDescription = null,
                                    tint = tokens.colors.textPrimary,
                                    modifier = Modifier.size(18.dp).offset(x = 1.dp),
                                )
                            }
                            else -> Unit
                        }
                    }
                    if (task.totalBytes != null && task.totalBytes > 0L) {
                        RemoteDownloadProgress(
                            progress = task.downloadedBytes.toFloat() / task.totalBytes,
                            active = task.state == DownloadStatus.Downloading,
                            color = if (task.state == DownloadStatus.Paused) tokens.colors.textMuted else tokens.colors.accent,
                            modifier = Modifier.padding(end = 4.dp),
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
