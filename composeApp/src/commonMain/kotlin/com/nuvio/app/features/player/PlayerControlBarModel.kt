package com.nuvio.app.features.player

import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.math.roundToInt

/** An on/off playback option, such as auto skip intro, shown in the Playback options menu. */
internal data class PlayerControlToggle(
    val label: String,
    val checked: Boolean,
    val icon: ImageVector,
    val onCheckedChange: (Boolean) -> Unit,
)

/** Skip intervals as the chapter types the desktop and phone seek bars understand. */
internal fun List<SkipInterval>.toPlayerControlSkipSegments(): List<PlayerControlSkipSegment> =
    mapNotNull { interval ->
        val segmentType = when (AutoSkipSegmentType.fromSkipIntervalType(interval.type)) {
            AutoSkipSegmentType.INTRO -> "intro"
            AutoSkipSegmentType.RECAP -> "recap"
            AutoSkipSegmentType.OUTRO,
            AutoSkipSegmentType.MOVIE_CREDITS -> "outro"
            null -> return@mapNotNull null
        }
        if (!interval.startTime.isFinite() || !interval.endTime.isFinite() ||
            interval.startTime < 0 || interval.endTime <= interval.startTime
        ) return@mapNotNull null
        PlayerControlSkipSegment(
            type = segmentType,
            startMs = (interval.startTime * 1000).toLong(),
            endMs = (interval.endTime * 1000).toLong(),
        )
    }.sortedBy { it.startMs }

internal enum class PlayerTimelineChapterType { Content, Recap, Intro, Credits }

internal data class PlayerTimelineChapter(
    val type: PlayerTimelineChapterType,
    val startMs: Long,
    val endMs: Long,
)

private const val MinimumChapterMs = 1_000L

/**
 * Splits the seek bar into chapters the same way the desktop controls do: skip segments of at
 * least a second, in order and without overlap, with the time between them as content.
 */
internal fun buildPlayerTimelineChapters(
    durationMs: Long,
    segments: List<PlayerControlSkipSegment>,
): List<PlayerTimelineChapter> {
    if (durationMs <= 0L) return listOf(PlayerTimelineChapter(PlayerTimelineChapterType.Content, 0L, 1L))
    val kept = segments
        .mapNotNull { segment ->
            val type = when (segment.type.lowercase()) {
                "recap" -> PlayerTimelineChapterType.Recap
                "intro" -> PlayerTimelineChapterType.Intro
                "outro" -> PlayerTimelineChapterType.Credits
                else -> return@mapNotNull null
            }
            val start = segment.startMs.coerceAtLeast(0L)
            val end = segment.endMs.coerceAtMost(durationMs)
            if (end - start < MinimumChapterMs) null else PlayerTimelineChapter(type, start, end)
        }
        .sortedBy { it.startMs }
        .fold(mutableListOf<PlayerTimelineChapter>()) { acc, chapter ->
            if (acc.isEmpty() || chapter.startMs >= acc.last().endMs) acc.add(chapter)
            acc
        }
    val chapters = mutableListOf<PlayerTimelineChapter>()
    var cursor = 0L
    kept.forEach { chapter ->
        if (chapter.startMs - cursor >= MinimumChapterMs) {
            chapters += PlayerTimelineChapter(PlayerTimelineChapterType.Content, cursor, chapter.startMs)
        }
        chapters += chapter
        cursor = chapter.endMs
    }
    if (chapters.isEmpty() || durationMs - cursor >= MinimumChapterMs) {
        chapters += PlayerTimelineChapter(PlayerTimelineChapterType.Content, cursor, maxOf(cursor + 1L, durationMs))
    }
    return chapters
}

internal fun List<PlayerTimelineChapter>.chapterAt(positionMs: Long): PlayerTimelineChapter? =
    firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

/** The resolution chip shown next to the episode line; letterboxed 1920x800 still reads 1080p. */
internal fun playerVideoQualityLabel(width: Int, height: Int): String? {
    if (height <= 0) return null
    val effective = maxOf(height, (width.coerceAtLeast(0) * 9.0 / 16.0).roundToInt())
    return when {
        effective >= 2000 -> "4K"
        effective >= 1400 -> "1440p"
        effective >= 1000 -> "1080p"
        effective >= 700 -> "720p"
        else -> "${height}p"
    }
}

/** Buttons that live in the bar when they fit and fold into More when they do not. */
internal enum class PlayerBarItem { NextEpisode, Episodes, Sources, Subtitles, Audio, Speed, Aspect, PlaybackOptions }

internal data class PlayerBarPlan(
    /** Below 600 dp the play button shrinks, status chips hide and header tools move into More. */
    val compact: Boolean,
    val inline: Set<PlayerBarItem>,
) {
    fun isInline(item: PlayerBarItem) = item in inline
}

internal const val PlayerBarCompactWidthDp = 600f

// Bar geometry in dp, matching the desktop control bar.
internal const val PlayerBarButtonDp = 44f
internal const val PlayerBarPlayDp = 56f
internal const val PlayerBarCompactPlayDp = 48f
private const val BarGapDp = 4f
private const val BarGroupGapDp = 16f
private const val BarDividerDp = 17f
private const val PlayMarginDp = 8f
private const val CompactPlayMarginDp = 4f

/** The order buttons claim space in; anything left over is still one tap away in More. */
private val BarPriority = listOf(
    PlayerBarItem.Subtitles,
    PlayerBarItem.Audio,
    PlayerBarItem.Sources,
    PlayerBarItem.Episodes,
    PlayerBarItem.NextEpisode,
    PlayerBarItem.Speed,
    PlayerBarItem.Aspect,
    PlayerBarItem.PlaybackOptions,
)

private val LibraryItems = setOf(PlayerBarItem.Episodes, PlayerBarItem.Sources)
private val TrackItems = setOf(PlayerBarItem.Subtitles, PlayerBarItem.Audio, PlayerBarItem.Speed, PlayerBarItem.Aspect)

internal fun planPlayerBar(widthDp: Float, available: Set<PlayerBarItem>): PlayerBarPlan {
    val compact = widthDp < PlayerBarCompactWidthDp
    var inline = emptySet<PlayerBarItem>()
    BarPriority.filter { it in available }.forEach { item ->
        val candidate = inline + item
        if (playerBarWidthDp(candidate, compact) <= widthDp) inline = candidate
    }
    return PlayerBarPlan(compact = compact, inline = inline)
}

/** Width of the bar with [inline] buttons shown: transport on the left, tools and More on the right. */
internal fun playerBarWidthDp(inline: Set<PlayerBarItem>, compact: Boolean): Float {
    val step = PlayerBarButtonDp + BarGapDp
    val play = if (compact) PlayerBarCompactPlayDp + CompactPlayMarginDp else PlayerBarPlayDp + PlayMarginDp
    val transportCount = 2 + if (PlayerBarItem.NextEpisode in inline) 1 else 0
    val left = play + BarGapDp + transportCount * step - BarGapDp
    val rightItems = inline.count { it != PlayerBarItem.NextEpisode }
    val groups = listOf(
        inline.any { it in LibraryItems },
        inline.any { it in TrackItems },
        true, // Playback options and More
    ).count { it }
    val right = rightItems * step + PlayerBarButtonDp + (groups - 1) * (BarDividerDp + BarGapDp)
    return left + BarGroupGapDp + right
}
