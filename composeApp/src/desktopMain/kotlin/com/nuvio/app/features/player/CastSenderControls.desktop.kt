package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

internal actual val CastSenderSupported: Boolean = false

@Composable
internal actual fun CastSenderControls(
    sourceUrl: String,
    resumePositionMs: Long,
    onCastStarted: () -> Unit,
) = Unit
