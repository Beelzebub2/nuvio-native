package com.nuvio.app.features.player.desktop

import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxMprisStateTest {
    private val active = LinuxMprisState(
        trackId = "$LinuxMprisObjectPath/Track/track_1",
        title = "Title",
        attached = true,
        isLoading = false,
        isPlaying = true,
        durationMs = 120_000L,
        positionMs = 90_000L,
    )

    @Test
    fun playbackStatusAndCapabilitiesFollowActualPlayerState() {
        val missing = LinuxMprisState()
        assertEquals("Stopped", missing.playbackStatus)
        assertFalse(missing.canPlay)
        assertFalse(missing.canPause)
        assertFalse(missing.canSeek)
        assertFalse(missing.canGoNext)
        assertEquals(0L, missing.positionUs)

        assertEquals("Playing", active.playbackStatus)
        assertTrue(active.canPlay)
        assertTrue(active.canPause)
        assertTrue(active.canSeek)
        assertEquals(90_000_000L, active.positionUs)

        assertEquals("Paused", active.copy(isPlaying = false).playbackStatus)
        assertEquals("Stopped", active.copy(isEnded = true).playbackStatus)
        assertEquals("Stopped", active.copy(isLoading = true).playbackStatus)
        assertFalse(active.copy(isLoading = true).canSeek)
        assertFalse(active.copy(durationMs = 0L).canSeek)
        assertFalse(active.canGoNext)
        assertTrue(active.copy(nextEpisodePlayable = true).canGoNext)
        assertEquals(
            setOf("CanGoNext"),
            linuxMprisChangedStateFields(active, active.copy(nextEpisodePlayable = true)),
        )
    }

    @Test
    fun seekUsesMicrosecondsRejectsStaleTrackAndOutOfBoundsAbsoluteValues() {
        assertEquals(45_000L, linuxMprisSeekTargetMs(active, 45_000_000L, relative = false, trackId = active.trackId))
        assertEquals(105_000L, linuxMprisSeekTargetMs(active, 15_000_000L, relative = true))
        assertEquals(65_000L, linuxMprisSeekTargetMs(active, -25_000_000L, relative = true))
        assertEquals(0L, linuxMprisSeekTargetMs(active, Long.MIN_VALUE, relative = true))
        assertEquals(120_000L, linuxMprisSeekTargetMs(active, Long.MAX_VALUE, relative = true))
        assertNull(linuxMprisSeekTargetMs(active, 0L, relative = false, trackId = LinuxMprisNoTrack))
        assertNull(linuxMprisSeekTargetMs(active, 0L, relative = false, trackId = "$LinuxMprisObjectPath/Track/track_0"))
        assertNull(linuxMprisSeekTargetMs(active, -1_000L, relative = false, trackId = active.trackId))
        assertNull(linuxMprisSeekTargetMs(active, 120_001_000L, relative = false, trackId = active.trackId))
        assertNull(linuxMprisSeekTargetMs(active.copy(isLoading = true), 1_000L, relative = true))
        assertEquals(0L, linuxMprisSeekTargetMs(active, 0L, relative = false, trackId = active.trackId))
        assertEquals(120_000L, linuxMprisSeekTargetMs(active, 120_000_000L, relative = false, trackId = active.trackId))
    }

    @Test
    fun propertyChangeEventsExcludeOrdinaryPositionUpdates() {
        assertEquals(emptySet<String>(), linuxMprisChangedStateFields(active, active.copy(positionMs = 91_000L)))
        assertEquals(
            setOf("PlaybackStatus"),
            linuxMprisChangedStateFields(active, active.copy(isPlaying = false)),
        )
        assertEquals(
            setOf("Metadata"),
            linuxMprisChangedStateFields(active, active.copy(title = "New title")),
        )
        assertEquals(
            setOf("Rate", "Volume"),
            linuxMprisChangedStateFields(active, active.copy(rate = 1.25, volume = 1.5)),
        )
        assertEquals(
            setOf("PlaybackStatus", "Metadata", "CanPlay", "CanPause", "CanSeek"),
            linuxMprisChangedStateFields(active, LinuxMprisState()),
        )
    }

    @Test
    fun onlyTrustedPublicArtworkWithoutQueryOrCredentialsIsPublished() {
        val permitted = "https://image.tmdb.org/t/p/w500/movie.jpg"
        assertEquals(permitted, linuxMprisPublicArtworkUrl(permitted))
        assertEquals(null, linuxMprisPublicArtworkUrl("https://image.tmdb.org/t/p/w500/movie.jpg?token=123"))
        assertEquals(null, linuxMprisPublicArtworkUrl("https://image.tmdb.org/t/p/w500/movie.jpg#fragment"))
        assertEquals(null, linuxMprisPublicArtworkUrl("https://user:pass@image.tmdb.org/t/p/movie.jpg"))
        assertEquals(null, linuxMprisPublicArtworkUrl("https://localhost:8090/video"))
        assertEquals(null, linuxMprisPublicArtworkUrl("https://192.168.0.8/poster.jpg"))
        assertEquals(null, linuxMprisPublicArtworkUrl("http://image.tmdb.org/t/p/w500/movie.jpg"))
        assertEquals(null, linuxMprisPublicArtworkUrl("https://example.org/stream.m3u8"))
        assertEquals(null, linuxMprisPublicArtworkUrl("file:///home/user/secret.jpg"))
    }

    @Test
    fun sessionBusAddressUsesExplicitEnvironmentOrXdgRuntimeFallback() {
        assertEquals(
            "unix:path=/run/user/1000/bus",
            linuxMprisSessionBusAddress(null, "/run/user/1000/"),
        )
        assertEquals(
            "unix:abstract=/tmp/dbus-abcd",
            linuxMprisSessionBusAddress("unix:abstract=/tmp/dbus-abcd", "/run/user/1000"),
        )
        assertNull(linuxMprisSessionBusAddress(null, null))
        assertNull(linuxMprisSessionBusAddress(null, "relative/runtime"))
    }

    @Test
    fun exportedInterfacesHaveExactMprisNamesAndObservableProperties() {
        assertEquals(
            "org.mpris.MediaPlayer2",
            LinuxMprisRoot::class.java.getAnnotation(DBusInterfaceName::class.java).value,
        )
        assertEquals(
            "org.mpris.MediaPlayer2.Player",
            LinuxMprisPlayer::class.java.getAnnotation(DBusInterfaceName::class.java).value,
        )
        assertTrue(
            LinuxMprisPlayer::class.java.getMethod("getMetadata")
                .isAnnotationPresent(DBusBoundProperty::class.java),
        )
        assertTrue(
            LinuxMprisPlayer::class.java.getMethod("getPosition")
                .isAnnotationPresent(DBusBoundProperty::class.java),
        )
        assertTrue(
            LinuxMprisRoot::class.java.getMethod("getIdentity")
                .isAnnotationPresent(DBusBoundProperty::class.java),
        )
    }

    @Test
    fun durationConversionSaturatesWithoutOverflow() {
        assertEquals(0L, (-123L).millisecondsToMicroseconds())
        assertEquals(123_000L, 123L.millisecondsToMicroseconds())
        assertTrue(Long.MAX_VALUE.millisecondsToMicroseconds() > 0L)
        assertTrue(Long.MAX_VALUE.millisecondsToMicroseconds() <= Long.MAX_VALUE)
    }
}
