package com.nuvio.app.features.watchorder

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.isLikelyMyAnimeListAnime
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WatchOrderSection(
    meta: MetaDetails,
    currentTitleCompleted: Boolean,
    onFindTitle: ((String) -> Unit)?,
) {
    if (!isLikelyMyAnimeListAnime(meta)) return
    var mode by rememberSaveable(meta.id) { mutableStateOf("recommended") }
    var includeOptional by rememberSaveable(meta.id) { mutableStateOf(false) }
    var currentMalId by remember(meta.id) { mutableStateOf<Int?>(null) }
    var order by remember(meta.id, mode) { mutableStateOf<AnimeWatchOrder?>(null) }
    var loading by remember(meta.id, mode) { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(meta.id, meta.imdbId, meta.links, mode) {
        loading = true
        order = null
        val malId = AnimeWatchOrderRepository.resolveMalId(meta)
        currentMalId = malId
        if (malId != null) order = AnimeWatchOrderRepository.load(malId, mode)
        loading = false
    }
    // No forced empty section for titles without a trustworthy MAL mapping.
    val result = order
    if (result == null && !loading && currentMalId == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.watch_order_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (loading) {
            Text(stringResource(Res.string.watch_order_loading),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (result == null) {
            Text(stringResource(Res.string.watch_order_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { mode = "recommended" }) {
                Text(stringResource(Res.string.watch_order_recommended))
            }
            return@Column
        }
        Text(
            text = if (result.verified) stringResource(Res.string.watch_order_verified)
                else stringResource(Res.string.watch_order_inferred),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "recommended" to stringResource(Res.string.watch_order_recommended),
                "release" to stringResource(Res.string.watch_order_release),
                "chronological" to stringResource(Res.string.watch_order_chronological),
            ).forEach { (value, label) ->
                FilterChip(
                    selected = mode == value,
                    onClick = { mode = value },
                    label = { Text(label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.watch_order_include_optional),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            Switch(checked = includeOptional, onCheckedChange = { includeOptional = it })
        }
        val visible = result.steps.filter { it.required || includeOptional }
        visible.forEachIndexed { index, entry ->
            val isCurrent = entry.malId == currentMalId
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = (if (entry.kind == "movie") stringResource(Res.string.watch_order_movie)
                                else stringResource(Res.string.watch_order_series)) +
                                if (!entry.required) " · " + stringResource(Res.string.watch_order_optional) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isCurrent) {
                        Text(
                            if (currentTitleCompleted) stringResource(Res.string.watch_order_watched)
                                else stringResource(Res.string.watch_order_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        val currentId = currentMalId
        val next = if (currentTitleCompleted && currentId != null)
            result.nextAfter(currentId, includeOptional) else null
        if (next != null) {
            Text(stringResource(Res.string.watch_order_next) + ": " + next.title,
                style = MaterialTheme.typography.titleSmall)
            if (onFindTitle != null) {
                TextButton(onClick = { onFindTitle(next.title) }) {
                    Text(stringResource(Res.string.watch_order_find_in_starlit))
                }
            }
        }
        Text(
            stringResource(Res.string.watch_order_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { uriHandler.openUri("https://junban.moe") }) {
            Text(stringResource(Res.string.watch_order_attribution))
        }
    }
}
