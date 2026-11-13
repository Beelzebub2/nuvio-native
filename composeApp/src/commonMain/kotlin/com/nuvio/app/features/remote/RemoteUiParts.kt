package com.nuvio.app.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioStatusEyebrow
import com.nuvio.app.core.ui.NuvioStatusTone
import com.nuvio.app.core.ui.NuvioTag
import com.nuvio.app.core.ui.NuvioTagStyle
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioHighlightedRowColor
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.surfaceInset
import com.nuvio.app.core.ui.tabular
import com.nuvio.app.features.streams.SourceResultsFilters
import com.nuvio.app.features.streams.SourceResultsFilterControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The six-digit code as separate boxes, split in half so it is easy to compare aloud. */
@Composable
internal fun RemotePairingCode(code: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    val half = if (code.length % 2 == 0) code.length / 2 else -1
    Row(
        modifier
            .fillMaxWidth()
            .testTag("remote-pairing-code")
            .semantics(mergeDescendants = true) { contentDescription = code.toList().joinToString(" ") },
        horizontalArrangement = Arrangement.spacedBy(if (large) 10.dp else 6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        code.forEachIndexed { index, digit ->
            if (index == half) Spacer(Modifier.width(if (large) 14.dp else 8.dp))
            Box(
                Modifier
                    .size(width = if (large) 52.dp else 40.dp, height = if (large) 64.dp else 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.colors.accent.copy(alpha = 0.12f))
                    .border(tokens.borders.thin, tokens.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    digit.toString(),
                    style = (if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium)
                        .copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }
        }
    }
}

@Composable
internal fun RemotePairingDialog(
    pairing: RemotePairing,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onReject,
        title = stringResource(Res.string.remote_verify_title),
        message = stringResource(Res.string.remote_verify_prompt),
    ) {
        RemotePairingCode(pairing.code, Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (confirmed) NuvioLoadingIndicator(Modifier.size(16.dp), color = tokens.colors.accent, size = 16.dp)
            DialogHint(
                if (confirmed) stringResource(Res.string.remote_verify_waiting)
                else stringResource(Res.string.remote_verify_peer, pairing.peer),
            )
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.remote_handoff_reject), onClick = onReject)
            DialogButton(
                text = stringResource(Res.string.remote_verify_confirm),
                onClick = onConfirm,
                style = DialogButtonStyle.Primary,
                enabled = !confirmed,
            )
        }
    }
}

/** The same verification, drawn inside the desktop Phone remote window. */
@Composable
internal fun RemotePairingPanel(
    pairing: RemotePairing,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier.widthIn(max = 520.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NuvioIconChip(size = 56.dp, shape = tokens.shapes.button) {
            if (confirmed) {
                NuvioLoadingIndicator(Modifier.size(26.dp), color = LocalContentColor.current, size = 26.dp)
            } else {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.remote_verify_title), style = MaterialTheme.nuvioTypeScale.titleMd,
                color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
            Text(stringResource(Res.string.remote_verify_prompt), style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted, textAlign = TextAlign.Center)
        }
        RemotePairingCode(pairing.code, large = true)
        Text(
            if (confirmed) stringResource(Res.string.remote_verify_waiting)
            else stringResource(Res.string.remote_verify_peer, pairing.peer),
            style = MaterialTheme.nuvioTypeScale.bodySm,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogButton(text = stringResource(Res.string.remote_handoff_reject), onClick = onReject)
            DialogButton(
                text = stringResource(Res.string.remote_verify_confirm),
                onClick = onConfirm,
                style = DialogButtonStyle.Primary,
                enabled = !confirmed,
            )
        }
    }
}

@Composable
internal fun RemoteHandoffDialog(title: String, onAccept: () -> Unit, onReject: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onReject,
        title = stringResource(Res.string.remote_handoff_approve_title),
        message = stringResource(Res.string.remote_handoff_approve_description),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.surfaceCard)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIconChip(Icons.Rounded.PlayArrow)
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = tokens.colors.textPrimary)
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.remote_handoff_reject), onClick = onReject)
            DialogButton(
                text = stringResource(Res.string.remote_handoff_approve),
                onClick = onAccept,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

/**
 * A list picker for episodes, subtitles, audio tracks or sources, opened from a playback option.
 * [actionLabel] adds an inline action under the list, such as looking for add-on subtitles.
 */
@Composable
internal fun RemoteChoiceList(
    title: String,
    items: List<RemoteChoice>,
    command: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                DialogOption(
                    text = item.label,
                    selected = item.selected,
                    onClick = {
                        DesktopRemote.command(command, id = item.id)
                        onDismiss()
                    },
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.nuvio.colors.accent,
                    modifier = Modifier.size(18.dp))
                NuvioActionLabel(
                    text = actionLabel,
                    onClick = onAction,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
        }
    }
}

