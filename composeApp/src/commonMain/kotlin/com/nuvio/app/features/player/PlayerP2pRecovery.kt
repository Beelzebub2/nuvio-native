package com.nuvio.app.features.player

import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingState

internal fun PlayerScreenRuntime.currentP2pStreamRequest(): P2pStreamRequest? =
    activeTorrentInfoHash?.let { hash ->
        P2pStreamRequest(
            infoHash = hash,
            fileIdx = activeTorrentFileIdx,
            filename = activeTorrentFilename,
            trackers = activeTorrentTrackers,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
        )
    }

/** A broken torrent reader can report EOF with its position reset to zero. */
internal fun normalizeP2pEnd(snapshot: PlayerPlaybackSnapshot, previous: PlayerPlaybackSnapshot): PlayerPlaybackSnapshot {
    if (!snapshot.isEnded) return snapshot
    val duration = snapshot.durationMs.takeIf { it > 0L } ?: previous.durationMs
    val position = snapshot.positionMs.takeIf { it > 0L } ?: previous.positionMs
    return snapshot.copy(
        durationMs = duration,
        positionMs = position,
        isEnded = duration > 0L && position >= duration - 2_000L,
        isLoading = duration <= 0L || position < duration - 2_000L,
    )
}

/** The logical playback identity stays the same when the local engine gets a new port. */
internal fun PlayerScreenRuntime.applyP2pRecoveryState(state: P2pStreamingState) {
    fun matches(request: P2pStreamRequest?): Boolean {
        val current = currentP2pStreamRequest() ?: return false
        return request != null && request.infoHash.equals(current.infoHash, ignoreCase = true) &&
            request.copy(infoHash = current.infoHash) == current
    }

    when (state) {
        is P2pStreamingState.Connecting -> {
            if (state.phase == "recovering_engine" && matches(state.request)) prepareP2pReconnect()
        }
        is P2pStreamingState.Streaming -> {
            if (!matches(state.request) || state.localUrl == p2pResolvedSourceUrl) return
            // StateFlow may conflate a very quick Connecting -> Streaming transition.
            if (p2pResolvedSourceUrl != null) prepareP2pReconnect()
            activeSourceAudioUrl = null
            activeSourceHeaders = emptyMap()
            activeSourceResponseHeaders = emptyMap()
            p2pResolvedSourceUrl = state.localUrl
            p2pRecoveryInProgress = false
            errorMessage = null
        }
        is P2pStreamingState.Error -> {
            if (activeTorrentInfoHash != null && p2pResolvedSourceUrl != null) prepareP2pReconnect()
        }
        else -> Unit
    }
}

private fun PlayerScreenRuntime.prepareP2pReconnect() {
    if (p2pRecoveryInProgress) return
    p2pRecoveryInProgress = true
    if (playbackSnapshot.positionMs > 0L) {
        activeInitialPositionMs = scrubbingPositionMs ?: playbackSnapshot.positionMs
        activeInitialProgressFraction = null
    }
    initialSeekApplied = activeInitialPositionMs <= 0L && (activeInitialProgressFraction ?: 0f) <= 0f
    p2pResolvedSourceUrl = null
    playerController = null
    playerControllerSourceUrl = null
    playbackSnapshot = PlayerPlaybackSnapshot()
    playbackSnapshotKey = null
    errorMessage = null
    initialLoadCompleted = false
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    cancelNextEpisodeAutoPlay()
    resetTrackSelectionState()
    // shouldPlay deliberately retains the user's paused/playing choice.
}
