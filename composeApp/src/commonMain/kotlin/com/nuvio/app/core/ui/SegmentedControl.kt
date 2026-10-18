package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioSegment<T>(
    val key: T,
    val label: String,
    val count: String? = null,
    val contentDescription: String? = null,
    val testTag: String? = null,
)

enum class NuvioSegmentedSize(val height: Dp, val horizontalPadding: Dp) {
    Regular(34.dp, 14.dp),
    Compact(30.dp, 12.dp),
    Dense(28.dp, 10.dp),
}

/** Single-choice control for seasons, quality filters, day windows and settings choices. */
@Composable
fun <T> NuvioSegmentedControl(
    segments: List<NuvioSegment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    size: NuvioSegmentedSize = NuvioSegmentedSize.Regular,
    onSecondaryClick: ((T) -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val containerShape = RoundedCornerShape(NuvioTokens.Radius.lg)
    Row(
        modifier = modifier
            .clip(containerShape)
            .background(colors.surface)
            .border(NuvioTokens.Border.thin, colors.borderSubtle, containerShape)
            .padding(NuvioTokens.Space.s4)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEach { segment ->
            val isSelected = segment.key == selected
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            val background by animateColorAsState(
                targetValue = when {
                    isSelected -> lerp(colors.surface, colors.textPrimary, 0.12f)
                    hovered -> lerp(colors.surface, colors.textPrimary, 0.05f)
                    else -> Color.Transparent
                },
                animationSpec = tween(NuvioTokens.Motion.fastMillis),
                label = "segment_background",
            )
            Row(
                modifier = Modifier
                    .height(size.height)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .background(background)
                    .secondaryClick(onSecondaryClick?.let { onSecondary -> { onSecondary(segment.key) } })
                    .selectable(
                        selected = isSelected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(segment.key) },
                    )
                    .then(segment.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                    .then(
                        segment.contentDescription?.let { description ->
                            Modifier.semantics { contentDescription = description }
                        } ?: Modifier,
                    )
                    .padding(horizontal = size.horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = segment.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
                segment.count?.let { count ->
                    Text(
                        text = count,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
