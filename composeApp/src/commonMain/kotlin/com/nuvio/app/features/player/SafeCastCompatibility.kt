package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** A local, offline suitability check. It never resolves, opens, uploads or logs source URLs. */
internal enum class CastCompatibility {
    NoSource,
    PeerToPeerOrLocal,
    AuthenticatedHeaders,
    SeparateAudio,
    NonHttpsSource,
    SignedOrOpaqueUrl,
    PrivateOrUnknownHost,
    UnsupportedMediaFormat,
    PotentiallyCompatibleDirectMedia,
}

/**
 * A candidate result is not permission to cast. The Android sender still
 * requires explicit rights confirmation, a supported Cast receiver and
 * deliberate device selection. No network/receiver calls happen here.
 */
internal fun assessCastCompatibility(
    sourceUrl: String?,
    sourceHeaders: Map<String, String>,
    isPeerToPeer: Boolean,
    hasSeparateAudio: Boolean,
): CastCompatibility {
    if (isPeerToPeer) return CastCompatibility.PeerToPeerOrLocal
    if (sourceUrl.isNullOrBlank()) return CastCompatibility.NoSource
    val url = sourceUrl.trim()
    if (url.startsWith("magnet:", true) || url.startsWith("file:", true) ||
        url.startsWith("content:", true) || url.startsWith("rtsp:", true))
        return CastCompatibility.PeerToPeerOrLocal
    if (sourceHeaders.isNotEmpty()) return CastCompatibility.AuthenticatedHeaders
    if (hasSeparateAudio) return CastCompatibility.SeparateAudio
    if (!url.startsWith("https://", ignoreCase = true)) return CastCompatibility.NonHttpsSource
    if (url != sourceUrl || '?' in url || '#' in url || url.length > 2048 || '\\' in url ||
        url.any { it.isWhitespace() || it.code < 32 || it.code == 127 } ||
        Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(url))
        return CastCompatibility.SignedOrOpaqueUrl

    val authority = url.substringAfter("://").substringBefore('/')
    val host = authority.lowercase()
    val labels = host.split('.')
    if (host.length !in 4..253 || labels.size < 2 ||
        labels.any { it.length !in 1..63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) } ||
        !labels.last().matches(Regex("[a-z]{2,63}")) ||
        listOf(".local", ".localhost", ".lan", ".internal", ".test", ".invalid", ".onion",
            ".home", ".home.arpa", ".localdomain")
            .any { host.endsWith(it) })
        return CastCompatibility.PrivateOrUnknownHost

    val path = url.substringAfter(authority, "").substringBefore('?')
    if (!listOf(".m3u8", ".mpd", ".mp4", ".webm").any { path.lowercase().endsWith(it) })
        return CastCompatibility.UnsupportedMediaFormat

    return CastCompatibility.PotentiallyCompatibleDirectMedia
}

/** MIME types accepted by the Default Media Receiver, never inferred from a response body. */
internal fun eligibleCastMimeType(url: String): String? {
    if (assessCastCompatibility(url, emptyMap(), isPeerToPeer = false, hasSeparateAudio = false) !=
        CastCompatibility.PotentiallyCompatibleDirectMedia) return null
    return when {
        url.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        url.endsWith(".webm", ignoreCase = true) -> "video/webm"
        url.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        url.endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
        else -> null
    }
}

@Composable
internal fun CastCompatibilityDialog(
    eligibility: CastCompatibility,
    sourceUrl: String?,
    resumePositionMs: Long,
    onCastStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.player_cast_availability_title),
        message = stringResource(if (CastSenderSupported) Res.string.player_cast_sender_intro
            else Res.string.player_cast_not_configured),
    ) {
        // The reason for this source sits in its own panel so it reads as the answer.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(20.dp))
            Text(
                stringResource(when (eligibility) {
                    CastCompatibility.NoSource -> Res.string.player_cast_no_source
                    CastCompatibility.PeerToPeerOrLocal -> Res.string.player_cast_local
                    CastCompatibility.AuthenticatedHeaders -> Res.string.player_cast_headers
                    CastCompatibility.SeparateAudio -> Res.string.player_cast_separate_audio
                    CastCompatibility.NonHttpsSource -> Res.string.player_cast_https_required
                    CastCompatibility.SignedOrOpaqueUrl -> Res.string.player_cast_signed
                    CastCompatibility.PrivateOrUnknownHost -> Res.string.player_cast_private
                    CastCompatibility.UnsupportedMediaFormat -> Res.string.player_cast_unsupported_format
                    CastCompatibility.PotentiallyCompatibleDirectMedia -> Res.string.player_cast_candidate
                }),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
            )
        }
        DialogHint(stringResource(Res.string.player_cast_alternative))
        if (CastSenderSupported) {
            CastSenderControls(
                // Keep disconnect/remote controls reachable after a source switch,
                // but never give an ineligible source to the load action.
                sourceUrl = sourceUrl.takeIf {
                    eligibility == CastCompatibility.PotentiallyCompatibleDirectMedia
                }.orEmpty(),
                resumePositionMs = resumePositionMs,
                onCastStarted = onCastStarted,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

/** Android-only Google Cast sender; desktop/iOS deliberately do not advertise discovery. */
internal expect val CastSenderSupported: Boolean

@Composable
internal expect fun CastSenderControls(
    sourceUrl: String,
    resumePositionMs: Long,
    onCastStarted: () -> Unit,
)
