package com.nuvio.app.features.watchlater

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeSynopsis(item: MetaPreview, content: @Composable () -> Unit) {
    if (!isDesktop || item.watchLaterEpisode == null) { content(); return }

    // Mouse hover already keeps the synopsis visible while reading. Persistent tooltips
    // ignore pointer exit, leaving the old card's synopsis over navigation or other content.
    val state = rememberTooltipState(isPersistent = false)
    var description by remember(item.id, item.type, item.description) { mutableStateOf(item.description) }
    var lookupFinished by remember(item.id, item.type, item.description) { mutableStateOf(false) }
    LaunchedEffect(state.isVisible, item.id, item.type) {
        if (state.isVisible && description.isNullOrBlank() && !lookupFinished) {
            description = try { withTimeoutOrNull(5_000L) { MetaDetailsRepository.fetch(item.type, item.id)?.description } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            lookupFinished = true
        }
    }

    val colors = MaterialTheme.nuvio.colors
    val synopsis = readableSynopsis(description).ifBlank {
        if (lookupFinished) "Synopsis unavailable" else "Loading synopsis…"
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = state,
        focusable = false,
        tooltip = {
            Surface(
                modifier = Modifier.width(360.dp),
                shape = MaterialTheme.nuvio.shapes.compactCard,
                color = colors.surfacePopover,
                border = BorderStroke(NuvioTokens.Border.hairline, colors.borderDefault),
                shadowElevation = NuvioTokens.Elevation.overlay,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Synopsis", style = MaterialTheme.typography.titleSmall, color = colors.accent)
                    Text(
                        synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        lineHeight = 20.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { content() }
}
