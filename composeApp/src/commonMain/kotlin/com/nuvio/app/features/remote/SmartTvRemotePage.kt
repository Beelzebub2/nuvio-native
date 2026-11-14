package com.nuvio.app.features.remote

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
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
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The TV tab sends the TV's own remote keys, so it also navigates Starlit on Tizen/webOS. */
@Composable
internal fun SmartTvRemotePage() {
    val transport = remember { SmartTvRemote.transport }
    val active by SmartTvRemote.active.collectAsState()
    val discovered by SmartTvRemote.discovered.collectAsState()
    val saved by SmartTvRemote.saved.collectAsState()
    val scanning by SmartTvRemote.scanning.collectAsState()
    val connecting by SmartTvRemote.connecting.collectAsState()
    val status by SmartTvRemote.status.collectAsState()
    val colors = MaterialTheme.nuvio.colors

    if (!transport.supported) {
        NuvioSurfaceCard(outlined = true) {
            Text(stringResource(Res.string.remote_tv_android_only), color = colors.textSecondary)
        }
        return
    }

    LaunchedEffect(Unit) {
        if (active == null && discovered.isEmpty() && !scanning) transport.discover()
    }
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NuvioIconChip(Icons.Rounded.Tv, tone = if (active != null) NuvioIconChipTone.Success else NuvioIconChipTone.Neutral)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (active == null) Res.string.remote_tv_find_title else Res.string.remote_tv_connected_title),
                    style = MaterialTheme.typography.titleLarge, color = colors.textPrimary,
                )
                Text(
                    active?.name ?: stringResource(Res.string.remote_tv_pairing_hint),
                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                )
            }
        }
        if (status.isNotBlank()) {
            Text(status, modifier = Modifier.testTag("smart-tv-remote-status"),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        if (active != null) {
            DialogButton(
                text = stringResource(Res.string.remote_disconnect),
                onClick = transport::disconnect,
                style = DialogButtonStyle.Destructive,
                modifier = Modifier.testTag("smart-tv-disconnect"),
            )
        } else if (connecting) {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = transport::disconnect,
                modifier = Modifier.testTag("smart-tv-cancel-pairing"),
            )
        } else {
            NuvioPrimaryButton(
                text = stringResource(Res.string.remote_tv_search),
                icon = RemoteIcons.Search,
                onClick = transport::discover,
                enabled = !scanning,
                loading = scanning,
                modifier = Modifier.testTag("smart-tv-search"),
            )
        }
    }

    if (active != null) {
        SmartTvRemotePad(transport)
        return
    }
    val devices = (discovered + saved).distinctBy { it.address to it.platform }
    if (devices.isNotEmpty()) {
        NuvioSectionLabel(stringResource(Res.string.remote_tv_available).uppercase())
        NuvioGroup {
            devices.forEachIndexed { index, device ->
                if (index > 0) NuvioGroupDivider()
                NuvioGroupRow(
                    title = device.name,
                    description = device.address,
                    modifier = Modifier.testTag("smart-tv-${device.platform.name}-${device.address}"),
                    onClick = { transport.connect(device) },
                    enabled = !connecting,
                    leading = { NuvioIconChip(Icons.Rounded.Tv, tone = NuvioIconChipTone.Neutral) },
                    trailing = {
                        if (saved.any { it.address == device.address && it.platform == device.platform }) {
                            NuvioPillButton(
                                text = stringResource(Res.string.remote_pc_forget),
                                onClick = { transport.forget(device) },
                                enabled = !connecting,
                            )
                        }
                    },
                )
            }
        }
    }
    SmartTvManualConnection(transport, connecting)
}

