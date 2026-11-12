package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app slider: a 4 dp track with an accent fill over a white 30 % rail and a 14 dp light thumb.
 * It keeps Material's slider semantics and gestures and only replaces the drawing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    touchHeight: Dp = 32.dp,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val disabledAlpha = MaterialTheme.nuvio.opacity.disabled
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(touchHeight),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(
                Modifier
                    .size(14.dp)
                    .alpha(if (enabled) 1f else disabledAlpha)
                    .clip(CircleShape)
                    .background(colors.textPrimary),
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s4)
                    .clip(CircleShape)
                    .background(colors.playerTimelineTrack),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .alpha(if (enabled) 1f else disabledAlpha)
                        .background(colors.accent),
                )
            }
        },
    )
}
