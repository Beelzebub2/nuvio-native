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
internal data class RemoteChoice(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    // Optional source-only metadata. Other remote choices and older peers keep their defaults.
    val quality: String? = null,
    val qualityStars: Int? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val cached: Boolean = false,
    val recommended: Boolean = false,
    val recommendationReason: String? = null,
    // Descriptive metadata only; optional to remain compatible with older desktop/phone peers.
    val provider: String? = null,
    val release: String? = null,
    val codec: String? = null,
    val audio: String? = null,
    val hdr: Boolean = false,
    val sizeBytes: Long? = null,
)

/** Track IDs are scoped to the current playback and include the player's logical track index. */
internal fun remoteAudioTrackId(index: Int, id: String): String = "$index:${id.take(64)}"

/** Source descriptions only. Media URLs, headers, torrent hashes and credentials stay on the PC. */
@Serializable
internal data class RemoteSourcePicker(
    val requestId: String = "",
    val title: String = "",
    val phase: String = "idle",
    val sources: List<RemoteChoice> = emptyList(),
    val message: String = "",
)

internal const val REMOTE_SOURCE_CAPABILITY = "source-picker-v2"
/** Desktop player volume is normalized to 0..2 (0..200%). Old PCs advertise 1. */
internal const val REMOTE_MAX_VOLUME_LEVEL = 2f

@Serializable
internal data class RemotePlayback(
    val playbackId: String = "",
    val title: String = "Desktop is not playing",
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val volume: Float = 1f,
    /** Defaults to 100% so a newer phone never sends unsupported boost commands to an older PC. */
    val volumeMax: Float = 1f,
    val skip: Boolean = false,
    val skipIntro: Boolean = false,
    val nextEpisodeAvailable: Boolean = false,
    val fullscreenSupported: Boolean = false,
    val fullscreen: Boolean = false,
    val episodes: List<RemoteChoice> = emptyList(),
    val subtitles: List<RemoteChoice> = emptyList(),
    val audioTracks: List<RemoteChoice> = emptyList(),
    val sources: List<RemoteChoice> = emptyList(),
    val message: String = "",
    val downloadProfileId: Int? = null,
    val downloads: List<RemoteDownloadStatus> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val sourcePicker: RemoteSourcePicker = RemoteSourcePicker(),
)

internal fun RemotePlayback.effectiveVolumeMax(): Float =
    volumeMax.takeIf { it.isFinite() }?.coerceIn(1f, REMOTE_MAX_VOLUME_LEVEL) ?: 1f

@Serializable
internal data class RemoteCommand(
    val type: String = "state",
    val value: Double = 0.0,
    val id: String = "",
    val playbackId: String = "",
    val profileId: Int? = null,
    val handoff: RemotePlaybackHandoff? = null,
    val requestId: String = "",
)

internal data class RemoteDesktop(
    val address: String,
    val port: Int,
    val name: String,
    val bluetooth: Boolean = false,
)

/** A connected LAN endpoint and a Bluetooth endpoint are distinct, even if their PC names match. */
internal fun isActiveRemoteDesktop(
    connected: Boolean,
    active: RemoteDesktop?,
    address: String,
    bluetooth: Boolean,
): Boolean = connected && active != null && active.bluetooth == bluetooth &&
    active.address.equals(address, ignoreCase = bluetooth)

internal data class RemotePairing(val code: String, val peer: String)
internal data class RemoteAccount(val userId: String, val server: String, val publishableKey: String)

/** Desktop-window actions are independent of whether a video is currently open. */
internal interface RemoteFullscreenActions {
    fun isFullscreen(): Boolean
    fun toggle()
}

internal interface DesktopRemoteTransport {
    val supported: Boolean
    val bluetoothSupported: Boolean get() = false
    val retainWhenUiDetached: Boolean get() = false
    fun start(account: RemoteAccount, host: Boolean)
    fun stop()
    /**
     * A literal IP is checked directly; an explicit deep scan also probes the local subnet.
     * With [connect], a PC found at [localIp] is connected to straight away (a scanned QR code).
     */
    fun discover(localIp: String? = null, deepScan: Boolean = false, connect: Boolean = false)
    fun discoverBluetooth() {}
    fun localAddresses(): List<String> = emptyList()
    fun wake(address: String, mac: String) {}
    fun connect(desktop: RemoteDesktop)
    fun confirmPairing()
    fun disconnect()
    fun send(command: RemoteCommand)
    fun forgetBluetoothTrust(account: RemoteAccount, address: String): Boolean = false
    fun forgetLanTrust(account: RemoteAccount, desktopId: String): Boolean = false
}

internal expect fun createDesktopRemoteTransport(): DesktopRemoteTransport

