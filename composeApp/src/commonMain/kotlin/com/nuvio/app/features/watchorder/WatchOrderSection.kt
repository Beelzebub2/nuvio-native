package com.nuvio.app.features.watchorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
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
    val tokens = MaterialTheme.nuvio

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
        val loaded = order
        val required = loaded?.steps?.filter { it.required }.orEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(Res.string.watch_order_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.nuvioTypeScale.titleMd,
                color = tokens.colors.textPrimary,
            )
            if (!loading && loaded != null) {
                Text(
                    text = stringResource(Res.string.watch_order_summary, required.size),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                )
            }
        }
        if (loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                NuvioLoadingIndicator(Modifier.size(18.dp), color = tokens.colors.accent, size = 18.dp)
                Text(
                    text = stringResource(Res.string.watch_order_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
            return@Column
        }
        if (loaded == null) {
            Text(
                text = if (currentMalId == null) stringResource(Res.string.watch_order_no_mapping)
                    else stringResource(Res.string.watch_order_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            NuvioPillButton(
                text = stringResource(Res.string.action_retry),
                onClick = { retryKey++ },
                icon = Icons.Rounded.Refresh,
            )
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WatchOrderConfidenceBadge(loaded.confidence, Modifier.weight(1f, fill = false))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                val malId = currentMalId
                if (malId != null && onOpenFullOrder != null) {
                    NuvioActionLabel(
                        text = stringResource(Res.string.watch_order_view_all),
                        onClick = { onOpenFullOrder(malId, currentTitleCompleted) },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }
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
            WatchOrderNextUp(title = next.title, onFindTitle = onFindTitle)
        }
        val visible = watchOrderPreviewWindow(
            steps = required,
            currentMalId = currentMalId,
            limit = 3,
        )
        if (visible.isNotEmpty()) {
            NuvioGroup {
                visible.forEachIndexed { index, entry ->
                    if (index > 0) NuvioGroupDivider()
                    WatchOrderStepRow(
                        step = entry,
                        position = required.indexOf(entry) + 1,
                        isCurrent = entry.malId == currentMalId,
                        isCompleted = entry.malId in completed,
                        onFindTitle = onFindTitle,
                    )
                }
            }
        }
        if (onOpenFullOrder == null) {
            Text(
                text = stringResource(Res.string.watch_order_disclaimer),
                style = MaterialTheme.nuvioTypeScale.labelSm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = tokens.colors.textMuted,
            )
        }
    }
}

/** "Next in watch order" with the title and a Find in Starlit action. */
@Composable
internal fun WatchOrderNextUp(title: String, onFindTitle: ((String) -> Unit)?) {
    val tokens = MaterialTheme.nuvio
    NuvioGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NuvioSectionLabel(stringResource(Res.string.watch_order_next).uppercase())
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onFindTitle != null) {
                NuvioPillButton(
                    text = stringResource(Res.string.watch_order_find_in_starlit),
                    onClick = { onFindTitle(title) },
                    style = NuvioPillButtonStyle.Primary,
                    icon = Icons.Rounded.Search,
                )
            }
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
