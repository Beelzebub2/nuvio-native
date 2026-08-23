package io.github.kdroidfilter.composemediaplayer.linux

/**
 * Owns the durable end-of-playback state needed after the one-shot native EOS
 * notification has been consumed by the UI update loop.
 *
 * Frame-update jobs carry the generation they started under so a late EOS from
 * a cancelled job cannot mark replacement media as ended.
 */
internal class LinuxPlaybackCompletion {
    private val lock = Any()
    private var generation = 0L
    private var endedGeneration: Long? = null

    fun captureGeneration(): Long = synchronized(lock) { generation }

    fun consumeEndIfCurrent(
        observedGeneration: Long,
        consumeEnd: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            generation == observedGeneration && consumeEnd()
        }

    fun markEnded(observedGeneration: Long): Boolean =
        synchronized(lock) {
            if (generation != observedGeneration) {
                false
            } else {
                endedGeneration = observedGeneration
                true
            }
        }

    fun runIfCurrent(
        observedGeneration: Long,
        action: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (generation != observedGeneration) {
                false
            } else {
                action()
                true
            }
        }

    fun reset() {
        synchronized(lock) {
            generation += 1
            endedGeneration = null
        }
    }

    fun resume(
        seekToStart: () -> Unit,
        play: () -> Unit,
    ) {
        synchronized(lock) {
            if (endedGeneration == generation) {
                endedGeneration = null
                generation += 1
                seekToStart()
            }
            play()
        }
    }
}
