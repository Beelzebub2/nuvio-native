package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSlider
import com.nuvio.app.core.ui.NuvioStepper
import com.nuvio.app.core.ui.NuvioStepperField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSwitchColors
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cd_decrease_value
import nuvio.composeapp.generated.resources.cd_increase_value
import nuvio.composeapp.generated.resources.compose_player_auto_sync
import nuvio.composeapp.generated.resources.compose_player_bold
import nuvio.composeapp.generated.resources.compose_player_bottom_offset
import nuvio.composeapp.generated.resources.compose_player_capture_line
import nuvio.composeapp.generated.resources.compose_player_color
import nuvio.composeapp.generated.resources.compose_player_font_size
import nuvio.composeapp.generated.resources.compose_player_font_size_value
import nuvio.composeapp.generated.resources.compose_player_loading_lines
import nuvio.composeapp.generated.resources.compose_player_no_subtitle_lines_found
import nuvio.composeapp.generated.resources.compose_player_outline
import nuvio.composeapp.generated.resources.compose_player_outline_color
import nuvio.composeapp.generated.resources.compose_player_outline_thickness
import nuvio.composeapp.generated.resources.compose_player_reload
import nuvio.composeapp.generated.resources.compose_player_reset
import nuvio.composeapp.generated.resources.compose_player_reset_defaults
import nuvio.composeapp.generated.resources.compose_player_select_addon_subtitle_first
import nuvio.composeapp.generated.resources.compose_player_style
import nuvio.composeapp.generated.resources.compose_player_subtitle_delay
import nuvio.composeapp.generated.resources.compose_player_text
import nuvio.composeapp.generated.resources.compose_player_text_opacity
import nuvio.composeapp.generated.resources.compose_player_timing
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SubtitleStylePanel(
    style: SubtitleStyleState,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: AddonSubtitle?,
    subtitleAutoSyncState: SubtitleAutoSyncUiState,
    isCompact: Boolean,
    showHeader: Boolean = true,
    onStyleChanged: (SubtitleStyleState) -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
    onSubtitleDelayReset: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncCueSelected: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sectionGap = if (isCompact) 12.dp else 16.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sectionGap),
    ) {
        if (showHeader) {
            Text(
                text = stringResource(Res.string.compose_player_style),
                color = tokens.colors.textPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
        }

        PanelSection(title = stringResource(Res.string.compose_player_timing)) {
            PanelGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SubtitleDelayRow(
                        subtitleDelayMs = subtitleDelayMs,
                        onSubtitleDelayChanged = onSubtitleDelayChanged,
                    )
                    NuvioPillButton(
                        text = stringResource(Res.string.compose_player_reset),
                        onClick = onSubtitleDelayReset,
                        enabled = subtitleDelayMs != 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        PanelSection(title = stringResource(Res.string.compose_player_text)) {
            PanelGroup {
                val fontSizeLabel = stringResource(Res.string.compose_player_font_size)
                PanelRow(title = fontSizeLabel) {
                    NuvioStepper(
                        value = stringResource(Res.string.compose_player_font_size_value, style.fontSizeSp),
                        onDecrease = {
                            onStyleChanged(style.copy(fontSizeSp = (style.fontSizeSp - 2).coerceAtLeast(subtitleFontSizeRangeSp.first)))
                        },
                        onIncrease = {
                            onStyleChanged(style.copy(fontSizeSp = (style.fontSizeSp + 2).coerceAtMost(subtitleFontSizeRangeSp.last)))
                        },
                        decreaseLabel = stringResource(Res.string.cd_decrease_value, fontSizeLabel),
                        increaseLabel = stringResource(Res.string.cd_increase_value, fontSizeLabel),
                        decreaseEnabled = style.fontSizeSp > subtitleFontSizeRangeSp.first,
                        increaseEnabled = style.fontSizeSp < subtitleFontSizeRangeSp.last,
                        valueMinWidth = 52.dp,
                    )
                }
                PanelDivider()
                val opacity = (style.textColor.alpha * 100f).roundToInt().coerceIn(0, 100)
                PanelRow(title = stringResource(Res.string.compose_player_text_opacity), titleWeight = false) {
                    NuvioSlider(
                        value = opacity / 100f,
                        onValueChange = { value ->
                            // Five-percent steps keep the preview smooth without a save per pixel.
                            val alpha = ((value * 20f).roundToInt() / 20f).coerceIn(0f, 1f)
                            if (abs(alpha - style.textColor.alpha) > 0.001f) {
                                onStyleChanged(style.copy(textColor = style.textColor.copy(alpha = alpha)))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$opacity%",
                        modifier = Modifier.width(44.dp),
                        color = tokens.colors.textMuted,
                        style = MaterialTheme.nuvioTypeScale.bodySm.tabular,
                        textAlign = TextAlign.End,
                    )
                }
                PanelDivider()
                PanelRow(title = stringResource(Res.string.compose_player_color)) {
                    SubtitleColorPicker(
                        colors = SubtitleColorSwatches,
                        selectedColor = style.textColor,
                        onColorSelected = { color ->
                            onStyleChanged(style.copy(textColor = color.copy(alpha = style.textColor.alpha)))
                        },
                        modifier = Modifier.weight(1.6f, fill = false),
                    )
                }
                PanelDivider()
                PanelSwitchRow(
                    title = stringResource(Res.string.compose_player_bold),
                    checked = style.bold,
                    onCheckedChange = { onStyleChanged(style.copy(bold = it)) },
                )
                PanelDivider()
                val offsetLabel = stringResource(Res.string.compose_player_bottom_offset)
                PanelRow(title = offsetLabel) {
                    NuvioStepper(
                        value = style.bottomOffset.toString(),
                        onDecrease = { onStyleChanged(style.copy(bottomOffset = (style.bottomOffset - 5).coerceAtLeast(0))) },
                        onIncrease = { onStyleChanged(style.copy(bottomOffset = (style.bottomOffset + 5).coerceAtMost(200))) },
                        decreaseLabel = stringResource(Res.string.cd_decrease_value, offsetLabel),
                        increaseLabel = stringResource(Res.string.cd_increase_value, offsetLabel),
                        decreaseEnabled = style.bottomOffset > 0,
                        increaseEnabled = style.bottomOffset < 200,
                        valueMinWidth = 52.dp,
                    )
                }
            }
        }

        PanelSection(title = stringResource(Res.string.compose_player_outline)) {
            PanelGroup {
                PanelSwitchRow(
                    title = stringResource(Res.string.compose_player_outline),
                    checked = style.outlineEnabled,
                    onCheckedChange = { onStyleChanged(style.copy(outlineEnabled = it)) },
                )
                if (style.outlineEnabled) {
                    PanelDivider()
                    val thicknessLabel = stringResource(Res.string.compose_player_outline_thickness)
                    PanelRow(title = thicknessLabel) {
                        NuvioStepper(
                            value = "${style.outlineWidth} px",
                            onDecrease = { onStyleChanged(style.copy(outlineWidth = (style.outlineWidth - 1).coerceAtLeast(0))) },
                            onIncrease = { onStyleChanged(style.copy(outlineWidth = (style.outlineWidth + 1).coerceAtMost(10))) },
                            decreaseLabel = stringResource(Res.string.cd_decrease_value, thicknessLabel),
                            increaseLabel = stringResource(Res.string.cd_increase_value, thicknessLabel),
                            decreaseEnabled = style.outlineWidth > 0,
                            increaseEnabled = style.outlineWidth < 10,
                            valueMinWidth = 52.dp,
                        )
                    }
                    PanelDivider()
                    PanelRow(title = stringResource(Res.string.compose_player_outline_color)) {
                        SubtitleColorPicker(
                            colors = SubtitleOutlineColorSwatches,
                            selectedColor = style.outlineColor,
                            onColorSelected = { color -> onStyleChanged(style.copy(outlineEnabled = true, outlineColor = color)) },
                            modifier = Modifier.weight(1.6f, fill = false),
                        )
                    }
                }
            }
        }

        SubtitleAutoSyncSection(
            selectedAddonSubtitle = selectedAddonSubtitle,
            state = subtitleAutoSyncState,
            onCapture = onAutoSyncCapture,
            onCueSelected = onAutoSyncCueSelected,
            onReload = onAutoSyncReload,
        )

        DialogButton(
            text = stringResource(Res.string.compose_player_reset_defaults),
            onClick = { onStyleChanged(SubtitleStyleState.DEFAULT) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Only apply complete signed millisecond values within the native delay range. */
internal fun parseSubtitleDelayInput(value: String): Int? = value.trim().toIntOrNull()
    ?.takeIf { it in SUBTITLE_DELAY_MIN_MS..SUBTITLE_DELAY_MAX_MS }

/**
 * Subtitle Delay with -100 ms / +100 ms buttons around an editable millisecond field. A value
 * outside the range turns the field's border red and is not applied.
 */
@Composable
private fun SubtitleDelayRow(
    subtitleDelayMs: Int,
    onSubtitleDelayChanged: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var delayInput by remember { mutableStateOf(subtitleDelayMs.toString()) }
    var editingDelay by remember { mutableStateOf(false) }
    LaunchedEffect(subtitleDelayMs, editingDelay) {
        if (!editingDelay) delayInput = subtitleDelayMs.toString()
    }
    val delayLabel = stringResource(Res.string.compose_player_subtitle_delay)
    val invalid = delayInput.isNotEmpty() && delayInput != "-" && parseSubtitleDelayInput(delayInput) == null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = delayLabel,
            modifier = Modifier.weight(1f),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        NuvioStepper(
            value = "",
            onDecrease = {
                onSubtitleDelayChanged((subtitleDelayMs - SUBTITLE_DELAY_STEP_MS).coerceAtLeast(SUBTITLE_DELAY_MIN_MS))
            },
            onIncrease = {
                onSubtitleDelayChanged((subtitleDelayMs + SUBTITLE_DELAY_STEP_MS).coerceAtMost(SUBTITLE_DELAY_MAX_MS))
            },
            decreaseLabel = stringResource(Res.string.cd_decrease_value, delayLabel),
            increaseLabel = stringResource(Res.string.cd_increase_value, delayLabel),
            decreaseEnabled = subtitleDelayMs > SUBTITLE_DELAY_MIN_MS,
            increaseEnabled = subtitleDelayMs < SUBTITLE_DELAY_MAX_MS,
            buttonSize = 40.dp,
            center = {
                NuvioStepperField(
                    value = delayInput,
                    onValueChange = { next ->
                        if (next.length <= 7 && next.all { it.isDigit() || it == '-' } &&
                            next.count { it == '-' } <= 1 && next.indexOf('-') <= 0
                        ) {
                            delayInput = next
                            parseSubtitleDelayInput(next)?.let(onSubtitleDelayChanged)
                        }
                    },
                    modifier = Modifier.onFocusChanged { focus ->
                        if (editingDelay && !focus.isFocused && parseSubtitleDelayInput(delayInput) == null) {
                            delayInput = subtitleDelayMs.toString()
                        }
                        editingDelay = focus.isFocused
                    },
                    suffix = "ms",
                    isError = invalid,
                    contentDescription = delayLabel,
                    width = 108.dp,
                    height = 40.dp,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            },
        )
    }
}

/** A section label above a group of panel rows. */
@Composable
private fun PanelSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioSectionLabel(text = title.uppercase())
        content()
    }
}

/** Rows on the card colour so the group stands out from the panel surface. */
@Composable
private fun PanelGroup(content: @Composable ColumnScope.() -> Unit) {
    NuvioGroup(containerColor = MaterialTheme.nuvio.colors.surfaceCard, content = content)
}

@Composable
private fun PanelDivider() {
    NuvioGroupDivider(startInset = 14.dp, endInset = 14.dp)
}

/** A label on the left and its control on the right. */
@Composable
private fun PanelRow(
    title: String,
    titleWeight: Boolean = true,
    control: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = if (titleWeight) Modifier.weight(1f) else Modifier.widthIn(min = 96.dp, max = 132.dp),
            color = MaterialTheme.nuvio.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        control()
    }
}

@Composable
private fun PanelSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.nuvio.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Switch(checked = checked, onCheckedChange = null, colors = nuvioSwitchColors())
    }
}

/** Round swatches. The selected one gets a gap and an accent ring around it. */
@Composable
private fun SubtitleColorPicker(
    colors: List<Color>,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .alpha(if (enabled) 1f else tokens.opacity.disabled),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { color ->
            val selected = sameRgb(color, selectedColor)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(if (selected) Modifier.border(2.dp, tokens.colors.accent, CircleShape) else Modifier)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onColorSelected(color) })
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun SubtitleAutoSyncSection(
    selectedAddonSubtitle: AddonSubtitle?,
    state: SubtitleAutoSyncUiState,
    onCapture: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit,
    onReload: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val capturedPositionMs = state.capturedPositionMs
    val nearestCues = if (capturedPositionMs == null) {
        emptyList()
    } else {
        state.cues.sortedBy { abs(it.startTimeMs - capturedPositionMs) }.take(5)
    }

    PanelSection(title = stringResource(Res.string.compose_player_auto_sync)) {
        PanelGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NuvioPillButton(
                        text = stringResource(Res.string.compose_player_capture_line),
                        onClick = onCapture,
                        style = NuvioPillButtonStyle.Soft,
                        enabled = selectedAddonSubtitle != null,
                        modifier = Modifier.weight(1f),
                    )
                    NuvioPillButton(
                        text = stringResource(Res.string.compose_player_reload),
                        onClick = onReload,
                        enabled = selectedAddonSubtitle != null,
                        modifier = Modifier.weight(1f),
                    )
                }
                when {
                    selectedAddonSubtitle == null -> SubtitleHelperText(stringResource(Res.string.compose_player_select_addon_subtitle_first))
                    state.isLoading -> SubtitleHelperText(stringResource(Res.string.compose_player_loading_lines))
                    state.errorMessage != null -> Text(
                        text = state.errorMessage,
                        color = tokens.colors.danger,
                        style = MaterialTheme.nuvioTypeScale.bodySm,
                    )
                    capturedPositionMs != null && nearestCues.isEmpty() ->
                        SubtitleHelperText(stringResource(Res.string.compose_player_no_subtitle_lines_found))
                }
                nearestCues.forEach { cue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.shapes.compactCard)
                            .background(tokens.colors.surface)
                            .clickable(role = Role.Button) { onCueSelected(cue) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = formatCueTimestamp(cue.startTimeMs),
                            color = tokens.colors.accent,
                            style = MaterialTheme.nuvioTypeScale.bodySm.tabular,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = cue.text,
                            modifier = Modifier.weight(1f),
                            color = tokens.colors.textPrimary,
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleHelperText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.nuvio.colors.textMuted,
        style = MaterialTheme.nuvioTypeScale.bodySm,
    )
}

private fun sameRgb(first: Color, second: Color): Boolean =
    abs(first.red - second.red) < 0.01f &&
        abs(first.green - second.green) < 0.01f &&
        abs(first.blue - second.blue) < 0.01f

private fun formatCueTimestamp(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal val SubtitleOutlineColorSwatches = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
)
