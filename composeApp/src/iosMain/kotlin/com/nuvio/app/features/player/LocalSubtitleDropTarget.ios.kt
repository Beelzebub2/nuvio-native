package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.localSubtitleDropTarget(
    enabled: Boolean,
    onResult: (LocalSubtitleFileResult) -> Unit,
): Modifier = this
