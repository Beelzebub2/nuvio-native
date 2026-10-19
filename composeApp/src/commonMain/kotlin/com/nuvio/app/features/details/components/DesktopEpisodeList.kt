package com.nuvio.app.features.details.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioAnimatedWatchedBadge
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioContextMenuAnchorHolder
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.bottomStartInRoot
import com.nuvio.app.core.ui.contextMenuTrigger
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.EpisodeRatingsVisibility
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.formatRuntimeFromMinutes
import com.nuvio.app.features.details.isReleasedBy
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchlater.WatchLaterRepository
import com.nuvio.app.features.watchlater.matchesWatchLaterPreview
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_resume
import nuvio.composeapp.generated.resources.detail_action_watched
import nuvio.composeapp.generated.resources.detail_minutes_left
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.episode_status_up_next
import nuvio.composeapp.generated.resources.episodes_hide_spoilers
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import nuvio.composeapp.generated.resources.episodes_summary_aired
import nuvio.composeapp.generated.resources.episodes_summary_next
import nuvio.composeapp.generated.resources.episodes_summary_watched
import nuvio.composeapp.generated.resources.episodes_view_cards
import nuvio.composeapp.generated.resources.episodes_view_list
import nuvio.composeapp.generated.resources.source_imdb
import nuvio.composeapp.generated.resources.watch_later_remove_named
import nuvio.composeapp.generated.resources.watch_later_save_named
import org.jetbrains.compose.resources.stringResource

/** Watched / aired / next-air counts for the selected season, shown under the season control. */
internal data class SeasonSummary(
    val watched: Int,
    val total: Int,
    val aired: Int,
    val nextAirDate: String?,
)

internal fun seasonSummary(
    meta: MetaDetails,
    episodes: List<MetaVideo>,
    progressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    todayIsoDate: String,
): SeasonSummary {
    val watched = episodes.count { episode ->
        val videoId = buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            fallbackVideoId = episode.id,
        )
        progressByVideoId[videoId]?.isEffectivelyCompleted == true ||
            WatchingState.isEpisodeWatched(
                watchedKeys = watchedKeys,
                metaType = meta.type,
                metaId = meta.id,
                episode = episode,
            )
    }
    val unreleased = episodes.filterNot { it.isReleasedBy(todayIsoDate) }
    return SeasonSummary(
        watched = watched,
        total = episodes.size,
        aired = episodes.size - unreleased.size,
        nextAirDate = unreleased.mapNotNull { it.released?.takeIf(String::isNotBlank) }.minOrNull(),
    )
}

@Composable
private fun seasonLabel(season: Int): String =
    if (season <= 0) stringResource(Res.string.episodes_specials) else stringResource(Res.string.episodes_season, season)

/**
 * Desktop season header: seasons as a segmented control, the spoiler and layout toggles on
 * the right, and the season's progress underneath.
 */
