package com.nuvio.app.features.watchorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.isLikelyMyAnimeListAnime
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A compact, actionable preview inside the existing detail Overview. Long
 * franchises belong on the dedicated, lazy full-order screen instead.
 */
@Composable
internal fun WatchOrderSection(
    meta: MetaDetails,
    currentTitleCompleted: Boolean,
    onFindTitle: ((String) -> Unit)?,
    onOpenFullOrder: ((Int, Boolean) -> Unit)?,
) {
    if (!isLikelyMyAnimeListAnime(meta)) return

    var retryKey by remember(meta.id) { mutableIntStateOf(0) }
    var currentMalId by remember(meta.id) { mutableStateOf<Int?>(null) }
    var order by remember(meta.id) { mutableStateOf<AnimeWatchOrder?>(null) }
    var loading by remember(meta.id) { mutableStateOf(true) }
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val completed = remember(watched.items, currentMalId, currentTitleCompleted) {
        completedMalIdsFromWatched(watched.items) +
            if (currentTitleCompleted) setOfNotNull(currentMalId) else emptySet()
    }

    LaunchedEffect(meta.id, meta.imdbId, meta.links, retryKey) {
        loading = true
        try {
            val malId = AnimeWatchOrderRepository.resolveMalId(meta)
            currentMalId = malId
            order = malId?.let {
                AnimeWatchOrderRepository.load(it, "recommended", forceRefresh = retryKey > 0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            order = null
        } finally {
            loading = false
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.watch_order_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val malId = currentMalId
            if (malId != null && order != null && onOpenFullOrder != null) {
                TextButton(onClick = { onOpenFullOrder(malId, currentTitleCompleted) }) {
                    Text(stringResource(Res.string.watch_order_view_all))
                }
            }
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(Res.string.watch_order_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val loaded = order
        if (loaded == null) {
            Text(
                text = if (currentMalId == null) stringResource(Res.string.watch_order_no_mapping)
                    else stringResource(Res.string.watch_order_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { retryKey++ }) {
                Text(stringResource(Res.string.action_retry))
            }
            return@Column
        }
        Text(
            text = stringResource(Res.string.watch_order_summary, loaded.steps.count { it.required }) +
                " · " + watchOrderConfidenceLabel(loaded.confidence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val next = if (currentTitleCompleted) {
            currentMalId?.let {
                loaded.nextUnwatchedAfter(
                    currentMalId = it,
                    includeOptional = false,
                    completedMalIds = completed,
                    today = CurrentDateProvider.todayIsoDate(),
                )
            }
        } else null
        if (next != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(Res.string.watch_order_next),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = next.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onFindTitle != null) {
                    FilledTonalButton(onClick = { onFindTitle(next.title) }) {
                        Text(stringResource(Res.string.watch_order_find_in_starlit))
                    }
                }
            }
        }
        val visible = watchOrderPreviewWindow(
            steps = loaded.steps.filter { it.required },
            currentMalId = currentMalId,
            limit = 3,
        )
        visible.forEachIndexed { index, entry ->
            WatchOrderStepRow(
                step = entry,
                position = loaded.steps.filter { it.required }.indexOf(entry) + 1,
                isCurrent = entry.malId == currentMalId,
                isCompleted = entry.malId in completed,
                onFindTitle = onFindTitle,
            )
        }
        if (onOpenFullOrder == null) {
            Text(
                text = stringResource(Res.string.watch_order_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun watchOrderPreviewWindow(
    steps: List<AnimeWatchOrderStep>,
    currentMalId: Int?,
    limit: Int,
): List<AnimeWatchOrderStep> {
    if (limit <= 0 || steps.size <= limit) return steps.take(limit.coerceAtLeast(0))
    val index = steps.indexOfFirst { it.malId == currentMalId }.coerceAtLeast(0)
    val start = (index - 1).coerceIn(0, steps.size - limit)
    return steps.drop(start).take(limit)
}
