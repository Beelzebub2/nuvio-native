package com.nuvio.app.features.streams

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDesktopTextField
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Reused for desktop, mobile, tablet and phone-to-PC result lists. */
@Composable
internal fun SourceResultsFilterControls(
    filters: SourceResultsFilters,
    availability: SourceFilterAvailability,
    onFiltersChange: (SourceResultsFilters) -> Unit,
    modifier: Modifier = Modifier,
    showQuality: Boolean = true,
) {
    if (availability.total == 0 && !filters.isActive) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NuvioDesktopTextField(
            value = filters.search,
            onValueChange = { onFiltersChange(filters.copy(search = it)) },
            placeholder = stringResource(Res.string.source_filter_search),
            leadingIcon = Icons.Rounded.Search,
            modifier = Modifier.fillMaxWidth().testTag("source-results-search"),
        )
        if (showQuality && (availability.qualityCounts.size > 1 || filters.quality != null)) {
            val segments = buildList {
                add(
                    NuvioSegment<StreamQuality?>(
                        null,
                        stringResource(Res.string.streams_quality_all),
                        count = availability.total.toString(),
                    ),
                )
                val otherLabel = stringResource(Res.string.streams_quality_other)
                StreamQuality.entries.sortedByDescending { it.rank }.forEach { quality ->
                    availability.qualityCounts[quality]?.let { count ->
                        add(NuvioSegment<StreamQuality?>(quality, quality.label(otherLabel), count.toString()))
                    }
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                NuvioSegmentedControl(
                    segments = segments,
                    selected = filters.quality,
                    onSelect = { onFiltersChange(filters.copy(quality = it)) },
                    size = NuvioSegmentedSize.Compact,
                )
            }
        }
        val showOptions = availability.hasHdr || availability.hasAtmos || availability.hasSurround ||
            availability.maxSeeders != null || availability.hasSize || filters.isActive
        if (showOptions) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (availability.hasHdr || filters.hdrOnly) {
                    SourceFilterToggle(
                        text = stringResource(Res.string.source_filter_hdr),
                        selected = filters.hdrOnly,
                        tag = "source-filter-hdr",
                    ) { onFiltersChange(filters.copy(hdrOnly = !filters.hdrOnly)) }
                }
                if (availability.hasAtmos || filters.audio == SourceAudioFilter.ATMOS) {
                    SourceFilterToggle(
                        text = stringResource(Res.string.source_filter_atmos),
                        selected = filters.audio == SourceAudioFilter.ATMOS,
                        tag = "source-filter-atmos",
                    ) {
                        onFiltersChange(filters.copy(audio =
                            if (filters.audio == SourceAudioFilter.ATMOS) null else SourceAudioFilter.ATMOS))
                    }
                }
                if (availability.hasSurround || filters.audio == SourceAudioFilter.SURROUND) {
                    SourceFilterToggle(
                        text = stringResource(Res.string.source_filter_surround),
                        selected = filters.audio == SourceAudioFilter.SURROUND,
                        tag = "source-filter-surround",
                    ) {
                        onFiltersChange(filters.copy(audio =
                            if (filters.audio == SourceAudioFilter.SURROUND) null else SourceAudioFilter.SURROUND))
                    }
                }
                listOf(
                    10 to stringResource(Res.string.source_filter_seeds_ten),
                    50 to stringResource(Res.string.source_filter_seeds_fifty),
                ).forEach { (minimum, label) ->
                    if ((availability.maxSeeders ?: -1) >= minimum || filters.minSeeders == minimum) {
                        SourceFilterToggle(
                            text = label,
                            selected = filters.minSeeders == minimum,
                            tag = "source-filter-seeds-" + minimum,
                        ) {
                            onFiltersChange(filters.copy(minSeeders =
                                if (filters.minSeeders == minimum) null else minimum))
                        }
                    }
                }
                listOf(
                    2 to stringResource(Res.string.source_filter_size_two),
                    5 to stringResource(Res.string.source_filter_size_five),
                ).forEach { (gib, label) ->
                    val limit = gib * SOURCE_GIB
                    if (availability.hasSize || filters.maxSizeBytes == limit) {
                        SourceFilterToggle(
                            text = label,
                            selected = filters.maxSizeBytes == limit,
                            tag = "source-filter-size-" + gib,
                        ) {
                            onFiltersChange(filters.copy(maxSizeBytes =
                                if (filters.maxSizeBytes == limit) null else limit))
                        }
                    }
                }
                if (filters.isActive) {
                    TextButton(onClick = { onFiltersChange(SourceResultsFilters()) },
                        modifier = Modifier.testTag("source-filter-clear")) {
                        Text(stringResource(Res.string.source_filter_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFilterToggle(
    text: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.testTag(tag),
    )
}
