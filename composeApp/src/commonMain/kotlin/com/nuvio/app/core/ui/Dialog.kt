package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.DialogProperties

enum class DialogButtonStyle {
    Primary,
    Secondary,
    Destructive,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BasicAlertDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth)
                .semantics { if (title != null) paneTitle = title },
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            border = BorderStroke(tokens.borders.thin, SurfaceEdge),
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                    )
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                }
                content()
            }
        }
    }
}

@Composable
fun DialogButtons(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = NuvioTokens.Space.s8),
    ) { measurables, constraints ->
        val gap = NuvioTokens.Space.s10.roundToPx()
        val width = constraints.maxWidth
        val totalGap = gap * (measurables.size - 1).coerceAtLeast(0)
        val itemWidth = (width - totalGap) / measurables.size.coerceAtLeast(1)
        if (measurables.all { it.maxIntrinsicWidth(constraints.maxHeight) <= itemWidth }) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(itemWidth)) }
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (itemWidth + gap), 0)
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }.asReversed()
            layout(width, placeables.sumOf { it.height } + totalGap) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DialogButtonStyle = DialogButtonStyle.Secondary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val containerColor = when (style) {
        DialogButtonStyle.Primary -> tokens.colors.accent
        DialogButtonStyle.Secondary -> tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover)
        DialogButtonStyle.Destructive -> tokens.colors.danger
    }
    val contentColor = if (style == DialogButtonStyle.Secondary) tokens.colors.textPrimary else tokens.colors.onAccent
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = NuvioTokens.Space.s48),
        enabled = enabled && !loading,
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = containerColor.alpha * tokens.opacity.disabled),
            disabledContentColor = contentColor.copy(alpha = tokens.opacity.disabled),
        ),
        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
    ) {
        if (loading) {
            NuvioLoadingIndicator(
                modifier = Modifier.size(tokens.icons.sm),
                color = contentColor,
            )
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s8))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DialogOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    leading: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(
                if (selected) {
                    tokens.colors.accent.copy(alpha = tokens.opacity.selected)
                } else {
                    tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle)
                },
            )
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(tokens.icons.md),
            )
        }
    }
}

/** A small heading inside a dialog or card, such as "Season" or "Quality". */
@Composable
fun DialogSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.nuvio.colors.textSecondary,
    )
}

/** A short hint or status line inside a dialog. Errors use the danger color. */
@Composable
fun DialogHint(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) colors.danger else colors.textMuted,
    )
}

/** An outlined text field in the app's colors, for dialogs and settings cards. */
@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = tokens.colors.textMuted) } },
        textStyle = textStyle.copy(color = tokens.colors.textPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = tokens.colors.borderDefault,
            disabledBorderColor = tokens.colors.borderSubtle,
            errorBorderColor = tokens.colors.danger,
            // Transparent so the floating label's notch blends into any dialog or card surface.
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            focusedLabelColor = tokens.colors.textSecondary,
            unfocusedLabelColor = tokens.colors.textMuted,
            errorLabelColor = tokens.colors.danger,
            cursorColor = tokens.colors.accent,
            errorCursorColor = tokens.colors.danger,
        ),
    )
}

/** Switch colors used across settings: accent track when on, quiet border when off. */
@Composable
fun nuvioSwitchColors(): SwitchColors {
    val colors = MaterialTheme.nuvio.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onAccent,
        checkedTrackColor = colors.accent,
        uncheckedThumbColor = colors.textMuted,
        uncheckedTrackColor = colors.borderDefault,
    )
}

/** Checkbox colors that follow the app accent instead of Material's default purple. */
@Composable
fun nuvioCheckboxColors(): CheckboxColors {
    val colors = MaterialTheme.nuvio.colors
    return CheckboxDefaults.colors(
        checkedColor = colors.accent,
        uncheckedColor = colors.textMuted,
        checkmarkColor = colors.onAccent,
        disabledCheckedColor = colors.textDisabled,
        disabledUncheckedColor = colors.textDisabled,
        disabledIndeterminateColor = colors.textDisabled,
    )
}

/** A full-width row with a label and a switch; the whole row toggles. */
@Composable
fun DialogSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.nuvio.shapes.compactCard)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = NuvioTokens.Space.s8),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
            )
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = nuvioSwitchColors())
    }
}

/** A selectable list row with a checkbox, for picking several items inside a dialog. */
@Composable
fun DialogCheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(
                if (checked && enabled) {
                    tokens.colors.accent.copy(alpha = tokens.opacity.selected)
                } else {
                    tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle)
                },
            )
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, colors = nuvioCheckboxColors())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
    }
}
