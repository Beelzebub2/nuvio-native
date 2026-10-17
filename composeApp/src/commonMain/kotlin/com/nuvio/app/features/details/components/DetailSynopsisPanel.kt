package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedMediaStatusLabel
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.formatRuntimeForDisplay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.detail_synopsis_about
import nuvio.composeapp.generated.resources.detail_facts_studio
import nuvio.composeapp.generated.resources.details_certification
import nuvio.composeapp.generated.resources.details_director
import nuvio.composeapp.generated.resources.details_networks
import nuvio.composeapp.generated.resources.details_origin_country
import nuvio.composeapp.generated.resources.details_original_language
import nuvio.composeapp.generated.resources.details_release_info
import nuvio.composeapp.generated.resources.details_runtime
import nuvio.composeapp.generated.resources.details_status
import nuvio.composeapp.generated.resources.details_writer
import nuvio.composeapp.generated.resources.source_imdb
import org.jetbrains.compose.resources.stringResource

/**
 * Right-side panel with the full synopsis and title facts, opened from "More" on the
 * desktop hero. Esc, the close button or a click on the dimmed page closes it.
 */
@Composable
internal fun DetailSynopsisPanel(
    meta: MetaDetails,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val colors = tokens.colors
    val focusRequester = remember { FocusRequester() }
    val isSeriesLike = meta.type == "series" || meta.videos.any { it.season != null || it.episode != null }
    val facts = buildList {
        meta.status?.let { add(stringResource(Res.string.details_status) to localizedMediaStatusLabel(it, isSeriesLike)) }
        meta.releaseInfo?.let { add(stringResource(Res.string.details_release_info) to formatReleaseDateForDisplay(it)) }
        formatRuntimeForDisplay(meta.runtime)?.let { add(stringResource(Res.string.details_runtime) to it) }
        meta.ageRating?.let { add(stringResource(Res.string.details_certification) to it) }
        meta.productionCompanies.takeIf { it.isNotEmpty() }?.let { companies ->
            add(stringResource(Res.string.detail_facts_studio) to companies.take(2).joinToString { it.name })
        }
        meta.networks.takeIf { it.isNotEmpty() }?.let { networks ->
            add(stringResource(Res.string.details_networks) to networks.take(2).joinToString { it.name })
        }
        meta.director.takeIf { it.isNotEmpty() }?.let { add(stringResource(Res.string.details_director) to it.take(2).joinToString()) }
        meta.writer.takeIf { it.isNotEmpty() }?.let { add(stringResource(Res.string.details_writer) to it.take(2).joinToString()) }
        meta.country?.let { add(stringResource(Res.string.details_origin_country) to it) }
        meta.language?.let { add(stringResource(Res.string.details_original_language) to it.uppercase()) }
    }
    val ratings = buildList {
        meta.imdbRating?.takeIf { raw -> raw.toDoubleOrNull()?.let { it > 0.0 } == true }?.let {
            add(it to stringResource(Res.string.source_imdb))
        }
        meta.externalRatings
            .filterNot { it.source.equals("imdb", ignoreCase = true) }
            .take(3)
            .forEach { rating ->
                val value = if (rating.value % 1.0 == 0.0 && rating.value > 10) "${rating.value.toInt()}%" else rating.value.toString()
                add(value to rating.source.replaceFirstChar { it.uppercase() })
            }
    }

    Popup(
        popupPositionProvider = FullWindowPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.overlayScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .background(colors.surface)
                    .border(tokens.borders.thin, colors.borderSubtle)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(Res.string.detail_synopsis_about),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        Text(
                            text = meta.name,
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = NuvioTokens.Type.headline),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NuvioIconActionButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        onClick = onDismiss,
                        size = NuvioActionSize.Small,
                    )
                }
                meta.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    Text(
                        text = synopsis.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 25.sp,
                        color = colors.textPrimary.copy(alpha = 0.85f),
                    )
                }
                if (meta.genres.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        meta.genres.forEach { genre -> DetailGenreChip(genre) }
                    }
                }
                if (facts.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        maxItemsInEachRow = 2,
                    ) {
                        facts.forEach { (label, value) ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (ratings.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                        ratings.forEach { (value, source) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(colors.surfaceCard, RoundedCornerShape(NuvioTokens.Radius.lg))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                                Text(source, style = MaterialTheme.typography.labelMedium, color = colors.textMuted, maxLines = 1)
                            }
                        }
                    }
                }
                actions()
            }
        }
    }
}

@Composable
internal fun DetailGenreChip(label: String) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = label,
        modifier = Modifier
            .background(colors.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(NuvioTokens.Radius.full))
            .border(NuvioTokens.Border.thin, colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(NuvioTokens.Radius.full))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelLarge,
        color = colors.textPrimary.copy(alpha = 0.85f),
        maxLines = 1,
    )
}

private object FullWindowPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}
