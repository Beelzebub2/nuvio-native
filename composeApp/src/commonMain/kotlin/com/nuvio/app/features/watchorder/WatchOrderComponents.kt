package com.nuvio.app.features.watchorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watched.WatchedItem
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Only explicit full-title watched markers count; episodes do not prove a
 * franchise installment is completed. Never infer from title similarities. */
internal fun completedMalIdsFromWatched(items: List<WatchedItem>): Set<Int> =
    items.asSequence()
        .filter { it.season == null && it.episode == null }
        .mapNotNull {
            when {
                it.id.startsWith("mal:", ignoreCase = true) ->
                    it.id.substringAfter(':').toIntOrNull()
                it.trackingProviderId.equals("myanimelist", ignoreCase = true) ->
                    it.trackingProviderItemId?.toIntOrNull()
                else -> null
            }
        }
        .filter { it > 0 }
        .toSet()

@Composable
internal fun watchOrderConfidenceLabel(confidence: String): String = when (confidence) {
    "verified" -> stringResource(Res.string.watch_order_verified)
    "partial" -> stringResource(Res.string.watch_order_partial)
    else -> stringResource(Res.string.watch_order_inferred)
}

@Composable
internal fun WatchOrderStepRow(
    step: AnimeWatchOrderStep,
    position: Int,
    isCurrent: Boolean,
    isCompleted: Boolean,
    onFindTitle: ((String) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (isCurrent) colors.secondaryContainer.copy(alpha = 0.65f)
        else colors.surfaceVariant.copy(alpha = 0.4f)
    Surface(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
        onClick = { onFindTitle?.invoke(step.title) },
        enabled = !isCurrent && onFindTitle != null,
        color = background,
        shape = MaterialTheme.shapes.medium,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 440.dp
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 9.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (step.kind == "movie") Icons.Rounded.Movie else Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = if (isCompact) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val category = if (step.kind == "movie") {
                        stringResource(Res.string.watch_order_movie)
                    } else stringResource(Res.string.watch_order_series)
                    val optional = if (!step.required) {
                        stringResource(Res.string.watch_order_optional)
                    } else null
                    val status = if (!isCompact) null else when {
                        isCompleted -> stringResource(Res.string.watch_order_watched)
                        isCurrent -> stringResource(Res.string.watch_order_current)
                        else -> null
                    }
                    Text(
                        text = listOfNotNull(category, optional, status).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                when {
                    isCompleted && !isCompact -> Text(
                        text = stringResource(Res.string.watch_order_watched),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                    )
                    isCurrent && !isCompact -> Text(
                        text = stringResource(Res.string.watch_order_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                    )
                    !isCurrent && onFindTitle != null -> Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(Res.string.watch_order_find_in_starlit),
                        tint = colors.primary,
                    )
                }
            }
        }
    }
}
