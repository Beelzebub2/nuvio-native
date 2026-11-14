package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Starlit-to-Starlit pairing is distinct from vendor TV keys. The television
 * searches using its own add-ons and keeps every real stream URL locally; only
 * title IDs and short-lived source handles cross the approved relay session.
 */
internal interface StarlitTvPlaybackTransport {
    val supported: Boolean
    fun pair(code: String)
    fun disconnect()
    fun request(handoff: RemotePlaybackHandoff): Boolean
    fun select(sourceId: String)
    fun cancelSourcePicker()
    fun retrySourcePicker(): Boolean
}

internal object UnsupportedStarlitTvPlaybackTransport : StarlitTvPlaybackTransport {
    override val supported = false
    override fun pair(code: String) = Unit
    override fun disconnect() = Unit
    override fun request(handoff: RemotePlaybackHandoff) = false
    override fun select(sourceId: String) = Unit
    override fun cancelSourcePicker() = Unit
    override fun retrySourcePicker() = false
}

internal expect fun createStarlitTvPlaybackTransport(): StarlitTvPlaybackTransport

internal object StarlitTvPlayback {
    val transport by lazy { createStarlitTvPlaybackTransport() }
    val connected = MutableStateFlow(false)
    val pairing = MutableStateFlow(false)
    val name = MutableStateFlow("")
    val verification = MutableStateFlow("")
    val error = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val picker = MutableStateFlow(RemoteSourcePicker())
    val pickerVisible = MutableStateFlow(false)
    val openTvTabOnController = MutableStateFlow(false)
}

/**
 * Share "Play on…" between PC and TV without silently choosing the wrong
 * device if both sessions are connected. Selection is an explicit user action.
 */
internal object StarlitRemoteHandoff {
    val pendingDeviceChoice = MutableStateFlow<RemotePlaybackHandoff?>(null)

    fun request(handoff: RemotePlaybackHandoff): Boolean {
        if (!handoff.isAllowed()) return false
        val pc = DesktopRemote.connected.value
        val tv = StarlitTvPlayback.connected.value
        return when {
            tv && pc -> {
                pendingDeviceChoice.value = handoff
                true
            }
            tv -> StarlitTvPlayback.transport.request(handoff)
            pc -> DesktopRemote.requestHandoff(handoff)
            else -> false
        }
    }

    fun selectDevice(tv: Boolean): Boolean {
        val pending = pendingDeviceChoice.value ?: return false
        pendingDeviceChoice.value = null
        return if (tv) StarlitTvPlayback.transport.request(pending)
        else DesktopRemote.requestHandoff(pending)
    }
}