/** Initial source selection happens on the phone; the PC keeps the actual stream objects. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RemoteSourcePickerDialog(
    requestId: String,
    state: RemoteSourcePicker,
    connected: Boolean,
    pcName: String,
    onDismiss: () -> Unit,
    onOpenController: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val matches = requestId.isNotEmpty() && requestId == state.requestId
    var noResponse by remember(requestId) { mutableStateOf(false) }
    var sourceFilters by remember(requestId) { mutableStateOf(SourceResultsFilters()) }
    val filterAvailability = remember(state.sources) { remoteSourceFilterAvailability(state.sources) }
    val filteredSources = remember(state.sources, sourceFilters) {
        filterRemoteSourceChoices(state.sources, sourceFilters)
    }
    LaunchedEffect(requestId, matches, connected) {
        if (matches) {
            noResponse = false
        } else if (connected && requestId.isNotEmpty()) {
            delay(20_000L)
            noResponse = DesktopRemote.remotePlayback.value.sourcePicker.requestId != requestId
        }
    }
    val searching = connected && !noResponse && (!matches || state.phase == "searching")
    val preparing = connected && matches && state.phase == "preparing"
    val playing = connected && matches && state.phase == "playing"
    val showSources = matches && state.sources.isNotEmpty() && state.phase in setOf("searching", "ready")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val title = stringResource(Res.string.remote_source_picker_title)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                    )
                    NuvioStatusEyebrow(
                        text = stringResource(Res.string.remote_source_picker_device, pcName.ifBlank { "Starlit PC" }),
                        tone = if (connected) NuvioStatusTone.Success else NuvioStatusTone.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                NuvioSquareIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    onClick = { scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) } },
                    size = 36.dp,
                    iconSize = 18.dp,
                )
            }
            if (matches && state.title.isNotBlank()) {
                Text(state.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = tokens.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // One status at a time takes the list area; a search that already has results keeps
            // the list and reports progress in a single line above it.
            when {
                !connected -> SourcePickerStatus(Icons.Rounded.LinkOff, NuvioIconChipTone.Danger,
                    stringResource(Res.string.remote_source_picker_disconnected))
                noResponse && !matches -> SourcePickerStatus(Icons.Rounded.ErrorOutline, NuvioIconChipTone.Warning,
                    stringResource(Res.string.remote_source_picker_timeout))
                playing -> SourcePickerStatus(Icons.Rounded.CheckCircle, NuvioIconChipTone.Success,
                    stringResource(Res.string.remote_source_picker_playing))
                preparing -> SourcePickerStatus(Icons.Rounded.DesktopWindows, NuvioIconChipTone.Accent,
                    stringResource(Res.string.remote_source_picker_preparing), busy = true)
                matches && state.phase == "empty" -> SourcePickerStatus(Icons.Rounded.SearchOff, NuvioIconChipTone.Neutral,
                    stringResource(Res.string.remote_source_picker_empty))
                matches && state.phase == "error" -> SourcePickerStatus(Icons.Rounded.ErrorOutline, NuvioIconChipTone.Danger,
                    state.message.ifBlank { stringResource(Res.string.remote_source_picker_error) })
                searching && !showSources -> SourcePickerStatus(Icons.Rounded.Search, NuvioIconChipTone.Accent,
                    stringResource(Res.string.remote_source_picker_searching), busy = true)
                searching -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NuvioLoadingIndicator(Modifier.size(16.dp), color = tokens.colors.accent, size = 16.dp)
                    Text(stringResource(Res.string.remote_source_picker_searching),
                        style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted)
                }
            }
            if (connected && matches && state.message.isNotBlank() && state.phase !in setOf("playing", "error")) {
                DialogHint(state.message)
            }
            if (showSources) {
                SourceResultsFilterControls(
                    filters = sourceFilters,
                    availability = filterAvailability,
                    onFiltersChange = { sourceFilters = it },
                    modifier = Modifier.fillMaxWidth(),
                    resultCount = filteredSources.size,
                    onSheet = true,
                    contentPadding = PaddingValues(0.dp),
                    searchHeight = 44.dp,
                )
                if (filteredSources.isEmpty()) {
                    SourcePickerStatus(
                        icon = Icons.Rounded.SearchOff,
                        tone = NuvioIconChipTone.Neutral,
                        text = stringResource(Res.string.source_filter_no_matches),
                        detail = stringResource(Res.string.source_filter_adjust),
                    )
                } else {
                    // The PC's pick leads the list; the rest keep the PC's order.
                    val ordered = filteredSources.filter { it.recommended }.take(1) +
                        filteredSources.filterNot { it.recommended }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .testTag("remote-initial-sources"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NuvioGroup(containerColor = tokens.colors.surfaceInset) {
                            ordered.forEachIndexed { index, source ->
                                if (index > 0) NuvioGroupDivider(startInset = 16.dp, endInset = 16.dp)
                                RemoteSourceOption(
                                    source = source,
                                    isRecommended = index == 0 && source.recommended,
                                    onClick = { DesktopRemote.selectPhoneSource(source.id) },
                                )
                            }
                        }
                        if (filteredSources.any { it.qualityStars != null }) {
                            Text(
                                stringResource(Res.string.remote_source_quality_hint),
                                style = MaterialTheme.nuvioTypeScale.bodySm,
                                color = tokens.colors.textMuted,
                            )
                        }
                    }
                }
            }
            DialogButton(
                text = stringResource(Res.string.remote_source_picker_controller),
                onClick = onOpenController,
                modifier = Modifier.fillMaxWidth(),
                style = if (playing) DialogButtonStyle.Primary else DialogButtonStyle.Secondary,
            )
        }
    }
}

/** A centred status in the picker's list area: searching, preparing, playing, or a problem. */
@Composable
private fun SourcePickerStatus(
    icon: ImageVector,
    tone: NuvioIconChipTone,
    text: String,
    detail: String? = null,
    busy: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvioIconChip(size = 48.dp, tone = tone) {
            if (busy) NuvioLoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current, size = 24.dp)
            else Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, textAlign = TextAlign.Center)
        }
    }
}