@Composable
private fun SmartTvManualConnection(transport: SmartTvRemoteTransport, connecting: Boolean) {
    var address by rememberSaveable { mutableStateOf("") }
    var platform by rememberSaveable { mutableStateOf(SmartTvPlatform.SamsungTizen) }
    val valid = isPrivateSmartTvAddress(address.trim())
    val colors = MaterialTheme.nuvio.colors
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(Res.string.remote_tv_manual_title),
            style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Text(stringResource(Res.string.remote_tv_manual_hint),
            style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        NuvioLabeledField(
            label = stringResource(Res.string.remote_pc_manual_ip_label),
            helper = stringResource(Res.string.remote_tv_private_ip),
        ) {
            NuvioInputField(
                value = address,
                onValueChange = { address = it.take(45) },
                placeholder = "192.168.1.20",
                modifier = Modifier.testTag("smart-tv-manual-ip"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SmartTvPlatform.entries.forEach { option ->
                NuvioPillButton(
                    text = option.displayName,
                    style = if (platform == option) NuvioPillButtonStyle.Primary else NuvioPillButtonStyle.Secondary,
                    onClick = { platform = option },
                    modifier = Modifier.weight(1f).testTag("smart-tv-platform-${option.name}"),
                )
            }
        }
        DialogButton(
            text = stringResource(Res.string.remote_pc_connect),
            onClick = { transport.connect(SmartTvDevice(address.trim(), platform)) },
            enabled = valid && !connecting,
            modifier = Modifier.testTag("smart-tv-manual-connect"),
        )
    }
}

@Composable
private fun SmartTvRemotePad(transport: SmartTvRemoteTransport) {
    val colors = MaterialTheme.nuvio.colors
    val send = transport::send
    NuvioSectionLabel(stringResource(Res.string.remote_tv_navigation).uppercase())
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable
        fun button(key: SmartTvKey, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
            RemoteRoundButton(
                contentDescription = label,
                onClick = { send(key) },
                container = if (key == SmartTvKey.Ok) colors.accent else colors.surfaceCard,
                size = 56.dp,
                modifier = Modifier.testTag("smart-tv-key-${key.name}"),
            ) {
                Icon(icon, contentDescription = null,
                    tint = if (key == SmartTvKey.Ok) colors.onAccent else colors.textPrimary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            button(SmartTvKey.Home, stringResource(Res.string.remote_tv_home), Icons.Rounded.Home)
            button(SmartTvKey.Up, stringResource(Res.string.remote_tv_up), Icons.Rounded.KeyboardArrowUp)
            button(SmartTvKey.Back, stringResource(Res.string.action_back), Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            button(SmartTvKey.Left, stringResource(Res.string.remote_tv_left), Icons.Rounded.KeyboardArrowLeft)
            button(SmartTvKey.Ok, stringResource(Res.string.action_ok), Icons.Rounded.Check)
            button(SmartTvKey.Right, stringResource(Res.string.remote_tv_right), Icons.Rounded.KeyboardArrowRight)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            button(SmartTvKey.Down, stringResource(Res.string.remote_tv_down), Icons.Rounded.KeyboardArrowDown)
        }
    }
    NuvioSectionLabel(stringResource(Res.string.remote_tv_playback).uppercase())
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SmartTvAction(send, SmartTvKey.Rewind, stringResource(Res.string.remote_tv_rewind), Icons.Rounded.FastRewind)
            SmartTvAction(send, SmartTvKey.Play, stringResource(Res.string.action_play), Icons.Rounded.PlayArrow)
            SmartTvAction(send, SmartTvKey.Pause, stringResource(Res.string.remote_tv_pause), Icons.Rounded.Pause)
            SmartTvAction(send, SmartTvKey.FastForward, stringResource(Res.string.remote_tv_forward), Icons.Rounded.FastForward)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SmartTvAction(send, SmartTvKey.VolumeDown, stringResource(Res.string.remote_tv_volume_down), Icons.Rounded.VolumeDown)
            SmartTvAction(send, SmartTvKey.Mute, stringResource(Res.string.remote_tv_mute), Icons.Rounded.VolumeOff)
            SmartTvAction(send, SmartTvKey.VolumeUp, stringResource(Res.string.remote_tv_volume_up), Icons.Rounded.VolumeUp)
        }
        Text(stringResource(Res.string.remote_tv_controls_hint),
            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SmartTvAction(
    send: (SmartTvKey) -> Unit,
    key: SmartTvKey,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val colors = MaterialTheme.nuvio.colors
    RemoteRoundButton(
        contentDescription = label,
        onClick = { send(key) },
        container = colors.surfaceCard,
        size = 54.dp,
        modifier = Modifier.testTag("smart-tv-key-${key.name}"),
    ) { Icon(icon, contentDescription = null, tint = colors.textPrimary) }
}
