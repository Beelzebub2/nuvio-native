package com.nuvio.app.features.watchlater

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.formatRuntimeFromMinutes
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episode_hover_empty
import nuvio.composeapp.generated.resources.episode_hover_loading
import org.jetbrains.compose.resources.stringResource

internal fun readableSynopsis(value: String?): String = value.orEmpty()
    .replace(Regex("(?i)<br\\s*/?>"), " ")
    .replace(Regex("<[^>]*>"), " ")
    .replace("&nbsp;", " ", ignoreCase = true)
    .replace("&amp;", "&", ignoreCase = true)
    .replace("&quot;", "\"", ignoreCase = true)
    .replace("&#39;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(1600)

/**
 * Desktop hover card for an episode. It shows the episode's own overview: the item's
 * description belongs to the series, so it is never used here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeSynopsis(item: MetaPreview, content: @Composable () -> Unit) {
    val episode = item.watchLaterEpisode
    if (!isDesktop || episode == null) { content(); return }
    // Mouse hover already keeps the card visible while reading. Persistent tooltips
    // ignore pointer exit, leaving the old card over navigation or other content.
    val state = rememberTooltipState(isPersistent = false)
    var video by remember(item.id, item.type, episode) { mutableStateOf<MetaVideo?>(null) }
    // Only look the episode up when the saved preview has no overview of its own.
    var lookupFinished by remember(item.id, item.type, episode) { mutableStateOf(!episode.overview.isNullOrBlank()) }
    LaunchedEffect(state.isVisible, item.id, item.type, episode) {
        if (state.isVisible && !lookupFinished) {
            video = try {
                withTimeoutOrNull(5_000L) {
                    MetaDetailsRepository.fetch(item.type, item.id)?.videos
                        ?.firstOrNull { it.matchesWatchLaterEpisode(episode) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            lookupFinished = true
        }
    }
    val overview = readableSynopsis(episode.overview?.takeIf { it.isNotBlank() } ?: video?.overview)
    val gapPx = with(LocalDensity.current) { NuvioTokens.Space.s12.roundToPx() }
    val edgePx = with(LocalDensity.current) { NuvioTokens.Space.s8.roundToPx() }
    TooltipBox(
        positionProvider = remember(gapPx, edgePx) { EpisodeHoverCardPositionProvider(gapPx, edgePx) },
        state = state,
        focusable = false,
        tooltip = {
            EpisodeHoverCard(
                showName = item.name,
                episode = episode,
                video = video,
                overview = overview.ifBlank {
                    stringResource(if (lookupFinished) Res.string.episode_hover_empty else Res.string.episode_hover_loading)
                },
            )
        },
    ) { content() }
}

@Composable
private fun EpisodeHoverCard(
    showName: String,
    episode: WatchLaterEpisode,
    video: MetaVideo?,
    overview: String,
) {
    val colors = MaterialTheme.nuvio.colors
    val meta = listOfNotNull(
        video?.runtime?.takeIf { it > 0 }?.let(::formatRuntimeFromMinutes),
        video?.released?.let(::formatReleaseDateForDisplay),
    ).joinToString(" · ")
    Surface(
        modifier = Modifier.width(340.dp),
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = colors.surfacePopover,
        border = BorderStroke(NuvioTokens.Border.thin, colors.borderDefault),
        shadowElevation = NuvioTokens.Elevation.overlay,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = showName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                localizedSeasonEpisodeCode(episode.seasonNumber, episode.episodeNumber)?.let { code ->
                    Text(
                        text = code,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary.copy(alpha = 0.85f),
                lineHeight = 22.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Opens beside the anchor (right, else left) so the card never covers what it describes. */
private class EpisodeHoverCardPositionProvider(
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = anchorBounds.right + gapPx
        val left = anchorBounds.left - gapPx - popupContentSize.width
        val x = when {
            right + popupContentSize.width <= windowSize.width - edgePx -> right
            left >= edgePx -> left
            else -> (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(edgePx)
        }
        val maxY = (windowSize.height - popupContentSize.height - edgePx).coerceAtLeast(edgePx)
        return IntOffset(x, anchorBounds.top.coerceIn(edgePx, maxY))
    }
}
