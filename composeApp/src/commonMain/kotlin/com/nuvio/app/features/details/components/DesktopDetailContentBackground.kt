package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DesktopDetailContentBackground(
    backgroundColor: Color,
    contentStartOffset: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val contentStart = contentStartOffset()
                val fadeHeight = (size.height * 0.14f).coerceIn(88.dp.toPx(), 144.dp.toPx())
                val opaqueTransitionStart = size.height * 0.45f
                val opaqueTransitionEnd = size.height * 0.25f
                val viewportOpacity = ((opaqueTransitionStart - contentStart) /
                    (opaqueTransitionStart - opaqueTransitionEnd)).coerceIn(0f, 1f)
                if (viewportOpacity > 0f) {
                    drawRect(backgroundColor.copy(alpha = viewportOpacity))
                }
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.3f to backgroundColor.copy(alpha = 0.18f),
                            0.65f to backgroundColor.copy(alpha = 0.72f),
                            1f to backgroundColor,
                        ),
                        startY = contentStart,
                        endY = contentStart + fadeHeight,
                    ),
                )
            },
    )
}
