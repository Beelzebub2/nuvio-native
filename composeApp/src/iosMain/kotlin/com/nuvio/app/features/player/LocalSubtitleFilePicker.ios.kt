package com.nuvio.app.features.player

internal actual suspend fun chooseLocalSubtitleFile(title: String): LocalSubtitleFileResult =
    LocalSubtitleFileResult.Cancelled
