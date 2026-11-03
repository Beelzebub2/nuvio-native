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
    /** A supplied literal IP sends one bounded unicast probe; null broadcasts on the LAN. */
    fun discover(localIp: String? = null)
    fun localAddresses(): List<String> = emptyList()
    fun wake(address: String, mac: String) {}
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
    val remembered = MutableStateFlow<List<RememberedRemoteDevice>>(emptyList())
    val visible = MutableStateFlow(false)
    // Accessed only on the UI dispatcher, including dispatch from the transport.
    var playback = RemotePlayback()
    var dispatch: ((RemoteCommand) -> Unit)? = null
    private var activeAccount: RemoteAccount? = null

    /** Changing the account/server replaces remembered devices, never merges trust domains. */
    fun accountChanged(account: RemoteAccount?) {
        activeAccount = account
        remembered.value = account?.let(RememberedRemoteDeviceStore::read).orEmpty()
    }

    /** Called only after the encrypted session and both human approvals succeed. */
    fun pairedWith(account: RemoteAccount, desktop: RemoteDesktop, nowEpochMs: Long) {
        if (activeAccount?.userId != account.userId || activeAccount?.server != account.server) return
        val list = rememberRemoteDevice(remembered.value, desktop.name, desktop.address, nowEpochMs)
        RememberedRemoteDeviceStore.write(account, list)
        remembered.value = list
    }

    fun forget(address: String) {
        val current = activeAccount ?: return
        val next = remembered.value.filterNot { it.address == address }
        RememberedRemoteDeviceStore.write(current, next)
        remembered.value = next
    }

    fun saveWakeAddress(address: String, mac: String): Boolean {
        val current = activeAccount ?: return false
        val validated = normalizedWakeMac(mac) ?: return false
        if (remembered.value.none { it.address == address }) return false
        val next = remembered.value.map {
            if (it.address == address) it.copy(wakeMac = validated) else it
        }
        return runCatching {
            RememberedRemoteDeviceStore.write(current, next)
            remembered.value = next
        }.isSuccess
    }

    // Opening the controller never depends on a discovered PC. Users can always
    // reach its manual LAN scan and troubleshooting state from Settings.
    fun open() {
        visible.value = true
        if (!isDesktop && transport.supported && !connected.value && !scanning.value) {
            transport.discover()
        }
    }

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
            val remoteAccount = RemoteAccount(account.userId, server.backendUrl.trimEnd('/'), server.publishableKey)
            DesktopRemote.accountChanged(remoteAccount)
            DesktopRemote.transport.start(remoteAccount, isDesktop)
        } else {
            DesktopRemote.accountChanged(null)
            DesktopRemote.status.value = "Sign in to the same account and server on both devices to use Desktop remote."
        }
        onDispose {
            DesktopRemote.transport.stop()
            DesktopRemote.accountChanged(null)
        }
    }
    DesktopRemoteDialogs()
}

internal fun currentRemoteAccessToken(account: RemoteAccount): String {
    val current = AuthRepository.state.value as? AuthState.Authenticated
    check(current != null && !current.isAnonymous && current.userId == account.userId &&
        ServerConfigurationRepository.active.value.backendUrl.trimEnd('/') == account.server) { "Account changed; reconnect." }
    return checkNotNull(SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken) { "Please sign in again." }
}
