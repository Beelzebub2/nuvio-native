package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
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
    val playbackSpeed: Float?,
    val decoderMode: PlaybackDecoderMode,
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
        playbackSpeed = measuredPlaybackSpeed?.takeIf { it.isFinite() && it in 0.1f..8f },
        decoderMode = decoderMode,
    )
}

/** Observed buffering episodes while the panel is open, including seek buffers. */
internal class PlaybackDiagnosticsSession {
    private var started = false
    private var loading = true
    var rebufferEvents = 0
        private set

    fun update(snapshot: PlayerPlaybackSnapshot): Int {
        if (started && snapshot.isLoading && !loading && !snapshot.isEnded) {
            rebufferEvents = (rebufferEvents + 1).coerceAtMost(1_000_000)
        }
        if (snapshot.isPlaying && !snapshot.isLoading) started = true
        loading = snapshot.isLoading
        return rebufferEvents
    }
}

/** A strict, fixed-schema report. Never interpolate stream or platform log text. */
internal fun safeDiagnosticsReport(
    metrics: SafePlaybackMetrics,
    network: NetworkCondition,
    rebufferEvents: Int = 0,
): String = buildString {
    appendLine("Starlit playback diagnostics")
    appendLine("State: ${metrics.phase.name}")
    val resolution = metrics.resolution.takeIf { it.matches(Regex("[0-9]{1,5} × [0-9]{1,5}")) }
        ?: "Unavailable"
    appendLine("Resolution: $resolution")
    appendLine("Position seconds: ${metrics.positionSeconds}")
    appendLine("Duration seconds: ${metrics.durationSeconds?.toString() ?: "unavailable"}")
    appendLine("Buffered ahead seconds: ${metrics.bufferAheadSeconds}")
    appendLine("Playback speed: ${metrics.playbackSpeed ?: "unavailable"}")
    appendLine("Buffering events while diagnostics open (includes seeks): ${rebufferEvents.coerceIn(0, 1_000_000)}")
    appendLine("App network check: ${network.name}")
    appendLine("Source throughput: unavailable")
    appendLine("Decoder: ${metrics.decoderMode.name}")
}

@Composable
internal fun PlaybackDiagnosticsDialog(
    snapshot: PlayerPlaybackSnapshot,
    onDismiss: () -> Unit,
) {
    val data = snapshot.safePlaybackMetrics()
    val session = remember { PlaybackDiagnosticsSession() }
    var rebufferEvents by remember { mutableStateOf(0) }
    LaunchedEffect(snapshot) { rebufferEvents = session.update(snapshot) }
    val network by NetworkStatusRepository.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val tokens = MaterialTheme.nuvio
    val copiedLabel = stringResource(Res.string.player_diagnostics_copied)
    val rows = listOf(
        stringResource(Res.string.player_diagnostics_phase) to stringResource(
            when (data.phase) {
                SafePlaybackPhase.Ended -> Res.string.player_diagnostics_ended
                SafePlaybackPhase.Buffering -> Res.string.player_diagnostics_buffering
                SafePlaybackPhase.Playing -> Res.string.player_diagnostics_playing
                SafePlaybackPhase.PausedOrIdle -> Res.string.player_diagnostics_paused
            },
        ),
        stringResource(Res.string.player_diagnostics_resolution) to data.resolution,
        stringResource(Res.string.player_diagnostics_position) to
            data.positionSeconds.toString() + "s" + (data.durationSeconds?.let { " / ${it}s" } ?: ""),
        stringResource(Res.string.player_diagnostics_buffer) to "${data.bufferAheadSeconds}s",
        stringResource(Res.string.player_diagnostics_speed) to (data.playbackSpeed?.let { "${it}×" }
            ?: stringResource(Res.string.player_diagnostics_unavailable)),
        stringResource(Res.string.player_diagnostics_rebuffers) to rebufferEvents.toString(),
        stringResource(Res.string.player_diagnostics_network_status) to stringResource(
            when (network.condition) {
                NetworkCondition.Online -> Res.string.player_diagnostics_network_online
                NetworkCondition.NoInternet -> Res.string.player_diagnostics_network_offline
                NetworkCondition.ServersUnreachable -> Res.string.player_diagnostics_network_services
                NetworkCondition.Checking -> Res.string.player_diagnostics_network_checking
                NetworkCondition.Unknown -> Res.string.player_diagnostics_unavailable
            },
        ),
        stringResource(Res.string.player_diagnostics_network) to stringResource(Res.string.player_diagnostics_unavailable),
        stringResource(Res.string.player_diagnostics_decoder) to when (data.decoderMode) {
            PlaybackDecoderMode.Hardware -> "Hardware"
            PlaybackDecoderMode.Software -> "Software"
            PlaybackDecoderMode.Unknown -> stringResource(Res.string.player_diagnostics_unavailable)
        },
    )
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.player_diagnostics_title),
    ) {
        // One quiet panel holds the readings so label/value pairs line up like a table.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = tokens.colors.borderSubtle)
                DiagnosticsLine(label, value)
            }
        }
        DialogHint(stringResource(Res.string.player_diagnostics_help))
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.player_diagnostics_copy),
                onClick = {
                    clipboard.setText(AnnotatedString(safeDiagnosticsReport(data, network.condition, rebufferEvents)))
                    NuvioToastController.show(copiedLabel)
                },
            )
            DialogButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun DiagnosticsLine(label: String, value: String) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
        )
    }
}
