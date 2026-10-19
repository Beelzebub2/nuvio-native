package com.nuvio.app.core.ui

import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.NavBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNavigationBarTest {
    @Test
    fun desktopLayoutPreferencesKeepTopBarAndSidebar() {
        assertEquals(DesktopNavigationLayout.TopBar, DesktopNavigationLayout.fromName("TopBar"))
        assertEquals(DesktopNavigationLayout.TopBar, DesktopNavigationLayout.fromName("topbar"))
        assertEquals(DesktopNavigationLayout.Sidebar, DesktopNavigationLayout.fromName("Sidebar"))
        assertEquals(DesktopNavigationLayout.Default, DesktopNavigationLayout.fromName(null))
    }

    @Test
    fun desktopKeepsLabelsUnlessCompact() {
        assertEquals(1f, desktopNavigationLabelFraction(NavBarStyle.ADAPTIVE))
        assertEquals(1f, desktopNavigationLabelFraction(NavBarStyle.EXPANDED))
        assertEquals(1f, desktopNavigationLabelFraction(NavBarStyle.CLASSIC))
        assertEquals(0f, desktopNavigationLabelFraction(NavBarStyle.COMPACT))
    }
}