internal object DesktopRemote {
    val transport by lazy { createDesktopRemoteTransport() }
    val firewall by lazy { createRemoteFirewallAccess() }
    val desktops = MutableStateFlow<List<RemoteDesktop>>(emptyList())
    val bluetoothDesktops = MutableStateFlow<List<RemoteDesktop>>(emptyList())
    val pairing = MutableStateFlow<RemotePairing?>(null)
    val connected = MutableStateFlow(false)
    /** The verified PC endpoint for the current phone-side session, not merely the last saved PC. */
    val activeDesktop = MutableStateFlow<RemoteDesktop?>(null)
    /** A network or Bluetooth search is running. */
    val scanning = MutableStateFlow(false)
    /** The phone is opening a connection, until the code check appears or it connects. */
    val connecting = MutableStateFlow(false)
    val hostReady = MutableStateFlow(false)
    val hostFailure = MutableStateFlow(false)
    /** True only if UDP discovery is listening; QR/manual-IP still work without it. */
    val discoveryReady = MutableStateFlow(false)
    val bluetoothHostReady = MutableStateFlow(false)
    val status = MutableStateFlow("Connect to a desktop on the same Wi-Fi or Ethernet network.")
    val remotePlayback = MutableStateFlow(RemotePlayback())
    val remembered = MutableStateFlow<List<RememberedRemoteDevice>>(emptyList())
    val visible = MutableStateFlow(false)
    val pendingHandoff = MutableStateFlow<RemotePlaybackHandoff?>(null)
    val sourcePicker = MutableStateFlow(RemoteSourcePicker())
    val sourcePickerVisible = MutableStateFlow(false)
    val pendingPhoneSourceRequestId = MutableStateFlow("")
    val sourceRequestId = MutableStateFlow("")
    var sourceRequestPreviousPlaybackId = ""
    /** Installed only while a desktop stream picker belongs to the active remote request. */
    var selectRemoteSource: ((String, String?) -> Boolean)? = null
    // Accessed only on the UI dispatcher, including dispatch from the transport.
    var playback = RemotePlayback()
    var dispatch: ((RemoteCommand) -> Unit)? = null
    var fullscreenActions: RemoteFullscreenActions? = null
    private var activeAccount: RemoteAccount? = null
    // Only the active desktop content navigator can accept a pending request.
    var onAcceptedHandoff: ((RemotePlaybackHandoff, String?) -> Unit)? = null
    var handoffAccount: RemoteAccount? = null

    /** Changing the account/server replaces remembered devices, never merges trust domains. */
    fun accountChanged(account: RemoteAccount?) {
        activeAccount = account
        remembered.value = account?.let(RememberedRemoteDeviceStore::read).orEmpty()
    }

    /** Called only after encrypted, account-verified pairing or a previously approved secure resume. */
    fun pairedWith(
        account: RemoteAccount, desktop: RemoteDesktop, nowEpochMs: Long, trustedId: String? = null,
    ) {
        if (activeAccount?.userId != account.userId || activeAccount?.server != account.server) return
        val list = rememberRemoteDevice(
            remembered.value, desktop.name, desktop.address, nowEpochMs, desktop.bluetooth, trustedId,
        )
        RememberedRemoteDeviceStore.write(account, list)
        remembered.value = list
    }

    fun forget(address: String) {
        val current = activeAccount ?: return
        val isCurrentPc = remembered.value.any { device ->
            device.address == address && isActiveRemoteDesktop(
                connected.value, activeDesktop.value, device.address, device.bluetooth,
            )
        }
        if (remembered.value.any { it.address == address && it.bluetooth }) {
            if (!transport.forgetBluetoothTrust(current, address)) {
                status.value = "Could not forget the Bluetooth PC securely. Please try again."
                return
            }
        }
        // Forget must also erase the encrypted LAN credential, never just the visible row.
        val lanIds = remembered.value.filter { it.address == address && !it.bluetooth }
            .mapNotNull { it.trustedId }.distinct()
        if (lanIds.any { !transport.forgetLanTrust(current, it) }) {
            status.value = "Could not forget this Wi-Fi PC securely. Please try again."
            return
        }
        val next = remembered.value.filterNot { it.address == address }
        RememberedRemoteDeviceStore.write(current, next)
        remembered.value = next
        // Forgetting a trusted PC must also revoke its *active* remote session.
        if (isCurrentPc) {
            transport.disconnect()
            status.value = "Disconnected. Search the local network to pair again."
        }
    }

    fun saveWakeAddress(address: String, mac: String): Boolean {
        val current = activeAccount ?: return false
        val validated = normalizedWakeMac(mac) ?: return false
        if (remembered.value.none { it.address == address && !it.bluetooth }) return false
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
        if (!isDesktop && transport.supported && !connected.value && !scanning.value && !connecting.value) {
            val lastBluetooth = preferredBluetoothReconnect(remembered.value, transport.bluetoothSupported)
            if (lastBluetooth != null) {
                transport.connect(RemoteDesktop(lastBluetooth.address, 0, lastBluetooth.name, bluetooth = true))
            } else {
                transport.discover(deepScan = true)
            }
        }
    }

