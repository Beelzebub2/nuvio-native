package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

@Immutable
sealed interface NuvioContextMenuEntry {
    data class Action(
        val label: String,
        val onClick: () -> Unit,
        val icon: ImageVector? = null,
        val shortcut: String? = null,
        val enabled: Boolean = true,
    ) : NuvioContextMenuEntry

    data object Divider : NuvioContextMenuEntry
}

/**
 * Desktop context menu opened at the pointer (right-click) or under a ⋯ button.
 * [anchorInRoot] is the pointer or anchor position in root coordinates.
 */
@Composable
fun NuvioContextMenu(
    anchorInRoot: Offset,
    entries: List<NuvioContextMenuEntry>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerCode: String? = null,
    headerTitle: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val colors = tokens.colors
    val edgeMarginPx = with(LocalDensity.current) { NuvioTokens.Space.s8.roundToPx() }
    val menuShape = RoundedCornerShape(NuvioTokens.Radius.xl)
    val firstItemFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val firstEnabledIndex = entries.indexOfFirst { it is NuvioContextMenuEntry.Action && it.enabled }

    Popup(
        popupPositionProvider = remember(anchorInRoot, edgeMarginPx) {
            NuvioPointerPopupPositionProvider(
                pointerPosition = IntOffset(anchorInRoot.x.roundToInt(), anchorInRoot.y.roundToInt()),
                edgeMarginPx = edgeMarginPx,
            )
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(Unit) {
            if (firstEnabledIndex >= 0) runCatching { firstItemFocus.requestFocus() }
        }
        Column(
            modifier = modifier
                .width(300.dp)
                .shadow(elevation = tokens.elevation.overlay, shape = menuShape)
                .clip(menuShape)
                .background(colors.surfacePopover)
                .border(tokens.borders.thin, colors.borderDefault, menuShape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .padding(NuvioTokens.Space.s6),
        ) {
            if (headerCode != null || headerTitle != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = NuvioTokens.Space.s10,
                            end = NuvioTokens.Space.s10,
                            top = NuvioTokens.Space.s8,
                            bottom = NuvioTokens.Space.s10,
                        ),
                ) {
                    headerCode?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    headerTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                NuvioContextMenuDivider()
            }
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    NuvioContextMenuEntry.Divider -> NuvioContextMenuDivider()
                    is NuvioContextMenuEntry.Action -> NuvioContextMenuRow(
                        entry = entry,
                        focusRequester = if (index == firstEnabledIndex) firstItemFocus else null,
                        onSelected = {
                            onDismiss()
                            entry.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NuvioContextMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s4)
            .height(NuvioTokens.Border.thin)
            .background(MaterialTheme.nuvio.colors.borderSubtle),
    )
}

@Composable
private fun NuvioContextMenuRow(
    entry: NuvioContextMenuEntry.Action,
    focusRequester: FocusRequester?,
    onSelected: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(if (entry.enabled && (hovered || focused)) colors.overlayHover else Color.Transparent)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(
                enabled = entry.enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onSelected,
            )
            .focusable(enabled = entry.enabled, interactionSource = interactionSource)
            .padding(horizontal = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        if (entry.icon != null) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = if (entry.enabled) colors.textSecondary else colors.textDisabled,
                modifier = Modifier.size(NuvioTokens.Icon.sm),
            )
        }
        Text(
            text = entry.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.enabled) colors.textPrimary else colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        entry.shortcut?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** Places a popup at the pointer, flipping back from the far window edge. */
internal class NuvioPointerPopupPositionProvider(
    private val pointerPosition: IntOffset,
    private val edgeMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = positionContextMenuAxis(
            pointer = pointerPosition.x,
            popupExtent = popupContentSize.width,
            windowExtent = windowSize.width,
            edgeMargin = edgeMarginPx,
        ),
        y = positionContextMenuAxis(
            pointer = pointerPosition.y,
            popupExtent = popupContentSize.height,
            windowExtent = windowSize.height,
            edgeMargin = edgeMarginPx,
        ),
    )
}

internal fun positionContextMenuAxis(
    pointer: Int,
    popupExtent: Int,
    windowExtent: Int,
    edgeMargin: Int,
): Int {
    val minimum = edgeMargin
    val maximum = (windowExtent - popupExtent - edgeMargin).coerceAtLeast(minimum)
    val preferred = if (pointer + popupExtent <= windowExtent - edgeMargin) {
        pointer
    } else {
        pointer - popupExtent
    }
    return preferred.coerceIn(minimum, maximum)
}
