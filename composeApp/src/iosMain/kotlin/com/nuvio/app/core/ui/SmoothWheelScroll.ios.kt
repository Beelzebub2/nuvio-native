package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

internal actual fun Modifier.nuvioSmoothWheelScroll(state: ScrollableState): Modifier = this
