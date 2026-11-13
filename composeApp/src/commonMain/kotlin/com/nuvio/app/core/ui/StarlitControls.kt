package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Figures that change in place (times, sizes, counts) keep their width with tabular numerals. */
val TextStyle.tabular: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

/** The lighter success tint for small text on a `success` @ 0.14 fill. */
internal val NuvioColorTokens.successOnTint: Color
    get() = lerp(success, Color.White, 0.3f)

/** The container behind a segmented control or list that sits inside a sheet or card. */
internal val NuvioColorTokens.surfaceInset: Color
    get() = lerp(surface, background, 0.3f)

// --- Pill button -----------------------------------------------------------------------------

enum class NuvioPillButtonStyle {
    /** Accent fill: the one main action in a header or row. */
    Primary,

    /** White @ 8 %: Change, Select, Wake PC, Reset. */
    Secondary,

    /** Accent @ 12 % with an accent outline: an optional action that should still stand out. */
    Soft,
}

/** A 36 dp full-radius button for headers and row trailers. */
@Composable
fun NuvioPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NuvioPillButtonStyle = NuvioPillButtonStyle.Secondary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = NuvioTokens.Space.s36,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val base = when (style) {
        NuvioPillButtonStyle.Primary -> tokens.colors.accent
        NuvioPillButtonStyle.Secondary -> tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover)
        NuvioPillButtonStyle.Soft -> tokens.colors.accent.copy(alpha = tokens.opacity.pressed)
    }
    val content = when (style) {
        NuvioPillButtonStyle.Primary -> tokens.colors.onAccent
        NuvioPillButtonStyle.Secondary, NuvioPillButtonStyle.Soft -> tokens.colors.textPrimary
    }
    val container by animateColorAsState(
        targetValue = when {
            !(hovered || focused) || !enabled -> base
            style == NuvioPillButtonStyle.Primary -> tokens.colors.accentStrong
            else -> base.copy(alpha = base.alpha + 0.06f)
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "pill_button_container",
    )
    val shape = tokens.shapes.chip
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else tokens.opacity.disabled)
            .height(height)
            .clip(shape)
            .background(container)
            .then(
                when {
                    focused -> Modifier.border(tokens.borders.medium, tokens.colors.focusRing, shape)
                    style == NuvioPillButtonStyle.Soft -> Modifier.border(tokens.borders.thin, tokens.colors.borderSelected, shape)
                    else -> Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = NuvioTokens.Space.s14),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm), color = content, size = tokens.icons.sm)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(tokens.icons.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Tag --------------------------------------------------------------------------------------

enum class NuvioTagStyle {
    /** Accent @ 14 % with accent text: Best match, Current. */
    Accent,

    /** Success @ 14 % with a lighter success text: Cached. */
    Success,

    /** Warning @ 14 % with warning text. */
    Warning,

    /** Hairline outline with secondary text: Optional. */
    Outline,

    /** Card-coloured fill with secondary text: quality, HDR and audio labels. */
    Neutral,

    /** Dark translucent fill for a label over artwork. */
    Overlay,
}

/** A small full-radius label. */
@Composable
fun NuvioTag(
    text: String,
    modifier: Modifier = Modifier,
    style: NuvioTagStyle = NuvioTagStyle.Accent,
    icon: ImageVector? = null,
    height: Dp = NuvioTokens.Space.s22,
    shape: Shape = MaterialTheme.nuvio.shapes.chip,
) {
    val colors = MaterialTheme.nuvio.colors
    val container = when (style) {
        NuvioTagStyle.Accent -> colors.accent.copy(alpha = 0.14f)
        NuvioTagStyle.Success -> colors.success.copy(alpha = 0.14f)
        NuvioTagStyle.Warning -> colors.warning.copy(alpha = 0.14f)
        NuvioTagStyle.Outline -> Color.Transparent
        NuvioTagStyle.Neutral -> colors.surfaceCard
        NuvioTagStyle.Overlay -> colors.playerControlsBackground
    }
    val content = when (style) {
        NuvioTagStyle.Accent -> colors.accent
        NuvioTagStyle.Success -> colors.successOnTint
        NuvioTagStyle.Warning -> colors.warning
        NuvioTagStyle.Outline, NuvioTagStyle.Neutral -> colors.textSecondary
        NuvioTagStyle.Overlay -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(container)
            .then(
                if (style == NuvioTagStyle.Outline) {
                    Modifier.border(NuvioTokens.Border.thin, colors.borderStrong, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = NuvioTokens.Space.s8),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(NuvioTokens.Icon.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.nuvioTypeScale.labelXs,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Status eyebrow ---------------------------------------------------------------------------

enum class NuvioStatusTone { Success, Muted, Warning, Danger, Accent }

@Composable
internal fun NuvioStatusTone.color(): Color {
    val colors = MaterialTheme.nuvio.colors
    return when (this) {
        NuvioStatusTone.Success -> colors.success
        NuvioStatusTone.Muted -> colors.textMuted
        NuvioStatusTone.Warning -> colors.warning
        NuvioStatusTone.Danger -> colors.danger
        NuvioStatusTone.Accent -> colors.accent
    }
}

/** A status dot and a short muted label, shown above a page title or under a sheet title. */
@Composable
fun NuvioStatusEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    tone: NuvioStatusTone = NuvioStatusTone.Muted,
    busy: Boolean = false,
    style: TextStyle = MaterialTheme.nuvioTypeScale.bodySm,
) {
    val color = tone.color()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            NuvioLoadingIndicator(modifier = Modifier.size(NuvioTokens.Space.s10), color = color, size = NuvioTokens.Space.s10)
        } else {
            Box(Modifier.size(NuvioTokens.Space.s8).clip(CircleShape).background(color))
        }
        Text(
            text = text,
            style = style,
            color = MaterialTheme.nuvio.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Icon chip --------------------------------------------------------------------------------

enum class NuvioIconChipTone {
    /** Accent @ 12 % with an accent glyph: the default row icon. */
    Accent,

    /** White @ 6 % with a secondary glyph: saved or already-seen items. */
    Neutral,

    /** Solid accent with the on-accent colour: the current item. */
    AccentSolid,
    Success,
    Warning,
    Danger,
}

/** The rounded square behind a row or card icon. The content slot takes a glyph or a number. */
@Composable
fun NuvioIconChip(
    modifier: Modifier = Modifier,
    size: Dp = NuvioTokens.Space.s36,
    tone: NuvioIconChipTone = NuvioIconChipTone.Accent,
    shape: Shape = MaterialTheme.nuvio.shapes.compactCard,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val opacity = MaterialTheme.nuvio.opacity
    val container = when (tone) {
        NuvioIconChipTone.Accent -> colors.accent.copy(alpha = opacity.pressed)
        NuvioIconChipTone.Neutral -> colors.skeleton
        NuvioIconChipTone.AccentSolid -> colors.accent
        NuvioIconChipTone.Success -> colors.success.copy(alpha = 0.14f)
        NuvioIconChipTone.Warning -> colors.warning.copy(alpha = 0.14f)
        NuvioIconChipTone.Danger -> colors.danger.copy(alpha = 0.14f)
    }
    val contentColor = when (tone) {
        NuvioIconChipTone.Accent -> colors.accent
        NuvioIconChipTone.Neutral -> colors.textSecondary
        NuvioIconChipTone.AccentSolid -> colors.onAccent
        NuvioIconChipTone.Success -> colors.success
        NuvioIconChipTone.Warning -> colors.warning
        NuvioIconChipTone.Danger -> colors.danger
    }
    Box(
        modifier = modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = { content() })
    }
}

@Composable
fun NuvioIconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = NuvioTokens.Space.s36,
    tone: NuvioIconChipTone = NuvioIconChipTone.Accent,
    iconSize: Dp = if (size >= NuvioTokens.Space.s48) NuvioTokens.Icon.lg else NuvioTokens.Icon.md,
) {
    NuvioIconChip(modifier = modifier, size = size, tone = tone) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
    }
}

// --- Square icon button -----------------------------------------------------------------------

enum class NuvioSquareIconButtonStyle {
    /** White @ 8 %: close, back, copy, edit. */
    Soft,

    /** No fill: overflow menus, drag handles, minor transport keys. */
    Ghost,

    /** Accent @ 12 % with an accent glyph: play a finished download. */
    AccentSoft,

    /** Solid accent. */
    Accent,

    /** Surface-coloured fill for a button that sits on the page background. */
    Surface,
}

/** An icon-only button with a 12 dp radius. [contentDescription] is required: there is no label. */
@Composable
fun NuvioSquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = NuvioTokens.Space.s40,
    style: NuvioSquareIconButtonStyle = NuvioSquareIconButtonStyle.Soft,
    enabled: Boolean = true,
    iconSize: Dp = NuvioTokens.Icon.md,
    shape: Shape = MaterialTheme.nuvio.shapes.compactCard,
    tint: Color? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val base = when (style) {
        NuvioSquareIconButtonStyle.Soft -> tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover)
        NuvioSquareIconButtonStyle.Ghost -> Color.Transparent
        NuvioSquareIconButtonStyle.AccentSoft -> tokens.colors.accent.copy(alpha = tokens.opacity.pressed)
        NuvioSquareIconButtonStyle.Accent -> tokens.colors.accent
        NuvioSquareIconButtonStyle.Surface -> tokens.colors.surface
    }
    val container by animateColorAsState(
        targetValue = when {
            !enabled || !(hovered || focused || pressed) -> base
            style == NuvioSquareIconButtonStyle.Accent -> tokens.colors.accentStrong
            style == NuvioSquareIconButtonStyle.Surface -> tokens.colors.surfaceCard
            style == NuvioSquareIconButtonStyle.Ghost -> tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover)
            else -> base.copy(alpha = base.alpha + 0.06f)
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "square_icon_button_container",
    )
    val content = tint ?: when (style) {
        NuvioSquareIconButtonStyle.Soft, NuvioSquareIconButtonStyle.Surface -> tokens.colors.textPrimary
        NuvioSquareIconButtonStyle.Ghost -> tokens.colors.textSecondary
        NuvioSquareIconButtonStyle.AccentSoft -> tokens.colors.accent
        NuvioSquareIconButtonStyle.Accent -> tokens.colors.onAccent
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else tokens.opacity.disabled)
            .size(size)
            .clip(shape)
            .background(container)
            .then(if (focused) Modifier.border(tokens.borders.medium, tokens.colors.focusRing, shape) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

// --- Stepper ----------------------------------------------------------------------------------

/**
 * `−` value `+`. [value] is shown between the buttons; pass [center] to replace it with an
 * editable field (see [NuvioStepperField]).
 */
@Composable
fun NuvioStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
    buttonSize: Dp = NuvioTokens.Space.s36,
    valueMinWidth: Dp = NuvioTokens.Space.s56,
    center: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioSquareIconButton(
            icon = Icons.Rounded.Remove,
            contentDescription = decreaseLabel,
            onClick = onDecrease,
            size = buttonSize,
            enabled = decreaseEnabled,
            iconSize = tokens.icons.sm + NuvioTokens.Space.s2,
        )
        if (center != null) {
            center()
        } else {
            Text(
                text = value,
                modifier = Modifier.widthIn(min = valueMinWidth),
                style = MaterialTheme.typography.bodyLarge.tabular,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        NuvioSquareIconButton(
            icon = Icons.Rounded.Add,
            contentDescription = increaseLabel,
            onClick = onIncrease,
            size = buttonSize,
            enabled = increaseEnabled,
            iconSize = tokens.icons.sm + NuvioTokens.Space.s2,
        )
    }
}

/** The editable centre of a [NuvioStepper]: a short numeric field with a unit suffix. */
@Composable
fun NuvioStepperField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
    contentDescription: String? = null,
    width: Dp = 104.dp,
    height: Dp = NuvioTokens.Space.s36,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = tokens.shapes.compactCard
    val border by animateColorAsState(
        targetValue = when {
            isError -> tokens.colors.danger
            focused -> tokens.colors.borderSelected
            else -> tokens.colors.borderDefault
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "stepper_field_border",
    )
    val textStyle = MaterialTheme.typography.bodyLarge.tabular.copy(
        color = tokens.colors.textPrimary,
        fontWeight = FontWeight.SemiBold,
        textAlign = if (suffix == null) TextAlign.Center else TextAlign.End,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .width(width)
            .then(contentDescription?.let { description -> Modifier.semantics { this.contentDescription = description } } ?: Modifier),
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(tokens.colors.accent),
        interactionSource = interactionSource,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(height)
                    .clip(shape)
                    .background(tokens.colors.surfaceCard)
                    .border(tokens.borders.thin, border, shape)
                    .padding(horizontal = NuvioTokens.Space.s10),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = if (suffix == null) Alignment.Center else Alignment.CenterEnd) {
                    innerTextField()
                }
                if (suffix != null) {
                    Text(suffix, style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted, maxLines = 1)
                }
            }
        },
    )
}

