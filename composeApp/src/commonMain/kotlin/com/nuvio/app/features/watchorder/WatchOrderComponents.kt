package com.nuvio.app.features.watchorder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.NuvioTag
import com.nuvio.app.core.ui.NuvioTagStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.successOnTint
import com.nuvio.app.core.ui.tabular
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

/** How far the order can be trusted: reviewed (check), partial (warning) or suggested (info). */
@Composable
internal fun WatchOrderConfidenceBadge(confidence: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    NuvioInfoBadge(
        text = watchOrderConfidenceLabel(confidence),
        modifier = modifier,
        icon = when (confidence) {
            "verified" -> Icons.Rounded.CheckCircle
            "partial" -> Icons.Rounded.WarningAmber
            else -> Icons.Rounded.Info
        },
        iconTint = when (confidence) {
            "verified" -> colors.success
            "partial" -> colors.warning
            else -> colors.textMuted
        },
        contentColor = colors.textSecondary,
    )
}

/**
 * One installment in a watch-order group: its position, title, kind and status. The row's
 * container (group or lazy group segment) and the dividers belong to the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchOrderStepRow(
    step: AnimeWatchOrderStep,
    position: Int,
    isCurrent: Boolean,
    isCompleted: Boolean,
    onFindTitle: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val canFind = !isCurrent && onFindTitle != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (isCurrent) Modifier.background(tokens.colors.accent.copy(alpha = tokens.opacity.subtle)) else Modifier,
            )
            .clickable(enabled = canFind, role = Role.Button) { onFindTitle?.invoke(step.title) }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioIconChip(
            tone = when {
                isCurrent -> NuvioIconChipTone.AccentSolid
                isCompleted -> NuvioIconChipTone.Neutral
                else -> NuvioIconChipTone.Accent
            },
        ) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.labelLarge.tabular,
                fontWeight = FontWeight.Bold,
                // A watched installment keeps a quiet number; the chip's own colour handles the rest.
                color = if (isCompleted && !isCurrent) tokens.colors.textMuted else LocalContentColor.current,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isCompleted && !isCurrent) tokens.colors.textSecondary else tokens.colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(if (step.kind == "movie") Res.string.watch_order_movie else Res.string.watch_order_series),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                )
                if (isCompleted) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.successOnTint,
                            modifier = Modifier.size(13.dp))
                        Text(
                            text = stringResource(Res.string.watch_order_watched),
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            color = tokens.colors.successOnTint,
                        )
                    }
                }
                if (isCurrent) {
                    NuvioTag(stringResource(Res.string.watch_order_current), style = NuvioTagStyle.Accent, height = 20.dp)
                }
                if (!step.required) {
                    NuvioTag(stringResource(Res.string.watch_order_optional), style = NuvioTagStyle.Outline, height = 20.dp)
                }
            }
        }
        if (canFind) {
            NuvioSquareIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(Res.string.watch_order_find_in_starlit),
                onClick = { onFindTitle?.invoke(step.title) },
                style = NuvioSquareIconButtonStyle.Ghost,
            )
        } else {
            // Keeps titles aligned with the rows that do have a search button.
            Box(Modifier.size(40.dp))
        }
    }
}
