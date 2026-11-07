package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PreferredAudioTrackTest {
    @Test
    fun portugueseTwoLetterPreferenceMatchesThreeLetterContainerTag() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "Portuguese", "por"),
        )
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun primaryPreferenceWinsEvenWhenSecondaryTrackComesFirst() {
        val tracks = listOf(
            AudioTrack(0, "1", "Slovak", "slo"),
            AudioTrack(1, "2", "Czech", "cze"),
        )
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
    }

    @Test
    fun secondaryPreferenceMatchesAlternativeIso6392TagWhenPrimaryIsMissing() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "Slovak", "slk"),
        )
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
    }

    @Test
    fun exactRegionalPreferenceBeatsEarlierBaseLanguageTrack() {
        val tracks = listOf(
            AudioTrack(0, "1", "Portuguese (Portugal)", "por"),
            AudioTrack(1, "2", "Brazilian Portuguese", "pt-BR"),
        )
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt-BR")))
        assertEquals(0, findPreferredAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun baseLanguageFallsBackToRegionalAudioWhenNoExactTrackExists() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "Portuguese Brazil", "pt-BR"),
        )
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun untaggedTracksCanMatchLanguageNameButWronglyTaggedTracksCannot() {
        val tracks = listOf(
            AudioTrack(0, "1", "Portuguese dub", "eng"),
            AudioTrack(1, "2", "Portuguese", null),
        )
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt")))
        assertEquals(-1, findPreferredAudioTrackIndex(tracks.take(1), listOf("pt")))
    }

    @Test
    fun unmatchedPreferencesLeaveThePlayersOriginalTrackUntouched() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "Unknown", null),
        )
        assertEquals(-1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
        assertEquals(-1, findPreferredAudioTrackIndex(emptyList(), listOf("pt")))
    }
}
