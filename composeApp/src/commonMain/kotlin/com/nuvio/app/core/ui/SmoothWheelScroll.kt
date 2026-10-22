package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

/**
 * Desktop: eases mouse-wheel scrolling of [state] in and out the way a browser does, and blends
 * quick wheel turns into one continuous glide, instead of moving a notch at a time at constant
 * speed. Touchpads and Shift+wheel keep their usual handling. Other platforms are unchanged.
 *
 * Put it on a container of the scrolling list. It takes vertical wheel turns before the list's
 * children see them, so use it only where no vertical scroller is nested inside.
 */
internal expect fun Modifier.nuvioSmoothWheelScroll(state: ScrollableState): Modifier
