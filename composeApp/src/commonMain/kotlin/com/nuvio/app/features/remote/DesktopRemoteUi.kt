package com.nuvio.app.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSectionLabel
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isDesktop
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.formatDownloadBytes
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopRemoteSettingsButton() {
    if (DesktopRemote.transport.supported) {
        TextButton(onClick = { DesktopRemote.open() }) {
            Text(stringResource(if (isDesktop) Res.string.remote_qr_phone_remote else Res.string.remote_pc_controller_title))
        }
    }
}

@Composable
internal fun DesktopRemoteDialogs(
    localAddresses: () -> List<String> = { DesktopRemote.transport.localAddresses() },
    // The desktop host shows its QR code; phones show the controller. Tests can pick either.
    desktopLayout: Boolean = isDesktop,
) {
    val pairing by DesktopRemote.pairing.collectAsState()
    val pendingHandoff by DesktopRemote.pendingHandoff.collectAsState()
    val visible by DesktopRemote.visible.collectAsState()
    val status by DesktopRemote.status.collectAsState()
    val connected by DesktopRemote.connected.collectAsState()
    val scanning by DesktopRemote.scanning.collectAsState()
    val desktops by DesktopRemote.desktops.collectAsState()
    val bluetoothDesktops by DesktopRemote.bluetoothDesktops.collectAsState()
    val savedPcs by DesktopRemote.remembered.collectAsState()
    val playback by DesktopRemote.remotePlayback.collectAsState()
    val hostReady by DesktopRemote.hostReady.collectAsState()
    val bluetoothHostReady by DesktopRemote.bluetoothHostReady.collectAsState()
    // Reopening after Wi-Fi/DHCP changes refreshes both the QR and its manual-IP fallback.
    val hostAddresses = remember(visible, hostReady) {
        if (desktopLayout && visible) localAddresses() else emptyList()
    }
    var manualIp by rememberSaveable { mutableStateOf("") }
    var qrError by remember { mutableStateOf(false) }
    val qrScanner = rememberRemoteQrScanner { payload ->
        val address = payload?.let(::parseRemotePairingQr)
        qrError = address == null
        if (address != null) {
            manualIp = address
            DesktopRemote.transport.discover(address)
        }
    }
    var editingWakeFor by remember { mutableStateOf<String?>(null) }
    var wakeMacDraft by remember { mutableStateOf("") }
    var wakeMacError by remember { mutableStateOf(false) }
    var confirmed by remember(pairing) { mutableStateOf(false) }
    val disconnectedStatus = stringResource(Res.string.remote_disconnected_status)
    val currentPairing = pairing
    val currentHandoff = pendingHandoff
    if (currentPairing != null) {
        RemotePairingDialog(
            pairing = currentPairing,
            confirmed = confirmed,
            onConfirm = {
                confirmed = true
                DesktopRemote.transport.confirmPairing()
            },
            onReject = { DesktopRemote.transport.disconnect() },
        )
    } else if (desktopLayout && currentHandoff != null) {
        // A paired phone cannot navigate or start playback without this PC's
        // explicit approval. The actual source is resolved on the desktop.
        RemoteHandoffDialog(
            title = currentHandoff.title,
            onAccept = { DesktopRemote.acceptPendingHandoff() },
            onReject = { DesktopRemote.pendingHandoff.value = null },
        )
    } else if (visible) {
        DialogSurface(
            onDismissRequest = { DesktopRemote.visible.value = false },
            title = stringResource(if (desktopLayout) Res.string.remote_qr_phone_remote else Res.string.remote_pc_controller_title),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (desktopLayout) {
                    val noNetwork = hostReady && hostAddresses.isEmpty()
                    RemoteStatusPanel(
                        text = if (noNetwork) stringResource(Res.string.remote_qr_no_network) else status,
                        connected = connected,
                    )
                    if (hostReady && hostAddresses.isNotEmpty()) {
                        DialogHint(stringResource(Res.string.remote_qr_instructions))
                        DesktopPairingQr(hostAddresses)
                    }
                    hostAddresses.takeIf { it.isNotEmpty() }?.let { addresses ->
                        DialogHint(
                            stringResource(Res.string.remote_pc_desktop_addresses, addresses.joinToString(", ")),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (hostReady) {
                        DialogHint(
                            if (bluetoothHostReady) stringResource(Res.string.remote_bluetooth_host_ready)
                            else stringResource(Res.string.remote_bluetooth_host_unavailable),
                        )
                    }
                } else if (!DesktopRemote.transport.supported) {
                    RemoteStatusPanel(stringResource(Res.string.remote_pc_unsupported), connected = false)
                } else {
                    RemoteStatusPanel(status, connected = connected, busy = scanning)
                    if (qrScanner != null) {
                        NuvioCompactActionButton(
                            label = stringResource(Res.string.remote_qr_scan),
                            icon = Icons.Rounded.QrCodeScanner,
                            tone = NuvioCompactActionTone.Primary,
                            enabled = !scanning,
                            modifier = Modifier.fillMaxWidth().testTag("desktop-remote-scan-qr"),
                            onClick = { qrError = false; qrScanner() },
                        )
                    }
                    if (qrError) DialogHint(stringResource(Res.string.remote_qr_scan_failed), isError = true)
                    // The manual scan stays accessible with an empty list, after failed
                    // discovery, or while connected to an existing desktop.
                    NuvioCompactActionButton(
                        label = stringResource(Res.string.remote_pc_search_local_network),
                        icon = Icons.Rounded.Refresh,
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth().testTag("desktop-remote-search-local-network"),
                        onClick = { DesktopRemote.transport.discover(deepScan = true) },
                    )
                    RemoteBluetoothSearchButton(enabled = true)
                    if (scanning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = MaterialTheme.nuvio.colors.accent,
                            trackColor = MaterialTheme.nuvio.colors.accent.copy(alpha = 0.12f),
                        )
                    }
                    if (desktops.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogSectionLabel(stringResource(Res.string.remote_pc_found_devices))
                            desktops.forEach { desktop ->
                                RemoteDeviceRow(
                                    name = desktop.name,
                                    address = desktop.address,
                                    onClick = { DesktopRemote.transport.connect(desktop) },
                                )
                            }
                        }
                    } else if (!scanning && !connected && bluetoothDesktops.isEmpty()) {
                        DialogHint(stringResource(Res.string.remote_pc_no_devices_hint))
                    }
                    if (bluetoothDesktops.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogSectionLabel(stringResource(Res.string.remote_bluetooth_found_devices))
                            bluetoothDesktops.forEach { desktop ->
                                RemoteDeviceRow(
                                    name = desktop.name,
                                    address = stringResource(Res.string.remote_bluetooth_device, desktop.address),
                                    onClick = { DesktopRemote.transport.connect(desktop) },
                                )
                            }
                        }
                    }
                    if (connected) {
                        RemotePlaybackControls(playback)
                        RemoteDownloadControls(playback)
                    }
                    if (savedPcs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogSectionLabel(stringResource(Res.string.remote_pc_saved_devices))
                            DialogHint(stringResource(Res.string.remote_pc_saved_notice))
                            savedPcs.forEach { saved ->
                                SavedPcCard(
                                    saved = saved,
                                    enabled = !scanning,
                                    editingWake = editingWakeFor == saved.address,
                                    wakeMacDraft = wakeMacDraft,
                                    wakeMacError = wakeMacError,
                                    onConnect = { DesktopRemote.transport.discover(saved.address) },
                                    onForget = { DesktopRemote.forget(saved.address) },
                                    onWake = { mac -> DesktopRemote.transport.wake(saved.address, mac) },
                                    onToggleWakeSettings = {
                                        editingWakeFor = if (editingWakeFor == saved.address) null else saved.address
                                        wakeMacDraft = saved.wakeMac.orEmpty()
                                        wakeMacError = false
                                    },
                                    onWakeMacChange = { wakeMacDraft = it.take(20); wakeMacError = false },
                                    onSaveWakeMac = {
                                        if (DesktopRemote.saveWakeAddress(saved.address, wakeMacDraft)) {
                                            editingWakeFor = null
                                            wakeMacError = false
                                        } else wakeMacError = true
                                    },
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogSectionLabel(stringResource(Res.string.remote_pc_manual_section))
                        DialogHint(stringResource(Res.string.remote_pc_manual_ip_hint))
                        DialogTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it.take(45) },
                            label = stringResource(Res.string.remote_pc_manual_ip_label),
                            modifier = Modifier.testTag("desktop-remote-manual-ip"),
                        )
                        NuvioCompactActionButton(
                            label = stringResource(Res.string.remote_pc_check_ip),
                            icon = Icons.Rounded.Search,
                            size = NuvioActionSize.Small,
                            enabled = !scanning && manualIp.isNotBlank(),
                            onClick = { DesktopRemote.transport.discover(manualIp.trim()) },
                        )
                    }
                }
            }
            DialogButtons {
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
                    onClick = { DesktopRemote.visible.value = false },
                )
            }
        }
    }
}

