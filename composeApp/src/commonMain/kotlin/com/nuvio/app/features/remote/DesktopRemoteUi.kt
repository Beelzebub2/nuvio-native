package com.nuvio.app.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isDesktop
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

/**
 * Everything the remote shows outside its Settings entry: the controller window,
 * the pairing code check and desktop playback approval.
 */
@Composable
internal fun DesktopRemoteDialogs(
    localAddresses: () -> List<String> = { DesktopRemote.transport.localAddresses() },
    // The desktop hosts the QR code; phones show the controller. Tests can pick either.
    desktopLayout: Boolean = isDesktop,
    firewall: RemoteFirewallAccess = DesktopRemote.firewall,
    // Tests draw the window's content in place instead of opening a real window.
    inline: Boolean = false,
) {
    val pairing by DesktopRemote.pairing.collectAsState()
    val pendingHandoff by DesktopRemote.pendingHandoff.collectAsState()
    val visible by DesktopRemote.visible.collectAsState()
    val hostReady by DesktopRemote.hostReady.collectAsState()
    val sourcePickerOpen by DesktopRemote.sourcePickerVisible.collectAsState()
    val requestedSourceId by DesktopRemote.pendingPhoneSourceRequestId.collectAsState()
    val remotePlayback by DesktopRemote.remotePlayback.collectAsState()
    val remoteConnected by DesktopRemote.connected.collectAsState()
    val activeDesktop by DesktopRemote.activeDesktop.collectAsState()
    val tvPickerOpen by StarlitTvPlayback.pickerVisible.collectAsState()
    val tvPicker by StarlitTvPlayback.picker.collectAsState()
    val tvConnected by StarlitTvPlayback.connected.collectAsState()
    val tvName by StarlitTvPlayback.name.collectAsState()
    val targetChoice by StarlitRemoteHandoff.pendingDeviceChoice.collectAsState()
    var confirmed by remember(pairing) { mutableStateOf(false) }
    val confirm = {
        confirmed = true
        DesktopRemote.transport.confirmPairing()
    }
    val reject = { DesktopRemote.transport.disconnect() }
    val close = { DesktopRemote.visible.value = false }
    // Reopening after Wi-Fi/DHCP changes refreshes both the QR and its manual-IP fallback.
    val hostAddresses = remember(visible, hostReady) {
        if (desktopLayout && visible) localAddresses() else emptyList()
    }

    if (visible) {
        val content: @Composable () -> Unit = {
            if (desktopLayout) {
                PhoneRemoteScreen(
                    addresses = hostAddresses,
                    firewall = firewall,
                    pairing = pairing,
                    pairingConfirmed = confirmed,
                    onConfirmPairing = confirm,
                    onRejectPairing = reject,
                    onClose = close,
                )
            } else {
                PcControllerScreen(onClose = close)
                // Drawn from inside the page so it always sits on top of it.
                pairing?.let { RemotePairingDialog(it, confirmed, confirm, reject) }
            }
        }
        if (inline) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.nuvio.colors.background)
                    .testTag("desktop-remote-window"),
            ) { content() }
        } else {
            RemoteControllerWindow(
                title = stringResource(if (desktopLayout) Res.string.remote_qr_phone_remote else Res.string.remote_pc_controller_title),
                onClose = close,
                content = content,
            )
        }
    } else {
        pairing?.let { RemotePairingDialog(it, confirmed, confirm, reject) }
    }

    if (desktopLayout) {
        // A paired phone cannot navigate or start playback without this PC's
        // explicit approval. The actual source is resolved on the desktop.
        val handoff = pendingHandoff
        if (pairing == null && handoff != null) {
            RemoteHandoffDialog(
                title = handoff.title,
                onAccept = { DesktopRemote.acceptPendingHandoff() },
                onReject = { DesktopRemote.pendingHandoff.value = null },
            )
        }
    } else if (tvPickerOpen) {
        RemoteSourcePickerDialog(
            requestId = tvPicker.requestId,
            state = tvPicker,
            connected = tvConnected,
            pcName = tvName,
            onDismiss = { StarlitTvPlayback.transport.cancelSourcePicker() },
            onRetry = { StarlitTvPlayback.transport.retrySourcePicker() },
            onSelectSource = { StarlitTvPlayback.transport.select(it) },
            isAwaitingSources = {
                StarlitTvPlayback.picker.value.isAwaitingSources(tvPicker.requestId)
            },
            onOpenController = {
                StarlitTvPlayback.pickerVisible.value = false
                StarlitTvPlayback.openTvTabOnController.value = true
                DesktopRemote.open()
            },
        )
    } else if (sourcePickerOpen) {
        RemoteSourcePickerDialog(
            requestId = requestedSourceId,
            state = remotePlayback.sourcePicker,
            connected = remoteConnected,
            pcName = activeDesktop?.name.orEmpty(),
            onDismiss = { DesktopRemote.dismissPhoneSourcePicker() },
            onRetry = { DesktopRemote.retryPhoneSourceRequest() },
            onOpenController = {
                DesktopRemote.dismissPhoneSourcePicker()
                DesktopRemote.open()
            },
        )
    }
    if (!desktopLayout && targetChoice != null) {
        AlertDialog(
            onDismissRequest = { StarlitRemoteHandoff.pendingDeviceChoice.value = null },
            title = { Text(stringResource(Res.string.remote_handoff_choose_target)) },
            confirmButton = {
                TextButton(onClick = { StarlitRemoteHandoff.selectDevice(true) }) {
                    Text(tvName.ifBlank { stringResource(Res.string.remote_tv_tab) })
                }
            },
            dismissButton = {
                TextButton(onClick = { StarlitRemoteHandoff.selectDevice(false) }) {
                    Text(activeDesktop?.name?.ifBlank { "PC" } ?: "PC")
                }
            },
        )
    }
}
