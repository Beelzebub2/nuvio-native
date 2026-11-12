package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

object PlayerNextEpisodeRules {

    fun resolveNextEpisode(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
    ): MetaVideo? {
        if (currentSeason == null || currentEpisode == null) return null
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(
                compareBy<MetaVideo> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null
        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Boolean {
        if (durationMs <= 0L || positionMs < 0L || isShortPlaceholderDuration(durationMs)) return false
        val outroSegments = validOutros(skipIntervals, durationMs)

        if (thresholdMode == NextEpisodeThresholdMode.SMART) {
            return positionMs >= smartTriggerPositionMs(durationMs, skipIntervals, outroSegments)
        }

        if (outroSegments.isNotEmpty()) {
            // Only explicit scene metadata can postpone this prompt. Trailing padding,
            // previews or imprecise ED bounds must not postpone it until the file ends.
            val latestOutro = outroSegments.maxByOrNull { it.endTime }
            val postCreditsScene = latestOutro?.findFollowingPostCreditsScene(skipIntervals, durationMs)

            if (postCreditsScene != null) {
                val sceneEndMs = (postCreditsScene.endTime * 1_000.0).toLong()
                    .coerceAtMost(durationMs)
                val userTriggerMs = userThresholdPositionMs(
                    durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
                )
                return positionMs >= maxOf(sceneEndMs, userTriggerMs)
            }

            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs

            // Calculate the user's configured threshold as milliseconds from end.
            val userThresholdMs = when (thresholdMode) {
                NextEpisodeThresholdMode.SMART -> 0L // Handled before the manual modes.
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(85f, 100f)
                    ((1.0 - clampedPercent / 100.0) * durationMs).toLong()
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    (clampedMinutes * 60_000f).toLong()
                }
            }

            return if (postOutroGapMs > userThresholdMs) {
                when (thresholdMode) {
                    NextEpisodeThresholdMode.SMART -> false // Handled above.
                    NextEpisodeThresholdMode.PERCENTAGE -> {
                        val clampedPercent = thresholdPercent.coerceIn(85f, 100f)
                        (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                    }
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                        val remainingMs = durationMs - positionMs
                        remainingMs <= (clampedMinutes * 60_000f).toLong()
                    }
                }
            } else {
                // Outro ends close to the file end — fire at earliest outro start.
                positionMs / 1_000.0 >= outroSegments.minOf { it.startTime }
            }
        }

        // Fallback to the settings threshold when no outro data exists.
        return when (thresholdMode) {
            NextEpisodeThresholdMode.SMART -> false // Handled above.
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val clampedPercent = thresholdPercent.coerceIn(85f, 100f)
                (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                val remainingMs = durationMs - positionMs
                remainingMs <= (clampedMinutes * 60_000f).toLong()
            }
        }
    }

    /** Smart mode offers the card early, but never starts the next episode over the credits. */
    fun shouldAutomaticallyPlayNextEpisode(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Boolean {
        if (!shouldShowNextEpisodeCard(
                positionMs, durationMs, skipIntervals,
                thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd,
            )
        ) return false
        return when (thresholdMode) {
            NextEpisodeThresholdMode.SMART ->
                positionMs >= durationMs - SMART_AUTO_PLAY_LEAD_MS
            // Early manual suggestions must not auto-switch before the current
            // episode has safely passed the watched-progress threshold.
            NextEpisodeThresholdMode.PERCENTAGE ->
                positionMs >= (durationMs * MANUAL_AUTO_PLAY_MIN_PROGRESS).toLong()
            // An explicit time-based selection keeps its previous semantics.
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> true
        }
    }

    private fun validOutros(intervals: List<SkipInterval>, durationMs: Long): List<SkipInterval> =
        intervals.filter {
            it.type.trim().lowercase() in OUTRO_SEGMENT_TYPES &&
                it.startTime.isFinite() && it.endTime.isFinite() &&
                it.startTime >= 0.0 && it.endTime > it.startTime &&
                it.startTime * 1_000.0 < durationMs &&
                it.endTime * 1_000.0 <= durationMs + 10_000.0
        }

    private fun smartTriggerPositionMs(
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        outroSegments: List<SkipInterval>,
    ): Long {
        // A short show should never get a multi-minute popup. Longer episodes
        // get enough time to choose the next one, even without community markers.
        val fallbackLeadMs = (durationMs * 0.05).toLong()
            .coerceIn(SMART_MIN_FALLBACK_LEAD_MS, SMART_MAX_FALLBACK_LEAD_MS)
        val fallbackPositionMs = (durationMs - fallbackLeadMs).coerceAtLeast(0L)

        // Ignore suspicious markers hours/episodes before the end. A genuine
        // ending marker near the tail can show the button before the fallback.
        val earliestTrustedMarkerMs = maxOf(
            (durationMs * 0.75).toLong(),
            durationMs - SMART_MAX_OUTRO_LEAD_MS,
        )
        val markerPositionMs = outroSegments
            .map { (it.startTime * 1_000.0).toLong() }
            .filter { it in earliestTrustedMarkerMs until durationMs }
            .minOrNull()
        val showPositionMs = minOf(fallbackPositionMs, markerPositionMs ?: fallbackPositionMs)

        // An explicitly tagged post-credits scene must be allowed to finish.
        val sceneEndMs = outroSegments
            .mapNotNull { it.findFollowingPostCreditsScene(skipIntervals, durationMs) }
            .maxOfOrNull { (it.endTime * 1_000.0).toLong().coerceAtMost(durationMs) }
            ?: 0L
        return maxOf(showPositionMs, sceneEndMs)
    }

    private const val SMART_MIN_FALLBACK_LEAD_MS = 45_000L
    private const val SMART_MAX_FALLBACK_LEAD_MS = 90_000L
    private const val SMART_MAX_OUTRO_LEAD_MS = 180_000L
    private const val SMART_AUTO_PLAY_LEAD_MS = 10_000L
    private const val MANUAL_AUTO_PLAY_MIN_PROGRESS = 0.95

    fun hasEpisodeAired(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val dateStr = when {
            value.length >= 10 -> value.substring(0, 10)
            else -> return true
        }
        // Parse YYYY-MM-DD
        val parts = dateStr.split("-")
        if (parts.size != 3) return true
        val year = parts[0].toIntOrNull() ?: return true
        val month = parts[1].toIntOrNull() ?: return true
        val day = parts[2].toIntOrNull() ?: return true

        val today = currentDateComponents()
        return compareDate(year, month, day, today.year, today.month, today.day) <= 0
    }

    private fun compareDate(
        y1: Int, m1: Int, d1: Int,
        y2: Int, m2: Int, d2: Int,
    ): Int {
        if (y1 != y2) return y1.compareTo(y2)
        if (m1 != m2) return m1.compareTo(m2)
        return d1.compareTo(d2)
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    private fun SkipInterval.findFollowingPostCreditsScene(
        intervals: List<SkipInterval>,
        durationMs: Long,
    ): SkipInterval? {
        if (type.trim().lowercase() !in OUTRO_SEGMENT_TYPES) return null
        val explicit = intervals.filter {
            it.type.trim().lowercase() == "post-credits" &&
                it.startTime.isFinite() && it.endTime.isFinite() &&
                it.startTime >= endTime && it.endTime > it.startTime &&
                (durationMs <= 0L || it.startTime * 1000.0 < durationMs)
        }.minByOrNull { it.startTime }
        return explicit
    }

    private fun userThresholdPositionMs(
        durationMs: Long,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Long = when (thresholdMode) {
        NextEpisodeThresholdMode.SMART -> smartTriggerPositionMs(durationMs, emptyList(), emptyList())
        NextEpisodeThresholdMode.PERCENTAGE -> {
            val clampedPercent = thresholdPercent.coerceIn(85f, 100f)
            kotlin.math.ceil(durationMs * (clampedPercent / 100.0)).toLong()
        }
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
            val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
            durationMs - (clampedMinutes * 60_000f).toLong()
        }
    }
}

internal expect fun currentDateComponents(): DateComponents

data class DateComponents(val year: Int, val month: Int, val day: Int)
