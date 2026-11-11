package com.nuvio.app.features.player

/** Short seeking retains the original ten-second behavior until customized. */
const val DEFAULT_SHORT_SEEK_SECONDS = 10
const val DEFAULT_MEDIUM_SEEK_SECONDS = 30
const val DEFAULT_LONG_SEEK_SECONDS = 60
const val MIN_SEEK_SECONDS = 1
const val MAX_SEEK_SECONDS = 120

enum class PlayerSeekStep {
    SHORT,
    MEDIUM,
    LONG,
}

fun normalizedSeekSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)

fun PlayerSettingsUiState.seekStepMs(step: PlayerSeekStep): Long {
    val seconds = when (step) {
        PlayerSeekStep.SHORT -> shortSeekSeconds
        PlayerSeekStep.MEDIUM -> mediumSeekSeconds
        PlayerSeekStep.LONG -> longSeekSeconds
    }
    return normalizedSeekSeconds(seconds).toLong() * 1_000L
}

fun PlayerSettingsUiState.seekOffsetMs(step: PlayerSeekStep, forward: Boolean): Long {
    val amountMs = seekStepMs(step)
    return if (forward) amountMs else -amountMs
}
