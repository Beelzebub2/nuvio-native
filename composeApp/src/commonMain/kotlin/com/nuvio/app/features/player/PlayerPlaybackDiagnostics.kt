package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Deliberately contains no source URL, CDN token, local path, addon headers or
 * account information. Unknown metrics are shown as unavailable rather than
 * guessed from resolution or buffer progress.
 */
internal data class SafePlaybackMetrics(
    val phase: SafePlaybackPhase,
    val resolution: String,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val bufferAheadSeconds: Long,
    val playbackSpeed: Float,
)

internal enum class SafePlaybackPhase { Buffering, Playing, PausedOrIdle, Ended }

internal fun PlayerPlaybackSnapshot.safePlaybackMetrics(): SafePlaybackMetrics {
    val phase = when {
        isEnded -> SafePlaybackPhase.Ended
        isLoading -> SafePlaybackPhase.Buffering
        isPlaying -> SafePlaybackPhase.Playing
        else -> SafePlaybackPhase.PausedOrIdle
    }
    val resolution = if (videoWidth in 1..16384 && videoHeight in 1..16384)
        "${videoWidth} × ${videoHeight}" else "Unavailable"
    val position = positionMs.coerceAtLeast(0L)
    val bufferAhead = (bufferedPositionMs.coerceAtLeast(0L) - position).coerceAtLeast(0L)
    return SafePlaybackMetrics(
        phase = phase,
        resolution = resolution,
        positionSeconds = position / 1_000L,
        durationSeconds = durationMs.takeIf { it > 0L }?.div(1_000L),
        bufferAheadSeconds = bufferAhead / 1_000L,
        playbackSpeed = playbackSpeed.takeIf { it.isFinite() && it > 0 } ?: 1f,
    )
}

/** A strict, fixed-schema report. Never interpolate stream or platform log text. */
internal fun safeDiagnosticsReport(
    metrics: SafePlaybackMetrics,
    network: NetworkCondition,
): String = buildString {
    appendLine("Starlit playback diagnostics")
    appendLine("State: ${metrics.phase.name}")
    val resolution = metrics.resolution.takeIf { it.matches(Regex("[0-9]{1,5} × [0-9]{1,5}")) }
        ?: "Unavailable"
    appendLine("Resolution: $resolution")
    appendLine("Position seconds: ${metrics.positionSeconds}")
    appendLine("Duration seconds: ${metrics.durationSeconds?.toString() ?: "unavailable"}")
    appendLine("Buffered ahead seconds: ${metrics.bufferAheadSeconds}")
    appendLine("Playback speed: ${metrics.playbackSpeed}")
    appendLine("App network check: ${network.name}")
    appendLine("Source throughput: unavailable")
    appendLine("Decoder: unavailable")
}

@Composable
internal fun PlaybackDiagnosticsDialog(
    snapshot: PlayerPlaybackSnapshot,
    onDismiss: () -> Unit,
) {
    val data = snapshot.safePlaybackMetrics()
    val network by NetworkStatusRepository.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.player_diagnostics_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiagnosticsLine(
                    stringResource(Res.string.player_diagnostics_phase),
                    stringResource(
                        when (data.phase) {
                            SafePlaybackPhase.Ended -> Res.string.player_diagnostics_ended
                            SafePlaybackPhase.Buffering -> Res.string.player_diagnostics_buffering
                            SafePlaybackPhase.Playing -> Res.string.player_diagnostics_playing
                            SafePlaybackPhase.PausedOrIdle -> Res.string.player_diagnostics_paused
                        },
                    ),
                )
                DiagnosticsLine(stringResource(Res.string.player_diagnostics_resolution), data.resolution)
                DiagnosticsLine(
                    stringResource(Res.string.player_diagnostics_position),
                    data.positionSeconds.toString() + "s" +
                        (data.durationSeconds?.let { " / ${it}s" } ?: ""),
                )
                DiagnosticsLine(
                    stringResource(Res.string.player_diagnostics_buffer),
                    "${data.bufferAheadSeconds}s",
                )
                DiagnosticsLine(stringResource(Res.string.player_diagnostics_speed), "${data.playbackSpeed}×")
                DiagnosticsLine(
                    stringResource(Res.string.player_diagnostics_network_status),
                    stringResource(
                        when (network.condition) {
                            NetworkCondition.Online -> Res.string.player_diagnostics_network_online
                            NetworkCondition.NoInternet -> Res.string.player_diagnostics_network_offline
                            NetworkCondition.ServersUnreachable -> Res.string.player_diagnostics_network_services
                            NetworkCondition.Checking -> Res.string.player_diagnostics_network_checking
                            NetworkCondition.Unknown -> Res.string.player_diagnostics_unavailable
                        }
                    ),
                )
                DiagnosticsLine(
                    stringResource(Res.string.player_diagnostics_network),
                    stringResource(Res.string.player_diagnostics_unavailable),
                )
                DiagnosticsLine(
                    stringResource(Res.string.player_diagnostics_decoder),
                    stringResource(Res.string.player_diagnostics_unavailable),
                )
                Text(
                    stringResource(Res.string.player_diagnostics_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(safeDiagnosticsReport(data, network.condition)))
            }) { Text(stringResource(Res.string.player_diagnostics_copy)) }
        },
    )
}

@Composable
private fun DiagnosticsLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}
