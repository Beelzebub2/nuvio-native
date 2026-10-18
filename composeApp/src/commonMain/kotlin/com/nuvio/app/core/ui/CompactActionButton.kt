package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Emphasized/Tonal/Quiet/Danger are the original card tones. Primary (light fill), Secondary
 * (translucent fill) and Ghost (outline only) are the desktop hero and toolbar tones.
 */
enum class NuvioCompactActionTone { Emphasized, Tonal, Quiet, Danger, Primary, Secondary, Ghost }

enum class NuvioActionSize(val height: Dp, val horizontalPadding: Dp, val iconSize: Dp) {
    Large(52.dp, 20.dp, 18.dp),
    Medium(44.dp, 12.dp, 18.dp),
    Small(36.dp, 13.dp, 14.dp),
}

private data class ActionColors(val container: Color, val content: Color, val stroke: Color)

@Composable
private fun rememberActionColors(
    tone: NuvioCompactActionTone,
    enabled: Boolean,
    hovered: Boolean,
    focused: Boolean,
    pressed: Boolean,
    selected: Boolean,
): ActionColors {
    val colors = MaterialTheme.nuvio.colors
    val scheme = MaterialTheme.colorScheme
    // A toggled-on Secondary/Ghost action gets a stronger tint, never the Primary fill, so the
    // play action stays the only solid button in a row.
    val isSelectedToggle = selected && tone in setOf(NuvioCompactActionTone.Secondary, NuvioCompactActionTone.Ghost)
    val effectiveTone = tone
    val baseContainer = when {
        isSelectedToggle -> colors.textPrimary.copy(alpha = 0.20f)
        else -> when (effectiveTone) {
            NuvioCompactActionTone.Emphasized -> colors.accent
            NuvioCompactActionTone.Tonal -> colors.surfaceCard
            NuvioCompactActionTone.Quiet -> colors.surface
            NuvioCompactActionTone.Danger -> colors.danger.copy(alpha = 0.12f)
            NuvioCompactActionTone.Primary -> scheme.onBackground
            NuvioCompactActionTone.Secondary -> colors.textPrimary.copy(alpha = 0.08f)
            NuvioCompactActionTone.Ghost -> Color.Transparent
        }
    }
    val foreground = when {
        !enabled -> colors.textDisabled
        isSelectedToggle -> colors.textPrimary
        effectiveTone == NuvioCompactActionTone.Emphasized -> colors.onAccent
        effectiveTone == NuvioCompactActionTone.Primary -> scheme.background
        effectiveTone == NuvioCompactActionTone.Danger -> colors.danger
        effectiveTone == NuvioCompactActionTone.Quiet -> colors.textSecondary
        effectiveTone == NuvioCompactActionTone.Ghost -> colors.textSecondary
        else -> colors.textPrimary
    }
    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceCard.copy(alpha = 0.50f)
            effectiveTone == NuvioCompactActionTone.Emphasized && (hovered || focused || pressed) -> colors.accentStrong
            effectiveTone == NuvioCompactActionTone.Primary && pressed -> lerp(baseContainer, scheme.background, 0.16f)
            effectiveTone == NuvioCompactActionTone.Primary && (hovered || focused) -> lerp(baseContainer, scheme.background, 0.08f)
            pressed -> lerp(baseContainer, colors.textPrimary, 0.16f).copy(alpha = maxOf(baseContainer.alpha, 0.16f))
            hovered || focused -> lerp(baseContainer, colors.textPrimary, 0.09f).copy(alpha = maxOf(baseContainer.alpha, 0.10f))
            else -> baseContainer
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "compact_action_background",
    )
    val stroke by animateColorAsState(
        targetValue = when {
            !enabled -> colors.borderSubtle
            focused -> colors.focusRing
            effectiveTone == NuvioCompactActionTone.Emphasized -> colors.accent
            isSelectedToggle -> colors.textPrimary.copy(alpha = if (hovered) 0.40f else 0.30f)
            effectiveTone == NuvioCompactActionTone.Primary -> Color.Transparent
            effectiveTone == NuvioCompactActionTone.Danger -> colors.danger.copy(alpha = if (hovered) 0.55f else 0.30f)
            effectiveTone == NuvioCompactActionTone.Secondary -> colors.textPrimary.copy(alpha = if (hovered) 0.18f else 0.10f)
            effectiveTone == NuvioCompactActionTone.Ghost -> colors.textPrimary.copy(alpha = if (hovered) 0.24f else 0.14f)
            hovered -> colors.borderStrong
            else -> colors.borderDefault
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "compact_action_border",
    )
    return ActionColors(container, foreground, stroke)
}

@Composable
private fun actionLabelStyle(size: NuvioActionSize, tone: NuvioCompactActionTone): TextStyle {
    val base = when (size) {
        NuvioActionSize.Large -> MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)
        NuvioActionSize.Medium -> MaterialTheme.typography.labelLarge
        NuvioActionSize.Small -> MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)
    }
    return if (tone == NuvioCompactActionTone.Primary) base.copy(fontWeight = FontWeight.SemiBold) else base
}

/** Compact, keyboard-accessible action for secondary controls and media cards. */
@Composable
fun NuvioCompactActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: NuvioCompactActionTone = NuvioCompactActionTone.Tonal,
    trailingIcon: ImageVector? = null,
    accessibilityLabel: String? = null,
    size: NuvioActionSize = NuvioActionSize.Medium,
    selected: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val actionColors = rememberActionColors(tone, enabled, hovered, focused, pressed, selected)
    Surface(
        onClick = onClick,
        modifier = modifier.height(size.height).semantics {
            role = Role.Button
            accessibilityLabel?.let { contentDescription = it }
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
        color = actionColors.container,
        contentColor = actionColors.content,
        border = BorderStroke(if (enabled && focused) 2.dp else 1.dp, actionColors.stroke),
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (icon != null) size.horizontalPadding - 2.dp else size.horizontalPadding,
                end = size.horizontalPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (size == NuvioActionSize.Small) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(size.iconSize)) }
            Text(
                label,
                modifier = Modifier.weight(1f, fill = false),
                style = actionLabelStyle(size, tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Circular icon-only action. [contentDescription] is required because there is no visible label. */
@Composable
fun NuvioIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: NuvioCompactActionTone = NuvioCompactActionTone.Secondary,
    size: NuvioActionSize = NuvioActionSize.Medium,
    selected: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val actionColors = rememberActionColors(tone, enabled, hovered, focused, pressed, selected)
    Surface(
        onClick = onClick,
        modifier = modifier.size(size.height).semantics {
            role = Role.Button
            this.contentDescription = contentDescription
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = actionColors.container,
        contentColor = actionColors.content,
        border = BorderStroke(if (enabled && focused) 2.dp else 1.dp, actionColors.stroke),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (size == NuvioActionSize.Large) 20.dp else size.iconSize + 2.dp),
            )
        }
    }
}
