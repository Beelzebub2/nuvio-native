package com.nuvio.app.features.updater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.themePalette
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.action_install
import nuvio.composeapp.generated.resources.action_later
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_update
import nuvio.composeapp.generated.resources.changelog_available
import nuvio.composeapp.generated.resources.updates_debug_test_complete
import nuvio.composeapp.generated.resources.updates_download_size
import nuvio.composeapp.generated.resources.updates_downloading_progress
import nuvio.composeapp.generated.resources.updates_message_allow_installs
import nuvio.composeapp.generated.resources.updates_message_ready
import nuvio.composeapp.generated.resources.updates_preparing_download
import nuvio.composeapp.generated.resources.updates_release_notes
import nuvio.composeapp.generated.resources.updates_title_allow_installs
import nuvio.composeapp.generated.resources.updates_title_available
import nuvio.composeapp.generated.resources.updates_whats_new
import org.jetbrains.compose.resources.stringResource

/** The one action that moves an update forward: Update, Retry or Install. */
internal data class UpdateAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun AppUpdaterHost(
    controller: AppUpdaterController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
        content()
        return
    }

    val state by controller.uiState.collectAsStateWithLifecycle()
    var showReleaseNotes by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(controller) {
        controller.ensureAutoCheckStarted()
    }
    LaunchedEffect(state.update?.tag) {
        showReleaseNotes = false
    }

    val update = state.update
    val showBanner = state.showDialog && update != null
    val primaryAction = primaryUpdateAction(
        state = state,
        onDownload = controller::downloadUpdate,
        onInstall = controller::installDownloadedUpdate,
    )

    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = showBanner,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(durationMillis = 300),
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(durationMillis = 240),
            ) + fadeOut(animationSpec = tween(durationMillis = 150)),
        ) {
            update?.let { availableUpdate ->
                AppUpdateBanner(
                    state = state,
                    update = availableUpdate,
                    primaryAction = primaryAction,
                    onShowReleaseNotes = { showReleaseNotes = true },
                    onDismiss = controller::dismissDialog,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (showBanner) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
    }

    if (showReleaseNotes && update != null) {
        ReleaseNotesDialog(
            notes = update.notes,
            title = stringResource(Res.string.updates_release_notes),
            fallbackTitle = update.title,
            highlightedVersion = update.tag,
            highlightedLabel = stringResource(Res.string.changelog_available),
            releaseUrl = update.releaseUrl,
            onDismiss = { showReleaseNotes = false },
            primaryAction = primaryAction?.let { action ->
                action.copy(onClick = {
                    showReleaseNotes = false
                    action.onClick()
                })
            },
        )
    }

    if (state.showInstallPermissionDialog) {
        UnknownSourcesDialog(
            onContinue = controller::resumeInstallation,
            onDismiss = controller::dismissDialog,
        )
    }
}

private val AppUpdaterUiState.isDebugTestComplete: Boolean
    get() = isDebugTest && !isDownloading && !isUpdateAvailable

@Composable
internal fun primaryUpdateAction(
    state: AppUpdaterUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
): UpdateAction? = when {
    state.isDownloading || state.isDebugTestComplete -> null
    state.downloadedUpdatePath != null -> UpdateAction(
        label = stringResource(Res.string.action_install),
        icon = Icons.Rounded.RestartAlt,
        enabled = true,
        onClick = onInstall,
    )
    state.errorMessage != null -> UpdateAction(
        label = stringResource(Res.string.action_retry),
        icon = Icons.Rounded.Refresh,
        enabled = state.isUpdateAvailable,
        onClick = onDownload,
    )
    else -> UpdateAction(
        label = stringResource(Res.string.action_update),
        icon = Icons.Rounded.Download,
        enabled = state.isUpdateAvailable,
        onClick = onDownload,
    )
}

@Composable
internal fun AppUpdateBanner(
    state: AppUpdaterUiState,
    update: AppUpdate,
    primaryAction: UpdateAction?,
    onShowReleaseNotes: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val colors = tokens.colors
    val debugTestComplete = state.isDebugTestComplete
    val hasError = state.errorMessage != null
    val targetProgress = when {
        state.isDownloading -> state.downloadProgress ?: 0f
        debugTestComplete -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 180),
        label = "updateBannerProgress",
    )
    val showProgressLine = state.isDownloading || debugTestComplete
    val status = when {
        hasError -> state.errorMessage
        state.isDownloading && state.downloadProgress != null -> stringResource(
            Res.string.updates_downloading_progress,
            (state.downloadProgress * 100).toInt().coerceIn(0, 100),
        )
        state.isDownloading -> stringResource(Res.string.updates_preparing_download)
        debugTestComplete -> stringResource(Res.string.updates_debug_test_complete)
        state.downloadedUpdatePath != null -> stringResource(Res.string.updates_message_ready)
        else -> update.assetSizeBytes?.let { stringResource(Res.string.updates_download_size, formatFileSize(it)) }
    }

    val accent = colors.accent
    val tone = if (hasError) colors.danger else accent
    val surfaceColor = colors.surface
    val washColor = tone.copy(alpha = 0.13f)
    val hairline = colors.borderSubtle
    val trackColor = accent.copy(alpha = 0.18f)
    val accentGradient = MaterialTheme.themePalette.accentGradient
    val progressBrush = if (accentGradient.size >= 2) Brush.horizontalGradient(accentGradient) else SolidColor(accent)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(surfaceColor)
                drawRect(Brush.horizontalGradient(0f to washColor, 0.6f to washColor.copy(alpha = 0f)))
                val line = 1.dp.toPx()
                drawRect(hairline, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                if (showProgressLine) {
                    val bar = 3.dp.toPx()
                    val top = size.height - bar
                    drawRect(trackColor, topLeft = Offset(0f, top), size = Size(size.width, bar))
                    if (progress > 0f) {
                        drawRect(progressBrush, topLeft = Offset(0f, top), size = Size(size.width * progress, bar))
                    }
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        val compact = maxWidth < NuvioTokens.Breakpoint.tablet
        val horizontalPadding = if (compact) tokens.spacing.screenHorizontal else NuvioTokens.Space.s24

        val tile: @Composable () -> Unit = {
            UpdateStatusTile(
                icon = when {
                    hasError -> Icons.Rounded.ErrorOutline
                    debugTestComplete || state.downloadedUpdatePath != null -> Icons.Rounded.TaskAlt
                    else -> Icons.Rounded.SystemUpdateAlt
                },
                tint = tone,
                downloadProgress = if (state.isDownloading) state.downloadProgress else null,
            )
        }
        val text: @Composable RowScope.() -> Unit = {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    Text(
                        text = stringResource(Res.string.updates_title_available),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    VersionPill(update.tag)
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasError) colors.danger else colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val whatsNew: @Composable () -> Unit = {
            NuvioCompactActionButton(
                label = stringResource(Res.string.updates_whats_new),
                icon = Icons.Rounded.AutoAwesome,
                onClick = onShowReleaseNotes,
                enabled = update.notes.isNotBlank(),
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
            )
        }
        val primary: @Composable () -> Unit = {
            primaryAction?.let { action ->
                NuvioCompactActionButton(
                    label = action.label,
                    icon = action.icon,
                    onClick = action.onClick,
                    enabled = action.enabled,
                    tone = NuvioCompactActionTone.Primary,
                    size = NuvioActionSize.Small,
                )
            }
        }
        val close: @Composable () -> Unit = {
            // Downloads can't be cancelled, so the banner stays until the download finishes.
            if (!state.isDownloading) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(NuvioTokens.Space.s36)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = colors.textMuted,
                        modifier = Modifier.size(tokens.icons.md),
                    )
                }
            }
        }

        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = horizontalPadding, end = horizontalPadding - 6.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    tile()
                    text()
                    close()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    whatsNew()
                    primary()
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(start = horizontalPadding, end = horizontalPadding - 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                tile()
                text()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    whatsNew()
                    primary()
                    close()
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusTile(
    icon: ImageVector,
    tint: Color,
    downloadProgress: Float?,
) {
    Box(
        modifier = Modifier
            .size(NuvioTokens.Space.s40)
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (downloadProgress != null) {
            CircularProgressIndicator(
                progress = { downloadProgress.coerceIn(0f, 1f) },
                modifier = Modifier.size(22.dp),
                color = tint,
                strokeWidth = 2.5.dp,
                trackColor = tint.copy(alpha = 0.22f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun VersionPill(version: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NuvioTokens.Radius.full),
        color = colors.accent.copy(alpha = 0.16f),
        contentColor = colors.accent,
    ) {
        Text(
            text = version,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownSourcesDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.updates_title_allow_installs),
    ) {
        Text(
            text = stringResource(Res.string.updates_message_allow_installs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_later),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_continue),
                onClick = onContinue,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
