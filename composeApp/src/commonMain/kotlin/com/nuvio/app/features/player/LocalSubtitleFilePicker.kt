package com.nuvio.app.features.player

/** The chosen local path is intentionally never stored or included in account sync. */
sealed interface LocalSubtitleFileResult {
    data class Selected(val uri: String, val displayName: String) : LocalSubtitleFileResult
    data class Rejected(val reason: LocalSubtitleFileError) : LocalSubtitleFileResult
    data object Cancelled : LocalSubtitleFileResult
}

enum class LocalSubtitleFileError {
    UNSUPPORTED_FORMAT,
    UNREADABLE,
    TOO_LARGE,
    MULTIPLE_FILES,
    PICKER_FAILED,
}

/** Only the basename and the current playback identity are retained in UI state. */
internal data class SessionLocalSubtitleSelection(
    val playbackKey: PlaybackKey,
    val displayName: String,
)

internal fun SessionLocalSubtitleSelection?.visibleNameFor(
    playbackKey: PlaybackKey,
    useCustomSubtitles: Boolean,
    selectedAddonSubtitleId: String?,
): String? = this?.takeIf {
    useCustomSubtitles && selectedAddonSubtitleId == null && it.playbackKey == playbackKey
}?.displayName

/** Only the desktop UI offers this action; other platforms return Cancelled. */
internal expect suspend fun chooseLocalSubtitleFile(title: String): LocalSubtitleFileResult

/** Desktop accepts one local subtitle file dropped on the active player; mobile is a no-op. */
@androidx.compose.runtime.Composable
internal expect fun androidx.compose.ui.Modifier.localSubtitleDropTarget(
    enabled: Boolean,
    onResult: (LocalSubtitleFileResult) -> Unit,
): androidx.compose.ui.Modifier