@Composable
private fun RemotePairingDialog(
    pairing: RemotePairing,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onReject,
        title = stringResource(Res.string.remote_verify_title),
        message = stringResource(Res.string.remote_verify_prompt),
    ) {
        // The code is the whole point of this dialog, so it gets a large, spaced-out panel.
        Text(
            text = pairing.code,
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected))
                .padding(vertical = 18.dp),
            style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 6.sp, fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (confirmed) NuvioLoadingIndicator(Modifier.size(16.dp), color = tokens.colors.accent)
            DialogHint(
                if (confirmed) stringResource(Res.string.remote_verify_waiting)
                else stringResource(Res.string.remote_verify_peer, pairing.peer),
            )
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.remote_handoff_reject), onClick = onReject)
            DialogButton(
                text = stringResource(Res.string.remote_verify_confirm),
                onClick = onConfirm,
                style = DialogButtonStyle.Primary,
                enabled = !confirmed,
            )
        }
    }
}

@Composable
private fun RemoteHandoffDialog(title: String, onAccept: () -> Unit, onReject: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onReject,
        title = stringResource(Res.string.remote_handoff_approve_title),
        message = stringResource(Res.string.remote_handoff_approve_description),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.remote_handoff_reject), onClick = onReject)
            DialogButton(
                text = stringResource(Res.string.remote_handoff_approve),
                onClick = onAccept,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

/** The live connection status, with a check when connected and a spinner while searching. */
@Composable
private fun RemoteStatusPanel(text: String, connected: Boolean, busy: Boolean = false) {
    if (text.isBlank()) return
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(
                if (connected) tokens.colors.accent.copy(alpha = tokens.opacity.selected)
                else tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle),
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            busy -> NuvioLoadingIndicator(Modifier.size(20.dp), color = tokens.colors.accent)
            else -> Icon(
                if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.Wifi,
                contentDescription = null,
                tint = if (connected) tokens.colors.accent else tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
    }
}

@Composable
private fun RemoteDeviceRow(name: String, address: String, onClick: () -> Unit, enabled: Boolean = true) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Computer, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(address, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
        }
        Icon(Icons.Rounded.Link, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedPcCard(
    saved: RememberedRemoteDevice,
    enabled: Boolean,
    editingWake: Boolean,
    wakeMacDraft: String,
    wakeMacError: Boolean,
    onConnect: () -> Unit,
    onForget: () -> Unit,
    onWake: (String) -> Unit,
    onToggleWakeSettings: () -> Unit,
    onWakeMacChange: (String) -> Unit,
    onSaveWakeMac: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteDeviceRow(name = saved.name, address = saved.address, onClick = onConnect, enabled = enabled)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            saved.wakeMac?.let { mac ->
                NuvioCompactActionButton(
                    label = stringResource(Res.string.remote_pc_wake),
                    icon = Icons.Rounded.PowerSettingsNew,
                    size = NuvioActionSize.Small,
                    onClick = { onWake(mac) },
                )
            }
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_pc_set_wake_mac),
                icon = null,
                size = NuvioActionSize.Small,
                selected = editingWake,
                onClick = onToggleWakeSettings,
            )
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_pc_forget),
                icon = null,
                size = NuvioActionSize.Small,
                tone = NuvioCompactActionTone.Danger,
                onClick = onForget,
            )
        }
        if (editingWake) {
            DialogHint(stringResource(Res.string.remote_pc_mac_help))
            DialogTextField(
                value = wakeMacDraft,
                onValueChange = onWakeMacChange,
                label = stringResource(Res.string.remote_pc_mac_label),
                isError = wakeMacError,
            )
            if (wakeMacError) DialogHint(stringResource(Res.string.remote_pc_invalid_mac), isError = true)
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_pc_save_mac),
                icon = null,
                size = NuvioActionSize.Small,
                tone = NuvioCompactActionTone.Primary,
                onClick = onSaveWakeMac,
            )
        }
    }
}

