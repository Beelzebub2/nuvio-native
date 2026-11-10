package com.nuvio.app.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.streams.SourceResultsFilters
import com.nuvio.app.features.streams.SourceResultsFilterControls
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Semantic color roles used across the remote screens. */
internal enum class RemoteTone { Neutral, Accent, Success, Warning, Danger }

@Composable
internal fun RemoteTone.color(): Color {
    val colors = MaterialTheme.nuvio.colors
    return when (this) {
        RemoteTone.Neutral -> colors.textMuted
        RemoteTone.Accent -> colors.accent
        RemoteTone.Success -> colors.success
        RemoteTone.Warning -> colors.warning
        RemoteTone.Danger -> colors.danger
    }
}

/** A bordered surface. A tone tints it for notices such as firewall warnings. */
@Composable
internal fun RemoteCard(
    modifier: Modifier = Modifier,
    tone: RemoteTone? = null,
    contentPadding: Dp = 16.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.card
    val tint = tone?.color()
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint?.copy(alpha = 0.08f) ?: tokens.colors.surface)
            .border(tokens.borders.thin, tint?.copy(alpha = 0.32f) ?: tokens.colors.borderSubtle, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
internal fun RemoteSectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        trailing?.invoke()
    }
}

@Composable
internal fun RemoteStatusPill(text: String, tone: RemoteTone, modifier: Modifier = Modifier, busy: Boolean = false) {
    val color = tone.color()
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("remote-status-pill"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) NuvioLoadingIndicator(Modifier.size(10.dp), color = color, size = 10.dp)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.nuvio.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** A round tinted icon, with a progress ring while [busy]. */
@Composable
internal fun RemoteIconBadge(icon: ImageVector, tone: RemoteTone = RemoteTone.Accent, size: Dp = 48.dp, busy: Boolean = false) {
    val color = tone.color()
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.fillMaxSize(), color = color, strokeWidth = 2.dp)
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.48f))
    }
}

/** One way to connect (QR, network, Bluetooth, address), laid out in a two-column grid. */
@Composable
internal fun RemoteActionTile(
    icon: ImageVector,
    label: String,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    val container = when {
        primary -> tokens.colors.accent
        selected -> tokens.colors.accent.copy(alpha = tokens.opacity.selected)
        else -> tokens.colors.surface
    }
    val contentColor = if (primary) tokens.colors.onAccent else tokens.colors.textPrimary
    Column(
        modifier
            .clip(shape)
            .background(container)
            .border(
                tokens.borders.thin,
                when {
                    primary -> Color.Transparent
                    selected -> tokens.colors.accent.copy(alpha = 0.5f)
                    else -> tokens.colors.borderSubtle
                },
                shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) contentColor else tokens.colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = contentColor, maxLines = 2)
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (primary) contentColor.copy(alpha = 0.78f) else tokens.colors.textMuted,
                maxLines = 3,
            )
        }
    }
}

