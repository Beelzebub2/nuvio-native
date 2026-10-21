package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.details.MetaVideo

internal fun episodeArtworkUrl(thumbnail: String?): String? =
    thumbnail?.trim()?.takeIf { it.isNotEmpty() }

/** Series artwork is not an episode still; use an honest fallback, also on load failure. */
@Composable
internal fun EpisodeArtwork(
    video: MetaVideo,
    blur: Boolean,
    modifier: Modifier = Modifier,
) {
    val image = episodeArtworkUrl(video.thumbnail)
    var failed by remember(image) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (image == null || failed) {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surface,
                ))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    localizedSeasonEpisodeCode(video.season, video.episode).orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            NuvioAsyncImage(
                model = image,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize().then(if (blur) Modifier.blur(18.dp) else Modifier),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        }
    }
}
