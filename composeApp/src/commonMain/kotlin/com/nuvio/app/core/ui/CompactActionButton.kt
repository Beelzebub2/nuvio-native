package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class NuvioCompactActionTone { Emphasized, Tonal, Quiet, Danger }

/** Compact, keyboard-accessible action for secondary controls and media cards. */
@Composable
fun NuvioCompactActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: NuvioCompactActionTone = NuvioCompactActionTone.Tonal,
    trailingIcon: ImageVector? = null,
    accessibilityLabel: String? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val baseContainer = when (tone) {
        NuvioCompactActionTone.Emphasized -> colors.accent
        NuvioCompactActionTone.Tonal -> colors.surfaceCard
        NuvioCompactActionTone.Quiet -> colors.surface
        NuvioCompactActionTone.Danger -> colors.danger.copy(alpha = 0.12f)
    }
    val foreground = when {
        !enabled -> colors.textDisabled
        tone == NuvioCompactActionTone.Emphasized -> colors.onAccent
        tone == NuvioCompactActionTone.Danger -> colors.danger
        tone == NuvioCompactActionTone.Quiet -> colors.textSecondary
        else -> colors.textPrimary
    }
    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceCard.copy(alpha = 0.50f)
            tone == NuvioCompactActionTone.Emphasized && (hovered || focused || pressed) -> colors.accentStrong
            pressed -> lerp(baseContainer, colors.textPrimary, 0.16f)
            hovered || focused -> lerp(baseContainer, colors.textPrimary, 0.09f)
            else -> baseContainer
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "compact_action_background",
    )
    val stroke by animateColorAsState(
        targetValue = when {
            !enabled -> colors.borderSubtle
            focused -> colors.focusRing
            tone == NuvioCompactActionTone.Emphasized -> colors.accent
            tone == NuvioCompactActionTone.Danger -> colors.danger.copy(alpha = if (hovered) 0.55f else 0.30f)
            hovered -> colors.borderStrong
            else -> colors.borderDefault
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "compact_action_border",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp).semantics {
            role = Role.Button
            accessibilityLabel?.let { contentDescription = it }
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
        color = container,
        contentColor = foreground,
        border = BorderStroke(if (enabled && focused) 2.dp else 1.dp, stroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        }
    }
}