/** Lays tiles out two per row with equal heights. */
@Composable
internal fun RemoteTileGrid(tiles: List<@Composable (Modifier) -> Unit>, spacing: Dp = 10.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun RemoteDeviceRow(
    name: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surface)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, shape)
            .then(if (active) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteIconBadge(icon, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else if (active) {
            Text(stringResource(Res.string.remote_pc_status_connected), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = tokens.colors.textSecondary)
        } else {
            Text(stringResource(Res.string.remote_pc_connect), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = tokens.colors.accent)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                tint = tokens.colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** Equal-width tabs in the same pill style as the app's segmented controls. */
@Composable
internal fun <T> RemoteTabs(tabs: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surface)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, RoundedCornerShape(14.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (key, label) ->
            val isSelected = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) tokens.colors.accent.copy(alpha = tokens.opacity.selected) else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(key) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

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
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RemoteIconBadge(Icons.Rounded.VerifiedUser, size = 64.dp, busy = confirmed)
        Text(stringResource(Res.string.remote_verify_title), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        Text(stringResource(Res.string.remote_verify_prompt), style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        RemotePairingCode(pairing.code, large = true)
        Text(
            if (confirmed) stringResource(Res.string.remote_verify_waiting)
            else stringResource(Res.string.remote_verify_peer, pairing.peer),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
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

/** A list picker for episodes, subtitles or sources, opened from a playback tile. */
@Composable
internal fun RemoteChoiceList(title: String, items: List<RemoteChoice>, command: String, onDismiss: () -> Unit) {
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
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
        }
    }
}

/** Initial source selection happens on the phone; the PC keeps the actual stream objects. */
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
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.remote_source_picker_title),
        message = stringResource(Res.string.remote_source_picker_device, pcName.ifBlank { "Starlit PC" }),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (matches && state.title.isNotBlank()) {
                Text(state.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (searching || preparing) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NuvioLoadingIndicator(Modifier.size(18.dp), color = tokens.colors.accent, size = 18.dp)
                    Text(
                        stringResource(if (preparing) Res.string.remote_source_picker_preparing else Res.string.remote_source_picker_searching),
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    )
                }
            }
            if (playing) {
                RemoteCard(tone = RemoteTone.Success) {
                    Text(stringResource(Res.string.remote_source_picker_playing),
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                }
            }
            if (!connected) {
                DialogHint(stringResource(Res.string.remote_source_picker_disconnected), isError = true)
            } else if (noResponse && !matches) {
                DialogHint(stringResource(Res.string.remote_source_picker_timeout), isError = true)
            } else if (matches && state.phase == "empty") {
                DialogHint(stringResource(Res.string.remote_source_picker_empty))
            } else if (matches && state.phase == "error") {
                DialogHint(state.message.ifBlank { stringResource(Res.string.remote_source_picker_error) }, isError = true)
            } else if (matches && state.message.isNotBlank() && state.phase != "playing") {
                DialogHint(state.message)
            }
            if (matches && state.sources.isNotEmpty() && state.phase in setOf("searching", "ready")) {
                SourceResultsFilterControls(
                    filters = sourceFilters,
                    availability = filterAvailability,
                    onFiltersChange = { sourceFilters = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filteredSources.isEmpty()) {
                    DialogHint(stringResource(Res.string.source_filter_no_matches))
                    Text(
                        stringResource(Res.string.source_filter_adjust),
                        color = tokens.colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                val recommended = filteredSources.firstOrNull { it.recommended }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                        .testTag("remote-initial-sources"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (recommended != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(Res.string.streams_best_match),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = tokens.colors.accent,
                            )
                            val reason = when (recommended.recommendationReason) {
                                "AutoPlayRules" -> Res.string.streams_best_match_auto_play
                                "ReadyToPlay" -> Res.string.streams_best_match_ready
                                "HighestQuality" -> Res.string.streams_best_match_highest_quality
                                else -> null
                            }
                            if (reason != null) {
                                Text(
                                    stringResource(reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.colors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        RemoteSourceOption(
                            source = recommended,
                            isRecommended = true,
                            onClick = { DesktopRemote.selectPhoneSource(recommended.id) },
                        )
                    }
                    filteredSources.filterNot { it.recommended }.forEach { source ->
                        RemoteSourceOption(
                            source = source,
                            isRecommended = false,
                            onClick = { DesktopRemote.selectPhoneSource(source.id) },
                        )
                    }
                    if (filteredSources.any { it.qualityStars != null }) {
                        Text(
                            stringResource(Res.string.remote_source_quality_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
                }
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
            if (playing) {
                DialogButton(
                    text = stringResource(Res.string.remote_source_picker_controller),
                    onClick = onOpenController,
                    style = DialogButtonStyle.Primary,
                )
            }
        }
    }
}

/** One touch target per PC-resolved stream; all additional fields are optional for older peers. */
@Composable
private fun RemoteSourceOption(
    source: RemoteChoice,
    isRecommended: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (isRecommended) tokens.colors.accent.copy(alpha = 0.12f) else tokens.colors.surface)
            .border(
                tokens.borders.thin,
                if (isRecommended) tokens.colors.accent.copy(alpha = 0.65f) else tokens.colors.borderSubtle,
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("remote-source-option")
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                source.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isRecommended) FontWeight.SemiBold else FontWeight.Medium,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = if (isRecommended) tokens.colors.accent else tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (source.quality != null || source.qualityStars != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                source.quality?.let { quality ->
                    Text(
                        quality,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textSecondary,
                    )
                }
                source.qualityStars?.takeIf { it in 1..5 }?.let { stars ->
                    val description = stringResource(Res.string.remote_source_quality_stars, stars)
                    Row(
                        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        repeat(5) { index ->
                            Icon(
                                if (index < stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (index < stars) tokens.colors.accent else tokens.colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
        if (source.seeders != null || source.peers != null || source.cached) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                source.seeders?.let { count ->
                    Text(
                        stringResource(Res.string.remote_source_seeders, count),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textSecondary,
                    )
                }
                source.peers?.let { count ->
                    Text(
                        stringResource(Res.string.remote_source_peers, count),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textSecondary,
                    )
                }
                if (source.cached) {
                    Text(
                        stringResource(Res.string.remote_source_cached),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.accent,
                    )
                }
            }
        }
    }
}

internal fun remoteTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