// --- Section header, field label, group pieces ------------------------------------------------

/** A section label with an optional inline action on the right ("Pause all", "Refresh", "Clear"). */
@Composable
fun NuvioSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTestTag: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioSectionLabel(text = title, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            NuvioActionLabel(
                text = actionLabel,
                onClick = onAction,
                style = MaterialTheme.typography.labelLarge,
                modifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier,
            )
        }
        trailing?.invoke(this)
    }
}

/** The label above an input field in a dialog or editor. */
@Composable
fun NuvioFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.nuvioTypeScale.bodySm,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.nuvio.colors.textSecondary,
    )
}

/** A label above an input field, with an optional helper or error line below it. */
@Composable
fun NuvioLabeledField(
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    isError: Boolean = false,
    field: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
        NuvioFieldLabel(label)
        field()
        if (!helper.isNullOrBlank()) {
            Text(
                text = helper,
                style = MaterialTheme.nuvioTypeScale.labelSm,
                fontWeight = FontWeight.Normal,
                color = if (isError) MaterialTheme.nuvio.colors.danger else MaterialTheme.nuvio.colors.textMuted,
            )
        }
    }
}

/** A grouped list container: surface fill, hairline border, radius 12 on phones and 16 on tablets. */
@Composable
fun NuvioGroup(
    modifier: Modifier = Modifier,
    isTablet: Boolean = false,
    containerColor: Color = MaterialTheme.nuvio.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = if (isTablet) RoundedCornerShape(NuvioTokens.Radius.xl) else tokens.shapes.compactCard
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, shape),
        content = content,
    )
}

