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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.nuvio.app.core.ui.NuvioToastController
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
    val consent = remember(manager, sourceUrl) { CastLoadConsent(sourceUrl) }
    var remotePlaying by remember(manager) { mutableStateOf(false) }
    var hasRemoteMedia by remember(manager) { mutableStateOf(false) }
    val currentOnCastStarted by rememberUpdatedState(onCastStarted)
    val failedLabel = stringResource(Res.string.player_cast_sender_load_failed)
    val startedLabel = stringResource(Res.string.player_cast_sender_started)
    val chooseReceiverLabel = stringResource(Res.string.player_cast_sender_connect)
    val mimeType = eligibleCastMimeType(sourceUrl)

    // Scope listeners and pending requests to this source/panel. The SDK owns
    // background reconnection; it must not automatically load the local source.
    DisposableEffect(manager, consent) {
        var observedClient: RemoteMediaClient? = null
        val mediaCallback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                remotePlaying = observedClient?.isPlaying == true
                hasRemoteMedia = observedClient?.hasMediaSession() == true
            }
        }
        fun refreshSession() {
            val session = manager?.currentCastSession?.takeIf { it.isConnected }
            consent.updateReceiver(session.connectedReceiverId())
            val client = session?.remoteMediaClient
            if (observedClient !== client) {
                observedClient?.unregisterCallback(mediaCallback)
                observedClient = client
                client?.registerCallback(mediaCallback)
            }
            mediaCallback.onStatusUpdated()
        }
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) {
                consent.updateReceiver(null)
            }
            override fun onSessionStarted(session: CastSession, sessionId: String) = refreshSession()
            override fun onSessionStartFailed(session: CastSession, error: Int) = refreshSession()
            override fun onSessionEnding(session: CastSession) {
                consent.updateReceiver(null)
            }
            override fun onSessionEnded(session: CastSession, error: Int) = refreshSession()
            override fun onSessionResuming(session: CastSession, sessionId: String) {
                consent.updateReceiver(null)
            }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = refreshSession()
            override fun onSessionResumeFailed(session: CastSession, error: Int) = refreshSession()
            override fun onSessionSuspended(session: CastSession, reason: Int) {
                consent.updateReceiver(null)
            }
        }
        manager?.addSessionManagerListener(listener, CastSession::class.java)
        refreshSession()
        onDispose {
            consent.dispose()
            observedClient?.unregisterCallback(mediaCallback)
            manager?.removeSessionManagerListener(listener, CastSession::class.java)
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
                        contentDescription = chooseReceiverLabel
                    }
                },
            )
            Text(stringResource(if (consent.receiverId != null) Res.string.player_cast_sender_connected
                else Res.string.player_cast_sender_connect))
        }
        if (mimeType != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = consent.rightsConfirmed,
                enabled = consent.receiverId != null && !consent.loading,
                onCheckedChange = { consent.rightsConfirmed = it },
            )
            Text(stringResource(Res.string.player_cast_sender_rights))
        }
        if (consent.receiverId != null) {
            if (mimeType != null) Button(
                enabled = consent.rightsConfirmed && !consent.loading,
                onClick = {
                    val session = manager.currentCastSession?.takeIf { it.isConnected }
                    val client = session?.remoteMediaClient
                    val requestId = consent.beginLoad(session.connectedReceiverId())
                    if (client == null || requestId == null) {
                        if (requestId != null) consent.completeLoad(requestId, null)
                        NuvioToastController.show(failedLabel)
                    } else {
                        // No title, URL query, tracking token or source headers
                        // are copied into Cast metadata or diagnostics.
                        runCatching {
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
                                val currentSession = manager.currentCastSession?.takeIf { it.isConnected }
                                if (!consent.completeLoad(requestId, currentSession.connectedReceiverId())) {
                                    return@setResultCallback
                                }
                                if (result.status.isSuccess) {
                                    currentOnCastStarted()
                                    NuvioToastController.show(startedLabel)
                                } else {
                                    NuvioToastController.show(failedLabel)
                                }
                            }
                        }.onFailure {
                            if (consent.completeLoad(requestId, manager.currentCastSession.connectedReceiverId())) {
                                NuvioToastController.show(failedLabel)
                            }
                        }
                    }
                },
            ) { Text(stringResource(Res.string.player_cast_sender_play)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = hasRemoteMedia && !consent.loading,
                    onClick = {
                        runCatching {
                            manager.currentCastSession?.takeIf { it.isConnected }?.remoteMediaClient?.let { client ->
                                val result = if (client.isPlaying) client.pause() else client.play()
                                result.setResultCallback {
                                    if (!it.status.isSuccess) NuvioToastController.show(failedLabel)
                                }
                            }
                        }.onFailure { NuvioToastController.show(failedLabel) }
                    },
                ) {
                    Text(stringResource(if (remotePlaying) Res.string.player_cast_sender_pause
                        else Res.string.player_cast_sender_resume))
                }
                OutlinedButton(onClick = {
                    consent.updateReceiver(null)
                    runCatching { manager.endCurrentSession(true) }
                        .onFailure { NuvioToastController.show(failedLabel) }
                }) {
                    Text(stringResource(Res.string.player_cast_sender_stop))
                }
            }
        }
    }
}

private fun CastSession?.connectedReceiverId(): String? {
    val session = this?.takeIf { it.isConnected } ?: return null
    val deviceId = session.castDevice?.deviceId ?: return null
    val sessionId = session.sessionId ?: return null
    return "$deviceId:$sessionId"
}
