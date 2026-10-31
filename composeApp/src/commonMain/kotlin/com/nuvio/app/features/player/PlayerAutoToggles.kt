package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.AutoSkipSegmentType

/** Auto skip intro only works while segment detection (skipIntroEnabled) is on. */
internal val PlayerSettingsUiState.autoSkipIntroActive: Boolean
    get() = skipIntroEnabled && AutoSkipSegmentType.INTRO in autoSkipSegmentTypes

/**
 * Turns auto skip intro on or off from the player. Turning it on also turns on
 * segment detection, otherwise there would be no intros to skip.
 */
internal fun setAutoSkipIntroFromPlayer(enabled: Boolean) {
    if (enabled && !PlayerSettingsRepository.uiState.value.skipIntroEnabled) {
        PlayerSettingsRepository.setSkipIntroEnabled(true)
    }
    PlayerSettingsRepository.setAutoSkipSegmentTypeEnabled(AutoSkipSegmentType.INTRO, enabled)
}

internal fun setAutoNextEpisodeFromPlayer(enabled: Boolean) {
    PlayerSettingsRepository.setStreamAutoPlayNextEpisodeEnabled(enabled)
}
