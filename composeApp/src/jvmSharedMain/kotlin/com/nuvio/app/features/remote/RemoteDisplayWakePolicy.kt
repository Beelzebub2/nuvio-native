package com.nuvio.app.features.remote

/**
 * Re-light the desktop monitor for intentional playback, never for routine
 * polling, navigation, volume, seeking, a cancelled source request, or a stale
 * playback generation. Authorization and same-account channel verification
 * happen before this policy is evaluated by the host transport.
 */
internal fun RemoteCommand.requestsDisplayWakeForPlayback(
    playback: RemotePlayback,
    activeProfileId: Int,
): Boolean =
    profileId == activeProfileId &&
        type in setOf("play", "next", "previous", "episode", "source") &&
        isAllowedFor(playback)
