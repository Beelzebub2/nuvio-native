package com.nuvio.app.features.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Addon metadata is untrusted; leave room for encryption and bound large/escaped Unicode menus. */
internal fun RemotePlayback.toRemoteWireJson(): String {
    var snapshot = copy(
        title = title.take(250),
        message = message.take(500),
        downloads = downloads.take(30).map { it.copy(title = it.title.take(100)) },
    )
    while (true) {
        val encoded = Json.encodeToString(snapshot)
        if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_REMOTE_FRAME / 2) return encoded
        snapshot = snapshot.copy(
            episodes = snapshot.episodes.take(snapshot.episodes.size / 2),
            subtitles = snapshot.subtitles.take(snapshot.subtitles.size / 2),
            sources = snapshot.sources.take(snapshot.sources.size / 2),
            downloads = snapshot.downloads.take(snapshot.downloads.size / 2),
            sourcePicker = snapshot.sourcePicker.copy(
                sources = snapshot.sourcePicker.sources.take(snapshot.sourcePicker.sources.size / 2),
                message = snapshot.sourcePicker.message.take(160),
            ),
            message = "Some choices are hidden because this list is very large. Use the desktop for the complete list.",
        )
    }
}
