package com.nuvio.app.features.remote

/**
 * Platform lifecycle for an explicitly initiated, authenticated remote session.
 *
 * Android keeps a user-visible connected-device service alive while controlling
 * a PC. Windows prevents automatic system sleep only while that phone is
 * authenticated. Neither hook authorizes commands or bypasses pairing.
 */
internal interface RemoteConnectionLifetime {
    val retainWhenUiDetached: Boolean get() = false
    fun connecting() {}
    fun authenticated(host: Boolean) {}
    fun disconnected() {}
    /** Only after authenticated, profile-bound playback actions on the PC. */
    fun playbackRequested() {}
}

internal object NoopRemoteConnectionLifetime : RemoteConnectionLifetime
