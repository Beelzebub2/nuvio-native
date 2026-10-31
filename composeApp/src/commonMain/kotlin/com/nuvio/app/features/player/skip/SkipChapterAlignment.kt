package com.nuvio.app.features.player.skip

import kotlin.math.abs

internal fun List<SkipInterval>.alignOpeningsToChapters(chapterStarts: List<Double>): List<SkipInterval> {
    val chapters = chapterStarts.filter { it.isFinite() && it >= 0 }.distinct().sorted().zipWithNext()
    if (chapters.isEmpty()) return this
    return map { interval ->
        if (interval.provider !in setOf("aniskip", "animeskip") ||
            interval.type !in setOf("op", "mixed-op")
        ) return@map interval
        val length = interval.endTime - interval.startTime
        // Refine an existing anime opening estimate only. Unnamed chapters alone
        // cannot identify an intro. Allow small release offsets, not other scenes.
        val chapter = chapters.singleOrNull { (start, end) ->
            abs(start - interval.startTime) <= 20.0 &&
                abs(end - interval.endTime) <= 20.0 &&
                abs((end - start) - length) <= 5.0
        }
        chapter?.let { interval.copy(startTime = it.first, endTime = it.second) } ?: interval
    }
}
