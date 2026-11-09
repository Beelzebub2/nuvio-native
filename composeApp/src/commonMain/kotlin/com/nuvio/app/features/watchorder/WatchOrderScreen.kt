package com.nuvio.app.features.watchorder

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.navigation.WatchOrderRoute
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Independent navigation destination with lazy rows for large franchises.
 * Nothing here changes playback, sources, or account watch progress. */
@Composable
internal fun WatchOrderScreen(
    route: WatchOrderRoute,
    onBack: () -> Unit,
    onFindTitle: (String) -> Unit,
) {
    var mode by rememberSaveable(route.malId) { mutableStateOf("recommended") }
    var includeOptional by rememberSaveable(route.malId) { mutableStateOf(false) }
    var retryKey by remember(route.malId, mode) { mutableIntStateOf(0) }
    var loading by remember(route.malId, mode) { mutableStateOf(true) }
    var order by remember(route.malId, mode) { mutableStateOf<AnimeWatchOrder?>(null) }
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val completed = remember(watched.items, route.malId, route.currentTitleCompleted) {
        completedMalIdsFromWatched(watched.items) +
            if (route.currentTitleCompleted) setOf(route.malId) else emptySet()
    }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) { WatchedRepository.ensureLoaded() }
    LaunchedEffect(route.malId, mode, retryKey) {
        loading = true
        try {
            order = AnimeWatchOrderRepository.load(
                malId = route.malId,
                mode = mode,
                forceRefresh = retryKey > 0,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            order = null
        } finally {
            loading = false
        }
    }

    NuvioScreen {
        item(key = "watch-order-header") {
            CenteredWatchOrderContent {
                NuvioScreenHeader(
                    title = stringResource(Res.string.watch_order_title),
                    onBack = onBack,
                    includeStatusBarPadding = false,
                )
                if (route.sourceTitle.isNotBlank()) {
                    Text(
                        route.sourceTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "watch-order-filters") {
            CenteredWatchOrderContent {
                Text(
                    text = stringResource(Res.string.watch_order_choose_mode),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "recommended" to stringResource(Res.string.watch_order_recommended),
                        "release" to stringResource(Res.string.watch_order_release),
                        "chronological" to stringResource(Res.string.watch_order_chronological),
                    ).forEach { (value, title) ->
                        FilterChip(
                            selected = mode == value,
                            onClick = { mode = value },
                            label = { Text(title) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = includeOptional,
                            role = Role.Switch,
                            onValueChange = { includeOptional = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.watch_order_include_optional),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(checked = includeOptional, onCheckedChange = null)
                }
            }
        }
        if (loading) {
            item(key = "watch-order-loading") {
                CenteredWatchOrderContent {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(Res.string.watch_order_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val result = order
            if (result == null) {
                item(key = "watch-order-error") {
                    CenteredWatchOrderContent {
                        Text(
                            text = if (mode == "chronological") {
                                stringResource(Res.string.watch_order_chronology_unverified)
                            } else stringResource(Res.string.watch_order_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { retryKey++ }) {
                                Text(stringResource(Res.string.action_retry))
                            }
                            if (mode != "recommended") {
                                TextButton(onClick = { mode = "recommended" }) {
                                    Text(stringResource(Res.string.watch_order_recommended))
                                }
                            }
                        }
                    }
                }
            } else {
                val steps = result.steps.filter {
                    it.required || includeOptional || it.malId == route.malId
                }
                item(key = "watch-order-explanation") {
                    CenteredWatchOrderContent {
                        Text(
                            watchOrderConfidenceLabel(result.confidence),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(Res.string.watch_order_summary, steps.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(Res.string.watch_order_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val next = if (route.currentTitleCompleted) {
                    result.nextUnwatchedAfter(
                        currentMalId = route.malId,
                        includeOptional = includeOptional,
                        completedMalIds = completed,
                        today = CurrentDateProvider.todayIsoDate(),
                    )
                } else null
                if (next != null) {
                    item(key = "watch-order-next") {
                        CenteredWatchOrderContent {
                            Text(
                                text = stringResource(Res.string.watch_order_next),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = next.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { onFindTitle(next.title) }) {
                                Text(stringResource(Res.string.watch_order_find_in_starlit))
                            }
                        }
                    }
                }
                itemsIndexed(
                    items = steps,
                    key = { _, step -> step.malId },
                    contentType = { _, _ -> "watch-order-step" },
                ) { position, step ->
                    CenteredWatchOrderContent {
                        WatchOrderStepRow(
                            step = step,
                            position = position + 1,
                            isCurrent = step.malId == route.malId,
                            isCompleted = step.malId in completed,
                            onFindTitle = onFindTitle,
                        )
                    }
                }
                item(key = "watch-order-footer") {
                    CenteredWatchOrderContent {
                        Text(
                            stringResource(Res.string.watch_order_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { uriHandler.openUri("https://junban.moe") }) {
                            Text(stringResource(Res.string.watch_order_attribution))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredWatchOrderContent(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = { content() },
        )
    }
}
