package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipIntroResolutionTest {
    @Test
    fun resolvesExactAnimeSeasonWithoutSimklAndRefusesAmbiguousCours() {
        val first = """{"imdb":"tt5370118","media":"TV","thetvdb-season":1,"myanimelist":30831,"anilist":21202}"""
        val second = """{"imdb":"tt5370118","media":"TV","thetvdb-season":2,"myanimelist":32937,"anilist":21699}"""
        val special = """{"imdb":"tt5370118","media":"OVA","thetvdb-season":0,"myanimelist":34626}"""
        assertEquals(AnimeSeasonIds("32937", "21699"), parseAnimeSeasonIds("[$first,$second,$special]", "tt5370118", 2))
        assertNull(parseAnimeSeasonIds("[$first]", "tt5370118", 2))
        assertNull(parseAnimeSeasonIds("[$second,$second]", "tt5370118", 2))
        assertNull(parseAnimeSeasonIds("[$second]", "tt0000000", 2))
    }

    @Test
    fun usesAnimeOpeningWhilePreservingIntroDbRecapAndEnding() {
        val coldOpen = SkipInterval(0.0, 107.02, "intro", "introdb")
        val recap = SkipInterval(0.0, 10.0, "recap", "introdb")
        val ending = SkipInterval(1320.444, 1410.451, "outro", "introdb")
        val opening = SkipInterval(107.0, 197.0, "op", "animeskip")
        val aniSkip = SkipInterval(107.662, 195.513, "op", "aniskip")
        assertEquals(
            listOf(opening, recap, ending),
            SkipIntroRepository.mergeByPriority(
                listOf(coldOpen, recap, ending),
                listOf(opening, ending.copy(startTime = 1300.0, provider = "animeskip")),
                listOf(aniSkip),
            ),
        )
        assertEquals(listOf(coldOpen), SkipIntroRepository.mergeByPriority(listOf(coldOpen), emptyList(), emptyList()))
        for (invalid in listOf(
            aniSkip.copy(startTime = -1.0), aniSkip.copy(endTime = Double.MAX_VALUE),
            aniSkip.copy(endTime = Double.NaN), aniSkip.copy(endTime = aniSkip.startTime),
        )) {
            assertEquals(listOf(coldOpen), SkipIntroRepository.mergeByPriority(listOf(coldOpen), emptyList(), listOf(invalid)))
        }
    }

    @Test
    fun incompleteAnimeSkipMarkerFallsBackToAniSkipWithoutSkippingTheEpisode() {
        val incomplete = AnimeSkipEpisode(timestamps = listOf(timestamp(18.0, "Intro"))).skipIntervals()
        assertTrue(incomplete.isEmpty())
        val fallback = SkipInterval(114.546, 204.546, "op", "aniskip")
        assertEquals(listOf(fallback), SkipIntroRepository.mergeByPriority(
            listOf(SkipInterval(0.0, 99.02, "intro", "introdb")), incomplete, listOf(fallback),
        ))
    }

    @Test
    fun alignsAllDownloadedKonosubaOpeningsToTheirOwnFiles() {
        // Live provider responses and ffprobe chapter boundaries from S02 E2/3/4/5/8/9/10.
        // E4 has an isolated Anime-Skip marker, and AniSkip belongs to a release shifted 15s.
        val cases = listOf(
            doubleArrayOf(107.024, 197.489, 107.0, 197.0, 107.662, 195.513),
            doubleArrayOf(54.054, 144.519, 52.0, 143.0, 54.663, 142.844),
            doubleArrayOf(99.016, 189.481, 18.0, -1.0, 114.546, 204.546),
            doubleArrayOf(65.983, 156.448, -1.0, -1.0, 66.269, 154.614),
            doubleArrayOf(50.050, 140.515, 50.0, 141.0, 50.932, 138.486),
            doubleArrayOf(89.506, 180.055, 87.0, 178.0, 90.376, 177.737),
            doubleArrayOf(18.018, 108.483, -1.0, -1.0, 18.81, 106.582),
        )
        for (case in cases) {
            val (start, end, animeStart, animeEnd, aniStart) = case
            val markers = if (animeStart < 0) emptyList() else listOfNotNull(
                timestamp(animeStart, "Intro"),
                animeEnd.takeIf { it > 0 }?.let { timestamp(it, "Canon") },
            )
            val resolved = SkipIntroRepository.mergeByPriority(
                listOf(SkipInterval(0.0, start, "intro", "introdb")),
                AnimeSkipEpisode(timestamps = markers).skipIntervals(),
                listOf(SkipInterval(aniStart, case[5], "op", "aniskip")),
            ).alignOpeningsToChapters(listOf(0.0, start, end, end + 500.0))
            assertEquals(start, resolved.single().startTime)
            assertEquals(end, resolved.single().endTime)
        }
    }

    @Test
    fun neverInfersAnOpeningFromChaptersAndRefinesEachSourceFromUnchangedProviderData() {
        val original = listOf(SkipInterval(114.546, 204.546, "op", "aniskip"))
        assertEquals(99.016, original.alignOpeningsToChapters(listOf(0.0, 99.016, 189.481)).single().startTime)
        assertEquals(114.546, original.alignOpeningsToChapters(listOf(0.0, 114.546, 204.546)).single().startTime)
        assertEquals(original, original.alignOpeningsToChapters(emptyList()))
        assertEquals(original, original.alignOpeningsToChapters(listOf(0.0, 500.0, 590.0)))
        assertEquals(original, original.alignOpeningsToChapters(listOf(0.0, 99.0, 150.0)))
        assertTrue(emptyList<SkipInterval>().alignOpeningsToChapters(listOf(0.0, 90.0)).isEmpty())
        val generic = listOf(SkipInterval(0.0, 99.0, "intro", "introdb"))
        assertEquals(generic, generic.alignOpeningsToChapters(listOf(0.0, 99.016, 189.481)))
    }

    private fun timestamp(at: Double, name: String) = AnimeSkipTimestamp(at, AnimeSkipTimestampType(name))
}
