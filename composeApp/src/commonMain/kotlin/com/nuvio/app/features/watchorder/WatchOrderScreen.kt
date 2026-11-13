package com.nuvio.app.features.watchorder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioEmptyBlock
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioGroupSegment
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.settings.SettingsSwitchRow
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
    val tokens = MaterialTheme.nuvio

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
    val result = order.takeIf { !loading }
    val steps = result?.steps?.filter { it.required || includeOptional || it.malId == route.malId }.orEmpty()

    NuvioScreen {
        item(key = "watch-order-header") {
            CenteredWatchOrderContent {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NuvioBackButton(
                        onClick = onBack,
                        shape = tokens.shapes.compactCard,
                        buttonSize = 36.dp,
                        showContainerOnDesktop = true,
                    )
                    if (route.sourceTitle.isNotBlank()) {
                        Text(
                            route.sourceTitle,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = stringResource(Res.string.watch_order_title),
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.nuvioTypeScale.titleMd,
                        color = tokens.colors.textPrimary,
                    )
                    if (result != null) {
                        Text(
                            text = stringResource(Res.string.watch_order_summary, steps.size),
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
                if (result != null) WatchOrderConfidenceBadge(result.confidence)
            }
        }
        item(key = "watch-order-filters") {
            CenteredWatchOrderContent {
                NuvioSegmentedControl(
                    segments = listOf(
                        NuvioSegment("recommended", stringResource(Res.string.watch_order_recommended)),
                        NuvioSegment("release", stringResource(Res.string.watch_order_release)),
                        NuvioSegment("chronological", stringResource(Res.string.watch_order_chronological)),
                    ),
                    selected = mode,
                    onSelect = { mode = it },
                    fillWidth = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                )
                NuvioGroup {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.watch_order_include_optional),
                        checked = includeOptional,
                        isTablet = false,
                        onCheckedChange = { includeOptional = it },
                    )
                }
            }
        }
        when {
            loading -> item(key = "watch-order-loading") {
                CenteredWatchOrderContent {
                    NuvioSurfaceCard(outlined = true) {
                        NuvioEmptyBlock(
                            icon = Icons.Rounded.FormatListNumbered,
                            title = stringResource(Res.string.watch_order_loading),
                            busy = true,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            result == null -> item(key = "watch-order-error") {
                CenteredWatchOrderContent {
                    NuvioSurfaceCard(outlined = true) {
                        NuvioEmptyBlock(
                            icon = Icons.Rounded.ErrorOutline,
                            title = if (mode == "chronological") {
                                stringResource(Res.string.watch_order_chronology_unverified)
                            } else stringResource(Res.string.watch_order_unavailable),
                            tone = NuvioIconChipTone.Warning,
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DialogButton(
                                    text = stringResource(Res.string.action_retry),
                                    onClick = { retryKey++ },
                                    style = DialogButtonStyle.Primary,
                                )
                                if (mode != "recommended") {
                                    DialogButton(
                                        text = stringResource(Res.string.watch_order_recommended),
                                        onClick = { mode = "recommended" },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> {
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
                            WatchOrderNextUp(title = next.title, onFindTitle = onFindTitle)
                        }
                    }
                }
                // One group drawn across lazy rows, so long franchises stay cheap to scroll.
                itemsIndexed(
                    items = steps,
                    key = { _, step -> step.malId },
                    contentType = { _, _ -> "watch-order-step" },
                ) { position, step ->
                    CenteredWatchOrderContent(spacing = 0.dp) {
                        Column(
                            Modifier.nuvioGroupSegment(
                                isFirst = position == 0,
                                isLast = position == steps.lastIndex,
                                containerColor = tokens.colors.surface,
                                borderColor = tokens.colors.borderSubtle,
                            ),
                        ) {
                            if (position > 0) NuvioGroupDivider()
                            WatchOrderStepRow(
                                step = step,
                                position = position + 1,
                                isCurrent = step.malId == route.malId,
                                isCompleted = step.malId in completed,
                                onFindTitle = onFindTitle,
                            )
                        }
                    }
                }
                item(key = "watch-order-footer") {
                    CenteredWatchOrderContent(spacing = 4.dp) {
                        val footerStyle = MaterialTheme.nuvioTypeScale.labelSm.copy(fontWeight = FontWeight.Normal)
                        Text(
                            stringResource(Res.string.watch_order_explanation),
                            style = footerStyle,
                            color = tokens.colors.textMuted,
                        )
                        Text(
                            stringResource(Res.string.watch_order_disclaimer),
                            style = footerStyle,
                            color = tokens.colors.textMuted,
                        )
                        Text(
                            stringResource(Res.string.watch_order_attribution),
                            modifier = Modifier
                                .clickable(role = Role.Button) { uriHandler.openUri("https://junban.moe") }
                                .padding(vertical = 4.dp),
                            style = footerStyle.copy(textDecoration = TextDecoration.Underline),
                            color = tokens.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredWatchOrderContent(
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = { content() },
        )
    }
}
