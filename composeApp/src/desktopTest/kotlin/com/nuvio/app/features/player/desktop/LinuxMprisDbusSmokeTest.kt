package com.nuvio.app.features.player.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Introspectable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Run on Linux inside dbus-run-session. Confirms real bus registration, generated
 * introspection, bound property requests and safe calls with no active player.
 */
class LinuxMprisDbusSmokeTest {
    @Test(timeout = 25_000)
    fun exportsBothInterfacesAndHandlesInactivePlaybackOverSessionBus() {
        assumeTrue(
            DesktopHostOs.current == DesktopHostOs.LINUX &&
                !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank(),
        )
        LinuxMprisService.start()
        val client = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        try {
            val introspection = client.getRemoteObject(
                LinuxMprisBusName,
                LinuxMprisObjectPath,
                Introspectable::class.java,
            ).Introspect()
            assertTrue(introspection.contains("org.mpris.MediaPlayer2\""))
            assertTrue(introspection.contains("org.mpris.MediaPlayer2.Player\""))
            assertTrue(introspection.contains("name=\"PlaybackStatus\""))
            assertTrue(introspection.contains("name=\"Metadata\""))
            assertTrue(introspection.contains("name=\"Position\""))

            val root = client.getRemoteObject(
                LinuxMprisBusName,
                LinuxMprisObjectPath,
                LinuxMprisRoot::class.java,
            )
            assertEquals("Starlit", root.getIdentity())
            assertEquals("starlit", root.getDesktopEntry())
            assertFalse(root.getCanQuit())
            assertFalse(root.getHasTrackList())

            val player = client.getRemoteObject(
                LinuxMprisBusName,
                LinuxMprisObjectPath,
                LinuxMprisPlayer::class.java,
            )
            assertEquals("Stopped", player.getPlaybackStatus())
            assertEquals(0L, player.getPosition())
            assertTrue(player.getMetadata().isEmpty())
            assertFalse(player.getCanPlay())
            assertFalse(player.getCanSeek())
            assertFalse(player.getCanGoNext())
            assertFalse(player.getCanGoPrevious())
            assertTrue(player.getCanControl())

            player.Play()
            player.Pause()
            player.PlayPause()
            player.Stop()
            player.Next()
            player.Previous()
            player.Seek(1_000_000L)
            player.SetPosition(DBusPath(LinuxMprisNoTrack), 1_000_000L)
            assertEquals("Stopped", player.getPlaybackStatus())
        } finally {
            runCatching { client.close() }
            LinuxMprisService.shutdown()
        }
    }
}
