package com.nuvio.app.features.downloads

/**
 * Transfer speed averaged over the last [windowMs], so a torrent that delivers in bursts shows a steady
 * rate and a believable time left instead of jumping with every piece.
 */
internal class DownloadRateMeter(private val windowMs: Long = 8_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Records [bytes] received so far at [nowMs] and returns the average bytes per second. */
    fun sample(nowMs: Long, bytes: Long): Long {
        // Fewer bytes than before means the transfer restarted from an earlier offset.
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
        samples.addLast(nowMs to bytes)
        // Keep the newest sample that is at least a full window old, so the average spans the whole window.
        while (samples.size > 2 && nowMs - samples[1].first >= windowMs) samples.removeFirst()
        val (startMs, startBytes) = samples.first()
        val elapsed = nowMs - startMs
        return if (elapsed <= 0L) 0L else (bytes - startBytes) * 1000 / elapsed
    }
}
