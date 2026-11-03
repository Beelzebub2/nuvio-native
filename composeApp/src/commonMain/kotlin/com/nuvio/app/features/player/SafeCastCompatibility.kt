package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
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
 * A candidate result is not permission to cast. Real casting still requires
 * sender SDK integration, a properly configured Cast receiver, rights checks
 * and explicit device selection. No network/receiver calls happen here.
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
    if ('?' in url || '#' in url || url.length > 2048 || '\\' in url)
        return CastCompatibility.SignedOrOpaqueUrl

    val authority = url.substringAfter("://").substringBefore('/')
    val host = authority.lowercase()
    if (host.length !in 4..253 || !host.contains('.') ||
        !host.matches(Regex("[a-z0-9.-]+")) ||
        host.startsWith('.') || host.endsWith('.') || ".." in host ||
        host.all { it in '0'..'9' || it == '.' } ||
        listOf(".local", ".localhost", ".lan", ".internal", ".test", ".invalid", ".onion")
            .any { host.endsWith(it) })
        return CastCompatibility.PrivateOrUnknownHost

    val path = url.substringAfter(authority, "").substringBefore('?')
    if (!listOf(".m3u8", ".mpd", ".mp4", ".webm").any { path.lowercase().endsWith(it) })
        return CastCompatibility.UnsupportedMediaFormat

    return CastCompatibility.PotentiallyCompatibleDirectMedia
}

@Composable
internal fun CastCompatibilityDialog(
    eligibility: CastCompatibility,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.player_cast_availability_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.player_cast_not_configured))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(Res.string.player_cast_alternative),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
        },
    )
}
