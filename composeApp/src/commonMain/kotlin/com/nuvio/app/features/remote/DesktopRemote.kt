package com.nuvio.app.features.remote

import androidx.compose.runtime.*
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.isDesktop
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun newRemotePlaybackId(): String = Uuid.random().toString()

@Serializable
internal data class RemoteChoice(val id: String, val label: String, val selected: Boolean = false)

@Serializable
internal data class RemotePlayback(
    val playbackId: String = "",
    val title: String = "Desktop is not playing",
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val volume: Float = 1f,
    val skip: Boolean = false,
    val episodes: List<RemoteChoice> = emptyList(),
    val subtitles: List<RemoteChoice> = emptyList(),
    val sources: List<RemoteChoice> = emptyList(),
    val message: String = "",
    val downloadProfileId: Int? = null,
    val downloads: List<RemoteDownloadStatus> = emptyList(),
)

@Serializable
internal data class RemoteCommand(
    val type: String = "state",
    val value: Double = 0.0,
    val id: String = "",
    val playbackId: String = "",
    val profileId: Int? = null,
)

internal data class RemoteDesktop(val address: String, val port: Int, val name: String)
internal data class RemotePairing(val code: String, val peer: String)
internal data class RemoteAccount(val userId: String, val server: String, val publishableKey: String)

internal interface DesktopRemoteTransport {
    val supported: Boolean
    fun start(account: RemoteAccount, host: Boolean)
    fun stop()
    fun discover()
    fun connect(desktop: RemoteDesktop)
    fun confirmPairing()
    fun disconnect()
    fun send(command: RemoteCommand)
}

internal expect fun createDesktopRemoteTransport(): DesktopRemoteTransport

internal object DesktopRemote {
    val transport by lazy { createDesktopRemoteTransport() }
    val desktops = MutableStateFlow<List<RemoteDesktop>>(emptyList())
    val pairing = MutableStateFlow<RemotePairing?>(null)
    val connected = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    val status = MutableStateFlow("Connect to a desktop on the same Wi-Fi or Ethernet network.")
    val remotePlayback = MutableStateFlow(RemotePlayback())
    val visible = MutableStateFlow(false)
    // Accessed only on the UI dispatcher, including dispatch from the transport.
    var playback = RemotePlayback()
    var dispatch: ((RemoteCommand) -> Unit)? = null

    fun command(type: String, value: Double = 0.0, id: String = "") {
        transport.send(RemoteCommand(
            type = type, value = value, id = id,
            playbackId = remotePlayback.value.playbackId,
            profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
        ))
    }

    fun downloadCommand(type: String, downloadId: String) {
        if (type != "downloadPause" && type != "downloadResume") return
        transport.send(RemoteCommand(
            type = type,
            id = downloadId,
            playbackId = remotePlayback.value.playbackId,
            profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
        ))
    }
}

@Composable
internal fun DesktopRemoteHost() {
    val auth by AuthRepository.state.collectAsState()
    val server by ServerConfigurationRepository.active.collectAsState()
    DisposableEffect(auth, server.backendUrl, server.publishableKey) {
        val account = auth as? AuthState.Authenticated
        if (account != null && !account.isAnonymous && server.backendUrl.startsWith("https://")) {
            DesktopRemote.transport.start(RemoteAccount(account.userId, server.backendUrl.trimEnd('/'), server.publishableKey), isDesktop)
        } else {
            DesktopRemote.status.value = "Sign in to the same account and server on both devices to use Desktop remote."
        }
        onDispose { DesktopRemote.transport.stop() }
    }
    DesktopRemoteDialogs()
}

internal fun currentRemoteAccessToken(account: RemoteAccount): String {
    val current = AuthRepository.state.value as? AuthState.Authenticated
    check(current != null && !current.isAnonymous && current.userId == account.userId &&
        ServerConfigurationRepository.active.value.backendUrl.trimEnd('/') == account.server) { "Account changed; reconnect." }
    return checkNotNull(SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken) { "Please sign in again." }
}