@Composable
private fun RemoteDownloadControls(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DialogSectionLabel(stringResource(Res.string.remote_downloads_title))
        if (playback.downloadProfileId == null ||
            playback.downloadProfileId != ProfileRepository.activeProfileId) {
            DialogHint(stringResource(Res.string.remote_downloads_profile_mismatch))
            return@Column
        }
        if (playback.downloads.isEmpty()) {
            DialogHint(stringResource(Res.string.remote_downloads_empty))
            return@Column
        }
        playback.downloads.forEach { task ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(tokens.shapes.compactCard)
                    .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val size = task.totalBytes?.let {
                    "${formatDownloadBytes(task.downloadedBytes)} / ${formatDownloadBytes(it)}"
                } ?: formatDownloadBytes(task.downloadedBytes)
                Text("${task.state} · $size", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                if (task.totalBytes != null && task.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = tokens.colors.accent,
                        trackColor = tokens.colors.textPrimary.copy(alpha = 0.08f),
                    )
                }
                when (task.state) {
                    DownloadStatus.Downloading -> NuvioCompactActionButton(
                        label = stringResource(Res.string.remote_downloads_pause),
                        icon = Icons.Rounded.Pause,
                        size = NuvioActionSize.Small,
                        onClick = { DesktopRemote.downloadCommand("downloadPause", task.id) },
                    )
                    DownloadStatus.Paused -> NuvioCompactActionButton(
                        label = stringResource(Res.string.remote_downloads_resume),
                        icon = Icons.Rounded.PlayArrow,
                        size = NuvioActionSize.Small,
                        onClick = { DesktopRemote.downloadCommand("downloadResume", task.id) },
                    )
                    else -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemotePlaybackControls(playback: RemotePlayback) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(playback.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (playback.playbackId.isEmpty()) return@Column
        if (playback.message.isNotBlank()) DialogHint(playback.message)
        val duration = playback.duration.coerceAtLeast(1)
        var seek by remember { mutableStateOf<Float?>(null) }
        val sliderColors = SliderDefaults.colors(
            thumbColor = tokens.colors.accent,
            activeTrackColor = tokens.colors.accent,
            inactiveTrackColor = tokens.colors.textPrimary.copy(alpha = 0.14f),
        )
        Column {
            Slider(
                value = seek ?: (playback.position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
                onValueChange = { seek = it },
                onValueChangeFinished = { seek?.let { DesktopRemote.command("seek", it.toDouble() * duration) }; seek = null },
                enabled = playback.duration > 0,
                colors = sliderColors,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(remoteTime(seek?.let { (it * duration).toLong() } ?: playback.position),
                    style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                Text(remoteTime(playback.duration), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            }
        }
        // Transport: previous, back 10s, play/pause, forward 10s, next.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RemoteIconButton(Icons.Rounded.SkipPrevious, stringResource(Res.string.remote_playback_previous)) { DesktopRemote.command("previous") }
            RemoteIconButton(Icons.Rounded.Replay10, stringResource(Res.string.remote_playback_rewind)) { DesktopRemote.command("seekBy", -10_000.0) }
            val playLabel = stringResource(if (playback.playing) Res.string.remote_playback_pause else Res.string.remote_playback_play)
            FilledIconButton(
                onClick = { DesktopRemote.command(if (playback.playing) "pause" else "play") },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = playLabel, modifier = Modifier.size(30.dp))
            }
            RemoteIconButton(Icons.Rounded.Forward10, stringResource(Res.string.remote_playback_forward)) { DesktopRemote.command("seekBy", 10_000.0) }
            RemoteIconButton(Icons.Rounded.SkipNext, stringResource(Res.string.remote_playback_next)) { DesktopRemote.command("next") }
        }
        var volume by remember { mutableStateOf<Float?>(null) }
        val shownVolume = volume ?: playback.volume.coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RemoteIconButton(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(Res.string.remote_playback_mute)) { DesktopRemote.command("mute") }
            Slider(
                value = shownVolume,
                onValueChange = { volume = it },
                onValueChangeFinished = { volume?.let { DesktopRemote.command("volume", it.toDouble()) }; volume = null },
                colors = sliderColors,
                modifier = Modifier.weight(1f),
            )
            Text("${(shownVolume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted,
                modifier = Modifier.widthIn(min = 40.dp), textAlign = TextAlign.End)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playback.skip) {
                NuvioCompactActionButton(
                    label = stringResource(Res.string.remote_playback_skip),
                    icon = Icons.Rounded.SkipNext,
                    size = NuvioActionSize.Small,
                    tone = NuvioCompactActionTone.Primary,
                    onClick = { DesktopRemote.command("skipInterval") },
                )
            }
            RemoteChoiceMenu(stringResource(Res.string.remote_playback_episodes), Icons.Rounded.VideoLibrary, playback.episodes, "episode")
            RemoteChoiceMenu(stringResource(Res.string.remote_playback_subtitles), Icons.Rounded.ClosedCaption, playback.subtitles, "subtitle")
            NuvioCompactActionButton(
                label = stringResource(Res.string.remote_playback_find_subtitles),
                icon = Icons.Rounded.Subtitles,
                size = NuvioActionSize.Small,
                onClick = { DesktopRemote.command("fetchSubtitles") },
            )
            RemoteChoiceMenu(stringResource(Res.string.remote_playback_sources), Icons.Rounded.Source, playback.sources, "source")
        }
    }
}

@Composable
private fun RemoteIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.nuvio.colors.textPrimary)
    }
}

@Composable
private fun RemoteChoiceMenu(title: String, icon: ImageVector, items: List<RemoteChoice>, command: String) {
    var open by remember { mutableStateOf(false) }
    if (items.isEmpty()) return
    NuvioCompactActionButton(label = title, icon = icon, size = NuvioActionSize.Small, onClick = { open = true })
    if (open) {
        DialogSurface(onDismissRequest = { open = false }, title = title) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    DialogOption(
                        text = item.label,
                        selected = item.selected,
                        onClick = {
                            DesktopRemote.command(command, id = item.id)
                            open = false
                        },
                    )
                }
            }
            DialogButtons {
                DialogButton(text = stringResource(Res.string.action_close), onClick = { open = false })
            }
        }
    }
}

private fun remoteTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