/** One touch target per PC-resolved stream; all additional fields are optional for older peers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteSourceOption(
    source: RemoteChoice,
    isRecommended: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (isRecommended) nuvioHighlightedRowColor(tokens.colors.surfaceInset) else tokens.colors.surfaceInset,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("remote-source-option")
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                source.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isRecommended || source.cached) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isRecommended) NuvioTag(stringResource(Res.string.streams_best_match), style = NuvioTagStyle.Accent)
                    if (source.cached) NuvioTag(stringResource(Res.string.remote_source_cached), style = NuvioTagStyle.Success)
                }
            }
            val meta = buildList {
                source.quality?.takeIf { it.isNotBlank() }?.let(::add)
                source.seeders?.let { add(stringResource(Res.string.remote_source_seeders, it)) }
                source.peers?.let { add(stringResource(Res.string.remote_source_peers, it)) }
            }
            val stars = source.qualityStars?.takeIf { it in 1..5 }
            if (stars != null || meta.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (stars != null) {
                        val description = stringResource(Res.string.remote_source_quality_stars, stars)
                        Row(
                            Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            repeat(5) { index ->
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (index < stars) tokens.colors.accent else tokens.colors.textPrimary.copy(alpha = 0.18f),
                                )
                            }
                        }
                    }
                    val metaStyle = MaterialTheme.nuvioTypeScale.bodySm.tabular
                    meta.forEachIndexed { index, part ->
                        if (index > 0) Text("·", style = metaStyle, color = tokens.colors.textMuted)
                        Text(part, style = metaStyle, color = tokens.colors.textMuted, maxLines = 1)
                    }
                }
            }
            if (isRecommended) {
                val reason = when (source.recommendationReason) {
                    "AutoPlayRules" -> Res.string.streams_best_match_auto_play
                    "ReadyToPlay" -> Res.string.streams_best_match_ready
                    "HighestQuality" -> Res.string.streams_best_match_highest_quality
                    else -> null
                }
                if (reason != null) {
                    Text(stringResource(reason), style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = if (isRecommended) tokens.colors.accent else tokens.colors.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun remoteTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