/** The divider between two rows of a group. [startInset] lines it up with the row text. */
@Composable
fun NuvioGroupDivider(
    startInset: Dp = NuvioTokens.Space.s64 + NuvioTokens.Space.s2,
    endInset: Dp = NuvioTokens.Space.none,
) {
    val tokens = MaterialTheme.nuvio
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = endInset),
        thickness = tokens.borders.thin,
        color = tokens.colors.borderSubtle,
    )
}

/**
 * A row inside a [NuvioGroup] for cases `SettingsNavigationRow` does not cover: a custom leading
 * element (poster, number chip), a meta line with tags, a highlighted row, or trailing buttons.
 */
@Composable
fun NuvioGroupRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    titleColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    titleMaxLines: Int = 2,
    descriptionMaxLines: Int = 2,
    verticalPadding: Dp = NuvioTokens.Space.s14,
    startPadding: Dp = NuvioTokens.Space.s16,
    endPadding: Dp = NuvioTokens.Space.s16,
    spacing: Dp = NuvioTokens.Space.s14,
    leading: (@Composable () -> Unit)? = null,
    meta: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier.background(tokens.colors.accent.copy(alpha = tokens.opacity.subtle))
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = startPadding, end = endPadding, top = verticalPadding, bottom = verticalPadding)
            .alpha(if (enabled) 1f else tokens.opacity.medium),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    maxLines = descriptionMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta?.invoke(this)
        }
        trailing?.invoke(this)
    }
}

