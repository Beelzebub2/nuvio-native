package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.auth.LocalImportOutcome
import com.nuvio.app.core.auth.LocalImportProgress
import com.nuvio.app.core.auth.LocalImportStage
import com.nuvio.app.core.auth.LocalSettingsImport
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Above the settings page so imported language/navigation changes cannot remove the progress UI. */
@Composable
internal fun LocalSettingsImportProgressHost() {
    val progress by LocalSettingsImport.progress.collectAsStateWithLifecycle()
    val auth by AuthRepository.state.collectAsStateWithLifecycle()
    val profiles by ProfileRepository.state.collectAsStateWithLifecycle()
    val current = progress ?: return
    // Reading these flows also dismisses an old account/profile's result on a lifecycle change.
    if (auth.userId != null && profiles.activeProfile != null && LocalSettingsImport.isCurrentProgress(current)) {
        LocalSettingsImportProgressDialog(current, LocalSettingsImport::dismissProgress)
    }
}

@Composable
internal fun LocalSettingsImportProgressDialog(progress: LocalImportProgress, onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val running = progress.isRunning
    val synced = progress.outcome == LocalImportOutcome.Synced
    val localReady = synced || progress.outcome == LocalImportOutcome.Pending
    val fraction by animateFloatAsState(
        targetValue = if (synced) 1f else progress.stage.ordinal.toFloat() / LocalImportStage.entries.size,
        animationSpec = tween(380), label = "import-progress",
    )
    DialogSurface(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(colors.accent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                if (running) NuvioLoadingIndicator(Modifier.size(26.dp), color = colors.accent)
                else Icon(if (synced) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, null, tint = colors.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.settings_account_import_local), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(stringResource(if (localReady) Res.string.settings_import_progress_complete else Res.string.settings_import_progress_title),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f, fill = false).heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AnimatedContent(
                targetState = progress.stage to progress.outcome,
                transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 4 }).togetherWith(fadeOut(tween(140))) },
                label = "import-stage",
            ) { (stage, outcome) ->
                Text(stringResource(when (outcome) {
                    LocalImportOutcome.Synced -> Res.string.settings_account_import_success
                    LocalImportOutcome.Pending -> Res.string.settings_import_progress_pending
                    LocalImportOutcome.Failed -> Res.string.settings_import_progress_failed
                    null -> stage.label()
                }), Modifier.fillMaxWidth().heightIn(min = 40.dp), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = colors.accent, trackColor = colors.accent.copy(alpha = .12f))
            if (running) Text(stringResource(Res.string.settings_import_progress_step, progress.stage.ordinal + 1, LocalImportStage.entries.size),
                style = MaterialTheme.typography.labelSmall, color = colors.textMuted)

            val summary = progress.summary
            val rows = listOf(
                Triple(LocalImportStage.Preferences, Res.string.settings_import_summary_preferences, null),
                Triple(LocalImportStage.AddonsAndPlugins, Res.string.settings_import_summary_addons,
                    summary?.let { stringResource(Res.string.settings_import_summary_addon_count, it.addons, it.plugins) }),
                Triple(LocalImportStage.Downloads, Res.string.settings_import_summary_downloads,
                    summary?.let { stringResource(Res.string.settings_import_summary_count, it.downloads) }),
                Triple(LocalImportStage.LibraryAndHistory, Res.string.settings_import_summary_history,
                    summary?.let { stringResource(Res.string.settings_import_summary_count, it.library + it.history) }),
                Triple(LocalImportStage.CollectionsAndLists, Res.string.settings_import_summary_lists,
                    summary?.let { stringResource(Res.string.settings_import_summary_count, it.collections + it.savedLists) }),
                Triple(LocalImportStage.SyncSettings, Res.string.settings_import_summary_cloud, null),
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { (stage, label, detail) ->
                    val cloud = stage == LocalImportStage.SyncSettings
                    val done = if (cloud) synced else localReady || progress.stage.ordinal > stage.ordinal
                    val active = running && if (cloud) progress.stage.ordinal >= stage.ordinal else progress.stage == stage
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).background(if (done) colors.accent.copy(alpha = .12f) else colors.surfaceCard, CircleShape)
                            .border(1.dp, if (active) colors.accent else colors.textMuted.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                            if (done) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.accent)
                            else if (active) NuvioLoadingIndicator(Modifier.size(14.dp), color = colors.accent)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active || done) colors.textPrimary else colors.textMuted)
                            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textMuted) }
                        }
                    }
                }
            }
            Text(stringResource(Res.string.settings_import_progress_note), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (!running) DialogButtons {
            DialogButton(stringResource(Res.string.action_close), onDismiss, style = DialogButtonStyle.Primary)
        }
    }
}

private fun LocalImportStage.label() = when (this) {
    LocalImportStage.Preparing -> Res.string.settings_import_progress_preparing
    LocalImportStage.Preferences -> Res.string.settings_import_progress_preferences
    LocalImportStage.AddonsAndPlugins -> Res.string.settings_import_progress_addons
    LocalImportStage.Downloads -> Res.string.settings_import_progress_downloads
    LocalImportStage.LibraryAndHistory -> Res.string.settings_import_progress_history
    LocalImportStage.CollectionsAndLists -> Res.string.settings_import_progress_lists
    LocalImportStage.SyncSettings -> Res.string.settings_import_progress_sync_settings
    LocalImportStage.SyncAddonsAndPlugins -> Res.string.settings_import_progress_sync_addons
    LocalImportStage.SyncLibraryAndHistory -> Res.string.settings_import_progress_sync_history
}
