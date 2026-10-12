package com.nuvio.app.features.watchlater

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeSynopsis(item: MetaPreview, content: @Composable () -> Unit) {
    if (!isDesktop || item.watchLaterEpisode == null) { content(); return }
    val state = rememberTooltipState(isPersistent = true)
    var description by remember(item.id, item.description) { mutableStateOf(item.description) }
    LaunchedEffect(state.isVisible, item.id) {
        if (state.isVisible && description.isNullOrBlank()) {
            description = try { withTimeoutOrNull(5_000L) { MetaDetailsRepository.fetch(item.type, item.id)?.description } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = state, focusable = false,
        tooltip = { PlainTooltip(modifier = Modifier.widthIn(max = 420.dp)) {
            Text(description?.replace(Regex("<[^>]*>"), " ")?.trim()?.takeIf { it.isNotBlank() } ?: "Synopsis unavailable", maxLines = 10)
        } }) { content() }
}
