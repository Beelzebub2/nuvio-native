package com.nuvio.app.features.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.components.HeroTrailerPlayerSurface
import com.nuvio.app.features.details.selectHeroTrailer
import com.nuvio.app.features.details.youtubePlaybackUrl
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay

private const val HoverTrailerFadeDurationMillis = 480

@Composable
internal fun HomePosterHoverTrailer(
    item: MetaPreview,
    soundEnabled: Boolean,
    startPositionSeconds: Int,
    modifier: Modifier = Modifier,
) {
    var playbackSource by remember(item.type, item.id) {
        mutableStateOf<TrailerPlaybackSource?>(null)
    }
    var trailerReady by remember(item.type, item.id) { mutableStateOf(false) }
    var trailerFinished by remember(item.type, item.id) { mutableStateOf(false) }
    val trailerAlpha by animateFloatAsState(
        targetValue = if (trailerReady && !trailerFinished) 1f else 0f,
        animationSpec = tween(durationMillis = HoverTrailerFadeDurationMillis),
        label = "poster_hover_trailer_alpha",
    )

    LaunchedEffect(item.type, item.id) {
        playbackSource = null
        trailerReady = false
        trailerFinished = false
        val meta = MetaDetailsRepository.peek(type = item.type, id = item.id)
            ?: MetaDetailsRepository.fetch(type = item.type, id = item.id)
        val trailer = selectHeroTrailer(meta?.trailers.orEmpty())
            ?: return@LaunchedEffect
        playbackSource = runCatching {
            TrailerPlaybackResolver.resolveFromYouTubeUrl(trailer.youtubePlaybackUrl())
        }.getOrNull()
    }

    LaunchedEffect(trailerFinished, playbackSource) {
        if (trailerFinished && playbackSource != null) {
            delay(HoverTrailerFadeDurationMillis.toLong())
            playbackSource = null
        }
    }

    playbackSource?.let { source ->
        HeroTrailerPlayerSurface(
            sourceUrl = source.videoUrl,
            sourceAudioUrl = source.audioUrl,
            playWhenReady = !trailerFinished,
            muted = !soundEnabled,
            startPositionMillis = startPositionSeconds.toLong() * 1_000L,
            fillFrame = true,
            modifier = modifier.graphicsLayer { alpha = trailerAlpha },
            onReady = {
                if (!trailerFinished) {
                    trailerReady = true
                }
            },
            onEnded = {
                trailerReady = false
                trailerFinished = true
            },
            onError = {
                trailerReady = false
                trailerFinished = true
            },
        )
    }
}