@Composable
internal fun DesktopSeasonToolbar(
    seasons: List<Int>,
    groupedEpisodes: Map<Int, List<MetaVideo>>,
    currentSeason: Int,
    summary: SeasonSummary?,
    onSelect: (Int) -> Unit,
    onLongPress: ((Int) -> Unit)?,
) {
    val colors = MaterialTheme.nuvio.colors
    val settings by MetaScreenSettingsRepository.uiState.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (seasons.size > 1) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        NuvioSegmentedControl(
                            segments = seasons.map { season ->
                                NuvioSegment(
                                    key = season,
                                    label = seasonLabel(season),
                                    count = groupedEpisodes[season]?.size?.toString(),
                                )
                            },
                            selected = currentSeason,
                            onSelect = onSelect,
                            onSecondaryClick = onLongPress,
                        )
                    }
                }
            }
            NuvioCompactActionButton(
                label = stringResource(Res.string.episodes_hide_spoilers),
                icon = if (settings.blurUnwatchedEpisodes) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                onClick = { MetaScreenSettingsRepository.setBlurUnwatchedEpisodes(!settings.blurUnwatchedEpisodes) },
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
                selected = settings.blurUnwatchedEpisodes,
            )
            NuvioSegmentedControl(
                segments = listOf(
                    NuvioSegment(MetaEpisodeCardStyle.List, stringResource(Res.string.episodes_view_list)),
                    NuvioSegment(MetaEpisodeCardStyle.Horizontal, stringResource(Res.string.episodes_view_cards)),
                ),
                selected = settings.episodeCardStyle,
                onSelect = MetaScreenSettingsRepository::setEpisodeCardStyle,
                size = NuvioSegmentedSize.Dense,
            )
        }
        summary?.takeIf { it.total > 0 }?.let { info ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioProgressBar(
                    progress = info.watched.toFloat() / info.total,
                    modifier = Modifier.width(180.dp),
                    height = NuvioTokens.Space.s4,
                    trackColor = colors.textPrimary.copy(alpha = 0.10f),
                    fillColor = colors.accent,
                )
                val parts = buildList {
                    add(stringResource(Res.string.episodes_summary_watched, info.watched, info.total))
                    if (info.aired < info.total) add(stringResource(Res.string.episodes_summary_aired, info.aired))
                    info.nextAirDate?.let { add(stringResource(Res.string.episodes_summary_next, formatReleaseDateForDisplay(it))) }
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Desktop episode row: still, code and title, meta, two-line overview, and hover actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopEpisodeRow(
    video: MetaVideo,
    fallbackImage: String?,
    progressEntry: WatchProgressEntry?,
    imdbRating: Double?,
    isWatched: Boolean,
    episodeRatingsVisibility: EpisodeRatingsVisibility,
    blurUnwatchedEpisodes: Boolean,
    isUpNext: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    preview: MetaPreview? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val todayIsoDate = remember { CurrentDateProvider.todayIsoDate() }
    val upcoming = remember(video.released, video.available, todayIsoDate) { !video.isReleasedBy(todayIsoDate) }
    val visibleProgress = progressEntry?.takeIf { it.durationMs > 0L && !it.isCompleted }
    val ratingLabel = remember(imdbRating, episodeRatingsVisibility, isWatched) {
        imdbRating?.takeIf { it > 0.0 && episodeRatingsVisibility.showRating(isWatched) }?.let(::formatEpisodeRating)
    }
    val formattedDate = remember(video.released) { video.released?.let(::formatReleaseDateForDisplay) }
    val runtimeLabel = remember(video.runtime) { video.runtime?.takeIf { it > 0 }?.let(::formatRuntimeFromMinutes) }
    val imdbLabel = stringResource(Res.string.source_imdb)
    val metaLine = listOfNotNull(runtimeLabel, formattedDate, ratingLabel?.let { "$imdbLabel $it" }).joinToString(" · ")
    val rowShape = RoundedCornerShape(NuvioTokens.Radius.lg)
    var moreButtonCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val savedItems by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val savedForLater = preview != null && savedItems.any { it.matchesWatchLaterPreview(preview) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(
                when {
                    isUpNext -> colors.surface
                    hovered -> colors.overlayHover
                    else -> Color.Transparent
                },
            )
            .border(NuvioTokens.Border.thin, if (isUpNext) colors.borderSubtle else Color.Transparent, rowShape)
            .contextMenuTrigger(onLongPress)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = onClick != null || onLongPress != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongPress,
            )
            .hoverable(interactionSource)
            .padding(NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(200.dp)
                .height(112.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceCard),
        ) {
            val imageUrl = video.thumbnail ?: fallbackImage
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = video.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurUnwatchedEpisodes && !isWatched) Modifier.blur(18.dp) else Modifier)
                        .alpha(if (upcoming) 0.45f else 1f),
                    contentScale = ContentScale.Crop,
                )
            }
            if (upcoming && formattedDate != null) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                    Text(formattedDate, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
                }
            }
            if (runtimeLabel != null && !upcoming) {
                Text(
                    text = runtimeLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 10.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(NuvioTokens.Radius.xs))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                    maxLines = 1,
                )
            }
            NuvioAnimatedWatchedBadge(
                isVisible = isWatched,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
            visibleProgress?.let { entry ->
                NuvioProgressBar(
                    progress = entry.progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                    height = 3.dp,
                    trackColor = Color.White.copy(alpha = 0.22f),
                    fillColor = colors.accent,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                video.episode?.let { number ->
                    Text(
                        text = "E$number",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
                Text(
                    text = video.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (upcoming) colors.textSecondary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isUpNext) EpisodeStatusChip(stringResource(Res.string.episode_status_up_next))
            }
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            video.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hovered && !upcoming) {
                preview?.let { item ->
                    NuvioIconActionButton(
                        icon = if (savedForLater) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = stringResource(
                            if (savedForLater) Res.string.watch_later_remove_named else Res.string.watch_later_save_named,
                            video.title,
                        ),
                        onClick = { WatchLaterRepository.toggle(item) },
                        size = NuvioActionSize.Small,
                    )
                }
                if (onLongPress != null) {
                    NuvioIconActionButton(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(Res.string.details_actions_menu_label),
                        onClick = {
                            moreButtonCoordinates?.takeIf { it.isAttached }?.let {
                                NuvioContextMenuAnchorHolder.set(it.bottomStartInRoot())
                            }
                            onLongPress()
                        },
                        modifier = Modifier.onGloballyPositioned { moreButtonCoordinates = it },
                        size = NuvioActionSize.Small,
                    )
                }
            } else {
                val status = when {
                    upcoming -> null
                    isWatched -> stringResource(Res.string.detail_action_watched)
                    visibleProgress != null -> stringResource(
                        Res.string.detail_minutes_left,
                        ((visibleProgress.durationMs - visibleProgress.lastPositionMs) / 60_000L).toInt().coerceAtLeast(1),
                    )
                    else -> null
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                }
            }
            if ((hovered || isUpNext) && !upcoming && onClick != null) {
                NuvioCompactActionButton(
                    label = stringResource(if (visibleProgress != null) Res.string.action_resume else Res.string.action_play),
                    icon = Icons.Rounded.PlayArrow,
                    onClick = onClick,
                    tone = NuvioCompactActionTone.Primary,
                    size = NuvioActionSize.Small,
                )
            }
        }
        Spacer(Modifier.width(NuvioTokens.Space.s4))
    }
}

@Composable
internal fun EpisodeStatusChip(label: String) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = label.uppercase(),
        modifier = Modifier
            .background(colors.accent, RoundedCornerShape(NuvioTokens.Radius.full))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.onAccent,
        maxLines = 1,
    )
}