/** A centred empty or status block: icon chip, title, message and an optional action below. */
@Composable
fun NuvioEmptyBlock(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    tone: NuvioIconChipTone = NuvioIconChipTone.Accent,
    busy: Boolean = false,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        NuvioIconChip(size = NuvioTokens.Space.s48, tone = tone) {
            if (busy) {
                NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.lg), color = LocalContentColor.current, size = tokens.icons.lg)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(tokens.icons.lg))
            }
        }
        Text(
            text = title,
            modifier = Modifier.padding(top = NuvioTokens.Space.s8),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Column(
                modifier = Modifier.padding(top = NuvioTokens.Space.s10),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = action,
            )
        }
    }
}

/** The fill behind a best-match or current row, already composited so it can sit on any surface. */
@Composable
internal fun nuvioHighlightedRowColor(container: Color = MaterialTheme.nuvio.colors.surface): Color =
    MaterialTheme.nuvio.colors.accent.copy(alpha = MaterialTheme.nuvio.opacity.subtle).compositeOver(container)

/**
 * One row of a group whose rows are separate lazy-list items: the fill, the side borders, and the
 * rounded top or bottom edge on the first or last row. The divider between rows stays with the
 * caller. A row that is both first and last draws a whole group.
 */
fun Modifier.nuvioGroupSegment(
    isFirst: Boolean,
    isLast: Boolean,
    containerColor: Color,
    borderColor: Color,
    radius: Dp = NuvioTokens.Radius.lg,
    borderWidth: Dp = NuvioTokens.Border.thin,
): Modifier = this
    .clip(
        RoundedCornerShape(
            topStart = if (isFirst) radius else 0.dp,
            topEnd = if (isFirst) radius else 0.dp,
            bottomStart = if (isLast) radius else 0.dp,
            bottomEnd = if (isLast) radius else 0.dp,
        ),
    )
    .background(containerColor)
    .drawWithContent {
        drawContent()
        val stroke = borderWidth.toPx()
        val corner = radius.toPx()
        // Middle rows extend the outline past their own edges so only its sides stay visible.
        val overhang = corner * 2 + stroke
        val top = if (isFirst) stroke / 2 else -overhang
        val bottom = if (isLast) size.height - stroke / 2 else size.height + overhang
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(stroke / 2, top),
            size = Size(size.width - stroke, bottom - top),
            cornerRadius = CornerRadius((corner - stroke / 2).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }

/**
 * A page header: back button, an optional eyebrow, a 26 sp title and trailing actions. Under a
 * native navigation bar (which already shows the title and back) only the actions remain.
 */
@Composable
fun NuvioPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    onBack: (() -> Unit)? = null,
    includeStatusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nativeNavigation = LocalUseNativeNavigation.current && !LocalNativeNavigationBarHidden.current && onBack != null
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().background(tokens.colors.background).nuvioConsumePointerEvents())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    top = if (includeStatusBarPadding && !nativeNavigation) statusBarTop else NuvioTokens.Space.none,
                    bottom = NuvioTokens.Space.s8,
                )
                .heightIn(min = NuvioTokens.Space.s48),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nativeNavigation) {
                Spacer(Modifier.weight(1f))
            } else {
                if (onBack != null) {
                    NuvioBackButton(
                        onClick = onBack,
                        shape = tokens.shapes.compactCard,
                        buttonSize = NuvioTokens.Space.s36,
                        showContainerOnDesktop = true,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                    if (!eyebrow.isNullOrBlank()) {
                        Text(
                            eyebrow,
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}
