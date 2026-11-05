package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal actual val CastSenderSupported: Boolean = true

/**
 * A deliberately narrow Cast sender: no player relays, custom headers,
 * DRM credentials, local network endpoints, P2P engines or source metadata.
 * The media URL is transmitted only after a connected route AND an explicit
 * playback action by the user, with an additional permission acknowledgement.
 */
@Composable
internal actual fun CastSenderControls(
    sourceUrl: String,
    resumePositionMs: Long,
    onCastStarted: () -> Unit,
) {
    val context = LocalContext.current
    val manager = remember(context) {
        runCatching { CastContext.getSharedInstance(context).sessionManager }.getOrNull()
    }
    var rightsConfirmed by remember(sourceUrl) { mutableStateOf(false) }
    var connected by remember(manager) { mutableStateOf(false) }
    var remotePlaying by remember(manager) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val failedLabel = stringResource(Res.string.player_cast_sender_load_failed)
    val startedLabel = stringResource(Res.string.player_cast_sender_started)

    // Only active while the user has the Cast panel open; the Cast SDK owns
    // background session reconnection and discovery independently of this UI.
    LaunchedEffect(manager) {
        while (true) {
            val session = manager?.currentCastSession?.takeIf { it.isConnected }
            connected = session != null
            remotePlaying = session?.remoteMediaClient?.isPlaying == true
            delay(900L)
        }
    }

    if (manager == null) {
        Text(stringResource(Res.string.player_cast_sender_unavailable))
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AndroidView(
                modifier = Modifier.size(48.dp),
                factory = { viewContext ->
                    MediaRouteButton(viewContext).apply {
                        CastButtonFactory.setUpMediaRouteButton(viewContext, this)
                        contentDescription = "Choose a Cast receiver"
                    }
                },
            )
            Text(stringResource(if (connected) Res.string.player_cast_sender_connected
                else Res.string.player_cast_sender_connect))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = rightsConfirmed, onCheckedChange = { rightsConfirmed = it })
            Text(stringResource(Res.string.player_cast_sender_rights))
        }
        if (connected) {
            val mimeType = eligibleCastMimeType(sourceUrl)
            Button(
                enabled = rightsConfirmed && mimeType != null && !sending,
                onClick = {
                    val client = manager.currentCastSession?.takeIf { it.isConnected }?.remoteMediaClient
                    if (client == null || mimeType == null ||
                        eligibleCastMimeType(sourceUrl) != mimeType) {
                        NuvioToastController.show(failedLabel)
                    } else {
                        sending = true
                        // No title, URL query, tracking token or source headers
                        // are copied into Cast metadata or diagnostics.
                        val mediaInfo = MediaInfo.Builder(sourceUrl)
                            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setContentType(mimeType)
                            .build()
                        val request = MediaLoadRequestData.Builder()
                            .setMediaInfo(mediaInfo)
                            .setAutoplay(true)
                            .setCurrentTime(resumePositionMs.coerceIn(0L, 172_800_000L))
                            .build()
                        client.load(request).setResultCallback { result ->
                            sending = false
                            if (result.status.isSuccess) {
                                onCastStarted()
                                NuvioToastController.show(startedLabel)
                            } else {
                                NuvioToastController.show(failedLabel)
                            }
                        }
                    }
                },
            ) { Text(stringResource(Res.string.player_cast_sender_play)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    manager.currentCastSession?.remoteMediaClient?.let { client ->
                        if (remotePlaying) client.pause() else client.play()
                    }
                }) {
                    Text(stringResource(if (remotePlaying) Res.string.player_cast_sender_pause
                        else Res.string.player_cast_sender_resume))
                }
                OutlinedButton(onClick = { manager.endCurrentSession(true) }) {
                    Text(stringResource(Res.string.player_cast_sender_stop))
                }
            }
        }
    }
}
