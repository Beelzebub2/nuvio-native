package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.HoverPreviewOpenDelayStepMillis
import com.nuvio.app.core.ui.MaxHoverPreviewOpenDelayMillis
import com.nuvio.app.core.ui.MinHoverPreviewOpenDelayMillis
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_hover_preview_delay
import nuvio.composeapp.generated.resources.settings_hover_preview_delay_description
import nuvio.composeapp.generated.resources.settings_hover_preview_delay_value
import nuvio.composeapp.generated.resources.settings_hover_preview_enabled
import nuvio.composeapp.generated.resources.settings_hover_preview_enabled_description
import nuvio.composeapp.generated.resources.settings_hover_preview_section_behavior
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal fun LazyListScope.hoverPreviewSettingsContent(
    isTablet: Boolean,
    uiState: PosterCardStyleUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_hover_preview_section_behavior),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_hover_preview_enabled),
                    description = stringResource(Res.string.settings_hover_preview_enabled_description),
                    checked = uiState.hoverPreviewEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PosterCardStyleRepository::setHoverPreviewEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                HoverPreviewDelaySlider(
                    isTablet = isTablet,
                    enabled = uiState.hoverPreviewEnabled,
                    delayMillis = uiState.hoverPreviewOpenDelayMillis,
                )
            }
        }
    }
}

@Composable
private fun HoverPreviewDelaySlider(
    isTablet: Boolean,
    enabled: Boolean,
    delayMillis: Int,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    var sliderValue by remember(delayMillis) { mutableFloatStateOf(delayMillis.toFloat()) }
    val snappedDelayMillis = sliderValue.roundToInt()
    val delaySeconds = if (snappedDelayMillis % 1_000 == 0) {
        (snappedDelayMillis / 1_000).toString()
    } else {
        "${snappedDelayMillis / 1_000}.${(snappedDelayMillis % 1_000) / 100}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_hover_preview_delay),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            ValueBox(
                text = stringResource(
                    Res.string.settings_hover_preview_delay_value,
                    delaySeconds,
                ),
            )
        }
        Text(
            text = stringResource(Res.string.settings_hover_preview_delay_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = {
                if (enabled) {
                    sliderValue = snapToStep(it, HoverPreviewOpenDelayStepMillis.toFloat())
                }
            },
            onValueChangeFinished = {
                if (enabled) {
                    PosterCardStyleRepository.setHoverPreviewOpenDelayMillis(sliderValue.roundToInt())
                }
            },
            enabled = enabled,
            valueRange = MinHoverPreviewOpenDelayMillis.toFloat()..MaxHoverPreviewOpenDelayMillis.toFloat(),
            steps = calculateSteps(
                MinHoverPreviewOpenDelayMillis.toFloat(),
                MaxHoverPreviewOpenDelayMillis.toFloat(),
                HoverPreviewOpenDelayStepMillis.toFloat(),
            ),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
