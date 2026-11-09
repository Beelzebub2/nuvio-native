package com.nuvio.app.features.watchorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Cross-title recommendations are never a candidate for auto-next playback.
 * They appear only once the current movie or final available episode has ended,
 * and always go through Starlit's normal search after releasing the player. */
internal fun shouldOfferFranchiseContinuation(
    hasEnded: Boolean,
    hasPlaybackError: Boolean,
    hasNextEpisode: Boolean,
    isSeries: Boolean,
    currentEpisodeInMetadata: Boolean,
): Boolean = hasEnded && !hasPlaybackError && !hasNextEpisode &&
    (!isSeries || currentEpisodeInMetadata)

@Composable
internal fun BoxScope.PlayerWatchOrderSuggestion(
    meta: MetaDetails?,
    eligible: Boolean,
    profileId: Int,
    watchedItems: List<WatchedItem>,
    bottomPadding: Dp,
    endPadding: Dp,
    onFindTitle: (String) -> Unit,
) {
    // Keep the dismissal local to the current playback and profile, rather
    // than writing to the watched repository or modifying any MAL list.
    var dismissed by remember(meta?.id, profileId) { mutableStateOf(false) }
    var next by remember(meta?.id, profileId) { mutableStateOf<AnimeWatchOrderStep?>(null) }
    LaunchedEffect(eligible, meta?.id, profileId, watchedItems) {
        next = null
        if (!eligible || meta == null) {
            dismissed = false
            return@LaunchedEffect
        }
        try {
            val malId = AnimeWatchOrderRepository.resolveMalId(meta) ?: return@LaunchedEffect
            val order = AnimeWatchOrderRepository.load(malId, "recommended") ?: return@LaunchedEffect
            val completed = completedMalIdsFromWatched(watchedItems) + malId
            next = order.nextUnwatchedAfter(
                currentMalId = malId,
                includeOptional = false,
                completedMalIds = completed,
                today = CurrentDateProvider.todayIsoDate(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing metadata must never disrupt playback or block Back.
        }
    }
    val suggested = next
    if (!eligible || dismissed || suggested == null) return
    Surface(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = endPadding, bottom = bottomPadding + 12.dp)
            .widthIn(max = 340.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 5.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.watch_order_next),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = suggested.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { onFindTitle(suggested.title) }) {
                    Text(stringResource(Res.string.watch_order_find_in_starlit))
                }
                TextButton(onClick = { dismissed = true }) {
                    Text(stringResource(Res.string.watch_order_dismiss))
                }
            }
        }
    }
}
