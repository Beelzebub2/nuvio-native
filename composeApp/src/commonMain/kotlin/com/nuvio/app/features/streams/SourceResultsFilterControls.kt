package com.nuvio.app.features.streams

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioDesktopTextField
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.surfaceInset
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Equal-width quality segments need about this much room each before they start to clip. */
private val QualitySegmentMinWidth = 76.dp

/**
 * Search, quality and option filters for a list of sources. Reused for desktop, mobile, tablet
 * and phone-to-PC result lists.
 *
 * [resultCount] is the number of sources left after filtering; with it the last line reads
 * "4 OF 24 SOURCES" next to Clear filters. [onSheet] darkens the quality control so it reads as
 * an inset when the controls sit on a sheet or card instead of the page background.
 */
@Composable
internal fun SourceResultsFilterControls(
    filters: SourceResultsFilters,
    availability: SourceFilterAvailability,
    onFiltersChange: (SourceResultsFilters) -> Unit,
    modifier: Modifier = Modifier,
    showQuality: Boolean = true,
    resultCount: Int? = null,
    onSheet: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    searchHeight: Dp = 48.dp,
) {
    if (availability.total == 0 && !filters.isActive) return
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val searchPlaceholder = stringResource(Res.string.source_filter_search)
        if (isDesktop) {
            // Desktop keeps the toolbar field: Escape clears it and it carries its own clear button.
            NuvioDesktopTextField(
                value = filters.search,
                onValueChange = { onFiltersChange(filters.copy(search = it)) },
                placeholder = searchPlaceholder,
                leadingIcon = Icons.Rounded.Search,
                modifier = Modifier.fillMaxWidth().testTag("source-results-search"),
            )
        } else {
            NuvioInputField(
                value = filters.search,
                onValueChange = { onFiltersChange(filters.copy(search = it)) },
                placeholder = searchPlaceholder,
                leadingIcon = Icons.Rounded.Search,
                minHeight = searchHeight,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.testTag("source-results-search"),
                trailingContent = if (filters.search.isEmpty()) {
                    null
                } else {
                    {
                        NuvioSquareIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.compose_search_clear),
                            onClick = { onFiltersChange(filters.copy(search = "")) },
                            size = 36.dp,
                            style = NuvioSquareIconButtonStyle.Ghost,
                            iconSize = 18.dp,
                        )
                    }
                },
            )
        }
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
            val container = if (onSheet) tokens.colors.surfaceInset else tokens.colors.surface
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // A few qualities share the full width; a long list keeps its own widths and scrolls.
                if (QualitySegmentMinWidth * segments.size <= maxWidth) {
                    NuvioSegmentedControl(
                        segments = segments,
                        selected = filters.quality,
                        onSelect = { onFiltersChange(filters.copy(quality = it)) },
                        size = NuvioSegmentedSize.Compact,
                        fillWidth = true,
                        containerColor = container,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        NuvioSegmentedControl(
                            segments = segments,
                            selected = filters.quality,
                            onSelect = { onFiltersChange(filters.copy(quality = it)) },
                            size = NuvioSegmentedSize.Compact,
                            containerColor = container,
                        )
                    }
                }
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
            }
        }
        if (filters.isActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (resultCount != null) {
                        NuvioSectionLabel(
                            stringResource(Res.string.source_filter_result_count, resultCount, availability.total).uppercase(),
                        )
                    }
                }
                NuvioActionLabel(
                    text = stringResource(Res.string.source_filter_clear),
                    onClick = { onFiltersChange(SourceResultsFilters()) },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("source-filter-clear").padding(vertical = 6.dp),
                )
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
    Chip(
        label = text,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        showSelectedCheck = true,
    )
}
