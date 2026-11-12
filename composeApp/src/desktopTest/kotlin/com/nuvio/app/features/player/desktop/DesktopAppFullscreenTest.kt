package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppFullscreenTest {
    @Test
    fun `Wayland temporarily maximizes a floating frame and restores it once`() {
        val states = WaylandFullscreenFrameStates()
        val frame = Any()

        assertEquals(Frame.MAXIMIZED_BOTH, states.nextExtendedState(frame, Frame.NORMAL, true))
        assertEquals(null, states.nextExtendedState(frame, Frame.MAXIMIZED_BOTH, true))
        assertEquals(Frame.NORMAL, states.nextExtendedState(frame, Frame.MAXIMIZED_BOTH, false))
        assertEquals(null, states.nextExtendedState(frame, Frame.NORMAL, false))
    }

    @Test
    fun `Wayland preserves a frame that was already maximized`() {
        val states = WaylandFullscreenFrameStates()
        val frame = Any()

        assertEquals(null, states.nextExtendedState(frame, Frame.MAXIMIZED_BOTH, true))
        assertEquals(null, states.nextExtendedState(frame, Frame.MAXIMIZED_BOTH, false))
    }

    @Test
    fun `Wayland keeps restoration state scoped to the original frame`() {
        val states = WaylandFullscreenFrameStates()
        val first = Any()
        val second = Any()

        assertEquals(Frame.MAXIMIZED_BOTH, states.nextExtendedState(first, Frame.NORMAL, true))
        assertEquals(Frame.MAXIMIZED_BOTH, states.nextExtendedState(second, Frame.MAXIMIZED_VERT, true))
        assertEquals(Frame.MAXIMIZED_VERT, states.nextExtendedState(second, Frame.MAXIMIZED_BOTH, false))
        assertEquals(Frame.NORMAL, states.nextExtendedState(first, Frame.MAXIMIZED_BOTH, false))
    }

    @Test
    fun `Wayland is detected from either session environment variable`() {
        assertTrue(isWaylandDesktopSession("wayland-0", null))
        assertTrue(isWaylandDesktopSession(null, "wayland"))
        assertTrue(isWaylandDesktopSession(null, "Wayland"))
        assertTrue(isWaylandDesktopSession("wayland-1", "x11"))
    }

    @Test
    fun `X11 and unset session values retain the existing fullscreen path`() {
        assertFalse(isWaylandDesktopSession(null, "x11"))
        assertFalse(isWaylandDesktopSession("", "x11"))
        assertFalse(isWaylandDesktopSession("  ", null))
        assertFalse(isWaylandDesktopSession(null, null))
    }

    @Test
    fun `native fullscreen exit lets the window listener restore placement`() {
        val updates = mutableListOf<String>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Maximized,
            requestNativeFullscreenExit = {
                updates += "native"
                true
            },
            clearComposeFullscreen = { updates += "compose" },
            setStatePlacement = { updates += "state:$it" },
        )

        assertEquals(listOf("native"), updates)
    }

    @Test
    fun `compose fallback clears fullscreen before restoring maximized placement`() {
        val updates = mutableListOf<Pair<String, WindowPlacement>>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Maximized,
            requestNativeFullscreenExit = { false },
            clearComposeFullscreen = { updates += "compose" to WindowPlacement.Floating },
            setStatePlacement = { updates += "state" to it },
        )

        assertEquals(
            listOf(
                "compose" to WindowPlacement.Floating,
                "state" to WindowPlacement.Maximized,
            ),
            updates,
        )
    }

    @Test
    fun `fullscreen cannot be restored as its own exit placement`() {
        val updates = mutableListOf<Pair<String, WindowPlacement>>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Fullscreen,
            requestNativeFullscreenExit = { false },
            clearComposeFullscreen = { updates += "compose" to WindowPlacement.Floating },
            setStatePlacement = { updates += "state" to it },
        )

        assertEquals(
            listOf(
                "compose" to WindowPlacement.Floating,
                "state" to WindowPlacement.Floating,
            ),
            updates,
        )
    }
}
