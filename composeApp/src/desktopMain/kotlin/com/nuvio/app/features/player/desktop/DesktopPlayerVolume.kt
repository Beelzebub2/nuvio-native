package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.remote.REMOTE_MAX_VOLUME_LEVEL

internal const val DESKTOP_PLAYER_MAX_VOLUME_LEVEL = REMOTE_MAX_VOLUME_LEVEL

internal fun Float.coerceDesktopPlayerVolumeLevel(): Float =
    coerceIn(0f, DESKTOP_PLAYER_MAX_VOLUME_LEVEL)

internal fun resolveDesktopPlayerVolumeLevel(
    requestedLevel: Float?,
    currentLevel: Float?,
    rememberedLevel: Float,
): Float =
    (requestedLevel ?: currentLevel ?: rememberedLevel).coerceDesktopPlayerVolumeLevel()
