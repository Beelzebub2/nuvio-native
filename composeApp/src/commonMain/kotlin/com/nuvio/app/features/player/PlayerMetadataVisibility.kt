package com.nuvio.app.features.player

/** A media-ready snapshot is not proof that the player has actually displayed anything. */
internal fun playbackHasStarted(
    previouslyStarted: Boolean,
    snapshot: PlayerPlaybackSnapshot,
): Boolean = previouslyStarted ||
    (snapshot.isPlaying && !snapshot.isLoading && snapshot.positionMs > 0L)

/** Do not mistake a prepared but still blank player for a video the user has watched. */
internal fun pauseMetadataEligible(
    enabled: Boolean,
    hasPlaybackStarted: Boolean,
    snapshot: PlayerPlaybackSnapshot,
    hasError: Boolean,
): Boolean = enabled && hasPlaybackStarted && !snapshot.isPlaying && !snapshot.isLoading &&
    snapshot.durationMs > 0L && !hasError