    fun requestHandoff(handoff: RemotePlaybackHandoff): Boolean {
        if (isDesktop || !transport.supported || !connected.value ||
            handoff.profileId != com.nuvio.app.features.profiles.ProfileRepository.activeProfileId) return false
        val safe = handoff.copy(
            title = handoff.title.take(160).map { if (it < ' ' || it == '\u007f') ' ' else it }
                .joinToString("").trim(),
        )
        if (!safe.isAllowed()) return false
        if (REMOTE_SOURCE_CAPABILITY !in remotePlayback.value.capabilities) {
            status.value = "Update Starlit on your PC to choose a source from the phone."
            return false
        }
        val requestId = newRemotePlaybackId()
        pendingPhoneSourceRequestId.value = requestId
        sourcePickerVisible.value = true
        transport.send(RemoteCommand(
            type = "handoffRequest",
            playbackId = remotePlayback.value.playbackId,
            profileId = safe.profileId,
            handoff = safe,
            requestId = requestId,
        ))
        return true
    }

    /** Enter the source-picker flow only after the encrypted, account-verified session has authorized the command. */
    fun beginSourceRequest(requestId: String, handoff: RemotePlaybackHandoff): Boolean {
        if (!isDesktop || !connected.value || onAcceptedHandoff == null ||
            !isValidRemoteRequestId(requestId)) return false
        val account = handoffAccount ?: return false
        if (com.nuvio.app.features.profiles.ProfileRepository.activeProfileId != handoff.profileId ||
            !handoff.isAllowed() || runCatching { currentRemoteAccessToken(account) }.isFailure) return false
        sourceRequestId.value = requestId
        sourceRequestPreviousPlaybackId = playback.playbackId
        sourcePicker.value = RemoteSourcePicker(requestId, handoff.title, "searching")
        selectRemoteSource = null
        onAcceptedHandoff?.invoke(handoff, requestId)
        return true
    }

    fun selectPhoneSource(sourceId: String) {
        val requestId = pendingPhoneSourceRequestId.value
        val sourceState = remotePlayback.value.sourcePicker
        if (!connected.value || sourceState.requestId != requestId ||
            sourceState.phase !in setOf("ready", "searching") ||
            sourceState.sources.none { it.id == sourceId }) return
        transport.send(RemoteCommand(
            type = "handoffSource", requestId = requestId, id = sourceId,
            playbackId = remotePlayback.value.playbackId,
            profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
        ))
    }

    fun dismissPhoneSourcePicker() {
        val requestId = pendingPhoneSourceRequestId.value
        val state = remotePlayback.value.sourcePicker
        // Even before the PC acknowledges a request, cancellation must be queued
        // after the request, or closing the phone sheet can strand the PC picker.
        if (connected.value && isValidRemoteRequestId(requestId) &&
            (state.requestId != requestId || state.phase in setOf("searching", "ready", "empty", "error"))) {
            transport.send(RemoteCommand(
                type = "handoffCancel", requestId = requestId,
                playbackId = remotePlayback.value.playbackId,
                profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
            ))
        }
        sourcePickerVisible.value = false
        pendingPhoneSourceRequestId.value = ""
    }

    fun acceptPendingHandoff() {
        val request = pendingHandoff.value ?: return
        pendingHandoff.value = null
        val account = handoffAccount ?: return
        if (!isDesktop || !connected.value ||
            com.nuvio.app.features.profiles.ProfileRepository.activeProfileId != request.profileId ||
            !request.isAllowed()) return
        if (runCatching { currentRemoteAccessToken(account) }.isFailure) return
        onAcceptedHandoff?.invoke(request, null)
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
    val signedIn = (auth as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }
    val remoteAccount = if (signedIn != null && server.backendUrl.startsWith("https://")) {
        RemoteAccount(signedIn.userId, server.backendUrl.trimEnd('/'), server.publishableKey)
    } else null
    // Refreshing the login session must not tear down a previously paired Bluetooth socket.
    DisposableEffect(remoteAccount?.userId, remoteAccount?.server, remoteAccount?.publishableKey) {
        if (remoteAccount != null) {
            DesktopRemote.accountChanged(remoteAccount)
            DesktopRemote.handoffAccount = remoteAccount
            DesktopRemote.transport.start(remoteAccount, isDesktop)
        } else {
            DesktopRemote.accountChanged(null)
            DesktopRemote.handoffAccount = null
            DesktopRemote.pendingHandoff.value = null
            DesktopRemote.status.value = "Sign in to the same account and server on both devices to use Desktop remote."
        }
        onDispose {
            // A phone's connected-device foreground service owns the transport
            // when its Activity is temporarily removed. Never retain it if the
            // account or server changed, or after an explicit disconnect.
            val signedInNow = AuthRepository.state.value as? AuthState.Authenticated
            val retain = !isDesktop && remoteAccount != null &&
                signedInNow != null && !signedInNow.isAnonymous &&
                signedInNow.userId == remoteAccount.userId &&
                ServerConfigurationRepository.active.value.backendUrl.trimEnd('/') == remoteAccount.server &&
                DesktopRemote.transport.retainWhenUiDetached &&
                (DesktopRemote.connected.value || DesktopRemote.connecting.value ||
                    DesktopRemote.pairing.value != null)
            if (!retain) {
                DesktopRemote.transport.stop()
                DesktopRemote.accountChanged(null)
                DesktopRemote.handoffAccount = null
                DesktopRemote.pendingHandoff.value = null
            }
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
