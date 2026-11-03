package com.nuvio.app.features.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.remote_pc_controller_title
import nuvio.composeapp.generated.resources.remote_pc_search_local_network
import nuvio.composeapp.generated.resources.remote_pc_no_devices_hint
import nuvio.composeapp.generated.resources.remote_pc_unsupported
import nuvio.composeapp.generated.resources.remote_pc_found_devices
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopRemoteSettingsButton() {
    if (DesktopRemote.transport.supported) {
        TextButton(onClick = { DesktopRemote.open() }) {
            Text(if (isDesktop) "Phone remote" else stringResource(Res.string.remote_pc_controller_title))
        }
    }
}

@Composable
internal fun DesktopRemoteDialogs() {
    val pairing by DesktopRemote.pairing.collectAsState()
    val visible by DesktopRemote.visible.collectAsState()
    val status by DesktopRemote.status.collectAsState()
    val connected by DesktopRemote.connected.collectAsState()
    val scanning by DesktopRemote.scanning.collectAsState()
    val desktops by DesktopRemote.desktops.collectAsState()
    val playback by DesktopRemote.remotePlayback.collectAsState()
    var confirmed by remember(pairing) { mutableStateOf(false) }
    if (pairing != null) {
        AlertDialog(
            onDismissRequest = { DesktopRemote.transport.disconnect() },
            title = { Text("Verify remote connection") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Confirm only if this code matches on both devices:")
                Text(pairing!!.code, style = MaterialTheme.typography.headlineLarge)
                Text(if (confirmed) "Waiting for the other device and account verification…" else "Connection from ${pairing!!.peer}. Codes change on every connection.")
            } },
            confirmButton = { TextButton(enabled = !confirmed, onClick = {
                confirmed = true
                DesktopRemote.transport.confirmPairing()
            }) { Text("Codes match") } },
            dismissButton = { TextButton(onClick = { DesktopRemote.transport.disconnect() }) { Text("Reject") } },
        )
    } else if (visible) {
        AlertDialog(
            onDismissRequest = { DesktopRemote.visible.value = false },
            title = {
                Text(if (isDesktop) "Phone remote" else stringResource(Res.string.remote_pc_controller_title))
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isDesktop) {
                        Text(status)
                        Text("On your Android phone open Settings → PC controller. Select this PC and compare the codes. Both devices must sign in to the same account and server.")
                    } else if (!DesktopRemote.transport.supported) {
                        Text(stringResource(Res.string.remote_pc_unsupported))
                    } else {
                        Text(status)
                        // The manual scan stays accessible with an empty list, after failed
                        // discovery, or while connected to an existing desktop.
                        OutlinedButton(
                            onClick = { DesktopRemote.transport.discover() },
                            enabled = !scanning,
                            modifier = Modifier.fillMaxWidth()
                                .testTag("desktop-remote-search-local-network"),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.remote_pc_search_local_network))
                        }
                        if (scanning) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (desktops.isNotEmpty()) {
                            Text(
                                text = stringResource(Res.string.remote_pc_found_devices),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            desktops.forEach { desktop ->
                                OutlinedButton(
                                    onClick = { DesktopRemote.transport.connect(desktop) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${desktop.name} (${desktop.address})")
                                }
                            }
                        } else if (!scanning && !connected) {
                            Text(
                                text = stringResource(Res.string.remote_pc_no_devices_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (connected) {
                            RemotePlaybackControls(playback)
                        }
                    }
                    if (connected) {
                        TextButton(
                            onClick = {
                                DesktopRemote.transport.disconnect()
                                DesktopRemote.status.value = "Disconnected. Search the local network to pair again."
                            },
                        ) { Text("Disconnect") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { DesktopRemote.visible.value = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun RemotePlaybackControls(playback: RemotePlayback) {
    Text(playback.title, style = MaterialTheme.typography.titleMedium)
    if (playback.playbackId.isEmpty()) return
    if (playback.message.isNotBlank()) Text(playback.message)
    val duration = playback.duration.coerceAtLeast(1)
    var seek by remember { mutableStateOf<Float?>(null) }
    Text("${remoteTime(playback.position)} / ${remoteTime(playback.duration)}")
    Slider(value = seek ?: (playback.position.toDouble() / duration).toFloat().coerceIn(0f, 1f), onValueChange = { seek = it },
        onValueChangeFinished = { seek?.let { DesktopRemote.command("seek", it.toDouble() * duration) }; seek = null }, enabled = playback.duration > 0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { DesktopRemote.command("seekBy", -10_000.0) }) { Text("−10s") }
        Button(onClick = { DesktopRemote.command(if (playback.playing) "pause" else "play") }) { Text(if (playback.playing) "Pause" else "Play") }
        TextButton(onClick = { DesktopRemote.command("seekBy", 10_000.0) }) { Text("+10s") }
    }
    Text("Volume ${(playback.volume * 100).toInt()}%")
    var volume by remember { mutableStateOf<Float?>(null) }
    Slider(value = volume ?: playback.volume.coerceIn(0f, 1f), onValueChange = { volume = it },
        onValueChangeFinished = { volume?.let { DesktopRemote.command("volume", it.toDouble()) }; volume = null })
    Row {
        TextButton(onClick = { DesktopRemote.command("mute") }) { Text("Mute / unmute") }
        if (playback.skip) TextButton(onClick = { DesktopRemote.command("skipInterval") }) { Text("Skip intro / credits") }
    }
    Row {
        TextButton(onClick = { DesktopRemote.command("previous") }) { Text("Previous episode") }
        TextButton(onClick = { DesktopRemote.command("next") }) { Text("Next episode") }
    }
    RemoteChoiceMenu("Episodes", playback.episodes, "episode")
    RemoteChoiceMenu("Subtitles", playback.subtitles, "subtitle")
    TextButton(onClick = { DesktopRemote.command("fetchSubtitles") }) { Text("Find addon subtitles") }
    RemoteChoiceMenu("Episode sources", playback.sources, "source")
}

@Composable
private fun RemoteChoiceMenu(title: String, items: List<RemoteChoice>, command: String) {
    var open by remember { mutableStateOf(false) }
    if (items.isEmpty()) return
    TextButton(onClick = { open = true }) { Text(title) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            items.forEach { item -> TextButton(onClick = {
                DesktopRemote.command(command, id = item.id)
                open = false
            }) { Text((if (item.selected) "✓ " else "") + item.label) } }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

private fun remoteTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
