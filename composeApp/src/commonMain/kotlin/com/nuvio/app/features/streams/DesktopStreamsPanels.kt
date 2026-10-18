package com.nuvio.app.features.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_best_match
import nuvio.composeapp.generated.resources.streams_best_match_auto_play
import nuvio.composeapp.generated.resources.streams_best_match_highest_quality
import nuvio.composeapp.generated.resources.streams_best_match_ready
import nuvio.composeapp.generated.resources.streams_choose_source
import nuvio.composeapp.generated.resources.streams_episode_label
import nuvio.composeapp.generated.resources.streams_quality_all
import nuvio.composeapp.generated.resources.streams_quality_other
import nuvio.composeapp.generated.resources.streams_searching
import nuvio.composeapp.generated.resources.streams_sources_summary
import nuvio.composeapp.generated.resources.streams_sources_summary_searching
import org.jetbrains.compose.resources.stringResource

/** Left column of the desktop sources page: what is about to play and where it resumes. */
@Composable
internal fun DesktopStreamsInfoPanel(
    isEpisode: Boolean,
    title: String,
    logo: String?,
    artwork: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    var logoFailed by remember(logo) { mutableStateOf(false) }
    val hasResume = (resumePositionMs != null && resumePositionMs > 0L) ||
        (resumeProgressFraction != null && resumeProgressFraction > 0f)
    Column(
        modifier = modifier.widthIn(max = 400.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
                .background(colors.surfaceCard),
        ) {
            if (artwork != null) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            resumeProgressFraction?.takeIf { it > 0f }?.let { progress ->
                NuvioProgressBar(
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    height = 4.dp,
                    trackColor = Color.White.copy(alpha = 0.20f),
                    fillColor = colors.accent,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            val logoUrl = logo?.takeIf { it.isNotBlank() && !logoFailed }
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = title,
                    modifier = Modifier.height(44.dp).widthIn(max = 240.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isEpisode && seasonNumber != null && episodeNumber != null) {
                Text(
                    text = stringResource(Res.string.streams_episode_label, seasonNumber, episodeNumber).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.textMuted,
                    maxLines = 1,
                )
                episodeTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (hasResume) {
            ResumeBanner(positionMs = resumePositionMs, progressFraction = resumeProgressFraction)
        }
    }
}

/** Header, best match, quality filter and add-on status above the desktop source list. */
@Composable
internal fun DesktopSourcesHeader(
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    qualityFilter: StreamQuality?,
    onQualityFilterChange: (StreamQuality?) -> Unit,
    best: BestMatch?,
    onPlay: (StreamItem) -> Unit,
    onStreamSecondaryClick: (StreamItem, androidx.compose.ui.geometry.Offset) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val streams = uiState.allStreams
    val searching = uiState.groups.count { it.isLoading }
    val badgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.streams_choose_source),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = NuvioTokens.Type.headline),
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            if (uiState.groups.isNotEmpty()) {
                Text(
                    text = if (searching > 0) {
                        stringResource(Res.string.streams_sources_summary_searching, streams.size, uiState.groups.size, searching)
                    } else {
                        stringResource(Res.string.streams_sources_summary, streams.size, uiState.groups.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        best?.let { match ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.6f), RoundedCornerShape(NuvioTokens.Radius.xl))
                    .border(NuvioTokens.Border.thin, colors.accent.copy(alpha = 0.7f), RoundedCornerShape(NuvioTokens.Radius.xl))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.streams_best_match).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                    Text(
                        text = stringResource(
                            when (match.reason) {
                                BestMatchReason.AutoPlayRules -> Res.string.streams_best_match_auto_play
                                BestMatchReason.ReadyToPlay -> Res.string.streams_best_match_ready
                                BestMatchReason.HighestQuality -> Res.string.streams_best_match_highest_quality
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StreamCard(
                    stream = match.stream,
                    enabled = true,
                    appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                    showFileSizeBadges = badgeSettings.showFileSizeBadges,
                    showAddonLogo = badgeSettings.showAddonLogo,
                    badgePlacement = badgeSettings.badgePlacement,
                    onClick = { onPlay(match.stream) },
                    onSecondaryClick = { position -> onStreamSecondaryClick(match.stream, position) },
                )
            }
        }

        if (streams.isNotEmpty()) {
            val counts = streams.groupingBy { it.quality() }.eachCount()
            val otherLabel = stringResource(Res.string.streams_quality_other)
            val segments = buildList {
                add(NuvioSegment<StreamQuality?>(null, stringResource(Res.string.streams_quality_all), count = streams.size.toString()))
                StreamQuality.entries.sortedByDescending { it.rank }.forEach { quality ->
                    counts[quality]?.let { add(NuvioSegment<StreamQuality?>(quality, quality.label(otherLabel), count = it.toString())) }
                }
            }
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                NuvioSegmentedControl(
                    segments = segments,
                    selected = qualityFilter,
                    onSelect = onQualityFilterChange,
                    size = NuvioSegmentedSize.Compact,
                )
            }
        }

        if (uiState.groups.size > 1 || searching > 0) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                uiState.groups.forEach { group ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    when {
                                        group.isLoading -> colors.accent
                                        group.error != null -> colors.danger
                                        group.streams.isEmpty() -> colors.textDisabled
                                        else -> colors.success
                                    },
                                    RoundedCornerShape(NuvioTokens.Radius.full),
                                ),
                        )
                        Text(group.addonName, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
                        Text(
                            text = if (group.isLoading) stringResource(Res.string.streams_searching) else group.streams.size.toString(),
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
