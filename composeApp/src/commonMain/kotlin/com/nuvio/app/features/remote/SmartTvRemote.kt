package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/** A TV's built-in, on-screen-approved LAN remote is separate from the PC remote session. */
@Serializable
internal enum class SmartTvPlatform(val displayName: String) {
    SamsungTizen("Samsung Tizen"),
    LgWebOs("LG webOS"),
}

internal data class SmartTvDevice(
    val address: String,
    val platform: SmartTvPlatform,
    val name: String = "${platform.displayName} · $address",
)

internal enum class SmartTvKey(
    val samsung: String?,
    val lgPointer: String? = null,
    val lgSsap: String? = null,
) {
    Up("KEY_UP", "UP"),
    Down("KEY_DOWN", "DOWN"),
    Left("KEY_LEFT", "LEFT"),
    Right("KEY_RIGHT", "RIGHT"),
    Ok("KEY_ENTER", "ENTER"),
    Back("KEY_RETURN", "BACK"),
    Home("KEY_HOME", "HOME"),
    VolumeUp("KEY_VOLUP", lgSsap = "ssap://audio/volumeUp"),
    VolumeDown("KEY_VOLDOWN", lgSsap = "ssap://audio/volumeDown"),
    Mute("KEY_MUTE", lgSsap = "ssap://audio/setMute"),
    Play("KEY_PLAY", lgSsap = "ssap://media.controls/play"),
    Pause("KEY_PAUSE", lgSsap = "ssap://media.controls/pause"),
    Rewind("KEY_REWIND", lgSsap = "ssap://media.controls/rewind"),
    FastForward("KEY_FF", lgSsap = "ssap://media.controls/fastForward"),
}

/** Only numeric, private LAN addresses are accepted; no DNS, public IP or URL is a TV target. */
internal fun isPrivateSmartTvAddress(value: String): Boolean {
    val octets = value.split('.')
    if (octets.size != 4) return false
    val numbers = octets.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
            (part.length > 1 && part.startsWith('0'))) null else part.toIntOrNull()?.takeIf { it in 0..255 }
    }
    if (numbers.any { it == null }) return false
    val (first, second) = numbers.map { it!! }
    return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
}

internal interface SmartTvRemoteTransport {
    val supported: Boolean
    fun discover()
    fun connect(device: SmartTvDevice)
    fun forget(device: SmartTvDevice)
    fun disconnect()
    fun send(key: SmartTvKey)
}

internal object UnsupportedSmartTvRemoteTransport : SmartTvRemoteTransport {
    override val supported = false
    override fun discover() = Unit
    override fun connect(device: SmartTvDevice) = Unit
    override fun forget(device: SmartTvDevice) = Unit
    override fun disconnect() = Unit
    override fun send(key: SmartTvKey) = Unit
}

internal expect fun createSmartTvRemoteTransport(): SmartTvRemoteTransport

internal object SmartTvRemote {
    val transport by lazy { createSmartTvRemoteTransport() }
    val discovered = MutableStateFlow<List<SmartTvDevice>>(emptyList())
    val saved = MutableStateFlow<List<SmartTvDevice>>(emptyList())
    val active = MutableStateFlow<SmartTvDevice?>(null)
    val scanning = MutableStateFlow(false)
    val connecting = MutableStateFlow(false)
    val status = MutableStateFlow("")
}
