package com.nuvio.app.features.remote

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeFirewall(initial: RemoteFirewallState) : RemoteFirewallAccess {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<RemoteFirewallState> = flow
    var refreshes = 0
    var requests = 0
    var publicEthernetRequests = 0
    var settingsOpened = 0
    override fun refresh() { refreshes++ }
    override fun requestAccess() { requests++; flow.value = RemoteFirewallState.Requesting }
    override fun requestPublicEthernetAccess() {
        publicEthernetRequests++
        flow.value = RemoteFirewallState.PublicEthernetFailed
    }
    override fun openNetworkSettings() { settingsOpened++ }
}

@OptIn(ExperimentalTestApi::class)
class RemoteControllerUiTest {
    @AfterTest
    fun reset() {
        DesktopRemote.visible.value = false
        DesktopRemote.connected.value = false
        DesktopRemote.activeDesktop.value = null
        DesktopRemote.connecting.value = false
        DesktopRemote.scanning.value = false
        DesktopRemote.pairing.value = null
        DesktopRemote.hostReady.value = false
        DesktopRemote.hostFailure.value = false
        DesktopRemote.discoveryReady.value = false
        DesktopRemote.remotePlayback.value = RemotePlayback()
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.bluetoothDesktops.value = emptyList()
        DesktopRemote.remembered.value = emptyList()
    }

    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/remote-ui-capture/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun ComposeUiTest.show(desktopLayout: Boolean, firewall: RemoteFirewallAccess = NoRemoteFirewallAccess) {
        DesktopRemote.visible.value = true
        setContent {
            NuvioTheme {
                DesktopRemoteDialogs(
                    localAddresses = { listOf("192.168.1.50") },
                    desktopLayout = desktopLayout,
                    firewall = firewall,
                    inline = true,
                )
            }
        }
    }

    @Test
    fun phoneControllerIsAFullPageWithConnectOptions() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.status.value = "Connect to a desktop on the same Wi-Fi or Ethernet network."
        DesktopRemote.desktops.value = listOf(RemoteDesktop("192.168.1.50", REMOTE_LAN_PORT, "Living room PC"))
        DesktopRemote.remembered.value = listOf(RememberedRemoteDevice("Office PC", "192.168.1.60", 0L, "AA:BB:CC:DD:EE:FF"))
        show(desktopLayout = false)
        onNodeWithTag("pc-controller-screen").assertIsDisplayed()
        onNodeWithText("Find your Starlit PC").assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsDisplayed()
        onNodeWithText("Living room PC").assertIsDisplayed()
        capture("pc-controller-find")
        onNodeWithTag("desktop-remote-manual-ip").assertDoesNotExist()
        onNodeWithTag("desktop-remote-enter-address").performClick()
        onNodeWithTag("desktop-remote-manual-ip").performScrollTo().assertIsDisplayed()
        onNodeWithText("Previously paired PCs".uppercase()).performScrollTo().assertIsDisplayed()
        onNodeWithText("Can't find your PC?").performScrollTo().assertIsDisplayed()
        capture("pc-controller-find-bottom")
    }

    @Test
    fun phoneControllerShowsConnectionProgressAndHoldsOtherOptions() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connecting.value = true
        DesktopRemote.status.value = "Connecting to Living room PC…"
        DesktopRemote.desktops.value = listOf(RemoteDesktop("192.168.1.50", REMOTE_LAN_PORT, "Living room PC"))
        show(desktopLayout = false)
        onNodeWithText("Connecting to your PC").assertIsDisplayed()
        onNodeWithText("Connecting").assertIsDisplayed()
        onNodeWithText("Connecting to Living room PC…").assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsNotEnabled()
        capture("pc-controller-connecting")

        // Searching shows its own progress, and the result replaces it.
        runOnIdle {
            DesktopRemote.connecting.value = false
            DesktopRemote.scanning.value = true
            DesktopRemote.status.value = "Searching nearby Bluetooth PCs…"
        }
        onNodeWithText("Looking for your PC").assertIsDisplayed()
        onNodeWithText("Searching").assertIsDisplayed()
        runOnIdle {
            DesktopRemote.scanning.value = false
            DesktopRemote.status.value = "Choose your PC."
        }
        onNodeWithText("Find your Starlit PC").assertIsDisplayed()
        onNodeWithText("Choose your PC.").assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsEnabled()
    }

    @Test
    fun remoteVolumeSliderShowsPcVolumeUpToTwoHundredPercent() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connected.value = true
        DesktopRemote.remotePlayback.value = RemotePlayback(
            playbackId = "volume-test", volume = 1.5f, volumeMax = REMOTE_MAX_VOLUME_LEVEL,
        )
        show(desktopLayout = false)
        val slider = onNodeWithTag("remote-volume-slider").performScrollTo().assertIsDisplayed()
        onNodeWithTag("remote-volume-value").assertTextEquals("150%")
        assertEquals(
            2f,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive,
        )
        runOnIdle { DesktopRemote.remotePlayback.value = DesktopRemote.remotePlayback.value.copy(volume = 2f) }
        onNodeWithTag("remote-volume-value").assertTextEquals("200%")

        // Mixed-version pairing remains usable; a legacy PC does not advertise 200% commands.
        runOnIdle {
            DesktopRemote.remotePlayback.value = DesktopRemote.remotePlayback.value.copy(
                volume = 0.75f, volumeMax = 1f,
            )
        }
        onNodeWithTag("remote-volume-value").assertTextEquals("75%")
        assertEquals(
            1f,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive,
        )
    }

    @Test
    fun phoneControllerShowsSelectedPcAudioTrackAndOpensAudioChoices() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connected.value = true
        DesktopRemote.remotePlayback.value = RemotePlayback(
            playbackId = "track-session",
            title = "Playback",
            audioTracks = listOf(
                RemoteChoice(remoteAudioTrackId(0, "2"), "English stereo", selected = true),
                RemoteChoice(remoteAudioTrackId(1, "4"), "Japanese surround"),
            ),
        )
        show(desktopLayout = false)
        onNodeWithTag("remote-audio-tracks").performScrollTo().assertIsDisplayed().assertIsEnabled()
        onNodeWithText("English stereo").assertIsDisplayed()
        capture("pc-controller-audio-tracks")
        onNodeWithTag("remote-audio-tracks").performClick()
        onNodeWithText("Japanese surround").assertIsDisplayed()
    }

    @Test
    fun legacyPcDoesNotDisplayUnsupportedAudioSelector() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connected.value = true
        DesktopRemote.remotePlayback.value = RemotePlayback(playbackId = "legacy-session", title = "Playback")
        show(desktopLayout = false)
        onNodeWithTag("remote-audio-tracks").assertDoesNotExist()
        capture("pc-controller-no-audio-tracks")
    }

    @Test
    fun connectedControllerSplitsRemoteDownloadsAndPcsIntoTabs() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connected.value = true
        DesktopRemote.status.value = "Connected to Living room PC"
        DesktopRemote.remotePlayback.value = RemotePlayback(
            playbackId = "playback-1",
            title = "Severance · S2 E3 · Who Is Alive?",
            playing = true,
            position = 1_234_000,
            duration = 3_120_000,
            volume = 0.62f,
            skip = true,
            skipIntro = true,
            nextEpisodeAvailable = true,
            fullscreenSupported = true,
            episodes = listOf(RemoteChoice("e3", "Episode 3", selected = true), RemoteChoice("e4", "Episode 4")),
            subtitles = listOf(RemoteChoice("s1", "English", selected = true)),
        )
        show(desktopLayout = false)
        onNodeWithText("Now playing on your PC".uppercase()).assertIsDisplayed()
        onNodeWithText("Severance · S2 E3 · Who Is Alive?").assertIsDisplayed()
        onNodeWithContentDescription("Pause").assertIsDisplayed()
        onNodeWithTag("remote-toggle-fullscreen").performScrollTo().assertIsEnabled()
        onNodeWithText("Fullscreen on PC").performScrollTo().assertIsDisplayed()
        onNodeWithTag("remote-skip-interval").performScrollTo().assertIsEnabled()
        onNodeWithText("Skip intro").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Next episode").assertIsEnabled()
        onAllNodesWithTag("remote-toggle-fullscreen").assertCountEquals(1)
        onAllNodesWithTag("remote-skip-interval").assertCountEquals(1)
        onAllNodesWithContentDescription("Next episode").assertCountEquals(1)
        capture("pc-controller-remote")
        runOnIdle {
            DesktopRemote.remotePlayback.value = DesktopRemote.remotePlayback.value.copy(
                skipIntro = false, nextEpisodeAvailable = false, fullscreen = true,
            )
        }
        onNodeWithText("Skip intro or credits").performScrollTo().assertIsDisplayed()
        onAllNodesWithTag("remote-skip-interval").assertCountEquals(1)
        onNodeWithContentDescription("Next episode").assertIsNotEnabled()
        runOnIdle {
            DesktopRemote.remotePlayback.value = DesktopRemote.remotePlayback.value.copy(skip = false)
        }
        onNodeWithTag("remote-skip-interval").assertDoesNotExist()
        onNodeWithText("Exit fullscreen on PC").performScrollTo().assertIsDisplayed()
        onNodeWithText("Downloads").performClick()
        onNodeWithText("Open the same profile on your PC and phone to manage its downloads.").assertIsDisplayed()
        onNodeWithText("PCs").performClick()
        onNodeWithText("Disconnect").assertIsDisplayed()
        capture("pc-controller-pcs-connected")
    }

    @Test
    fun connectedPcIsLabeledAndCannotOfferASecondConnectAction() = runDesktopComposeUiTest(width = 412, height = 900) {
        val saved = RememberedRemoteDevice("Office PC", "02:12:34:56:78:9A", 0L, bluetooth = true)
        val other = RememberedRemoteDevice("Other PC", "02:12:34:56:78:9B", 0L, bluetooth = true)
        DesktopRemote.remembered.value = listOf(saved, other)
        DesktopRemote.connected.value = true
        DesktopRemote.activeDesktop.value = RemoteDesktop(saved.address, 0, saved.name, bluetooth = true)
        DesktopRemote.status.value = "Connected to Office PC"
        show(desktopLayout = false)

        onNodeWithText("PCs").performClick()
        onNodeWithTag("remote-saved-pc-${saved.address}").performScrollTo().assertIsDisplayed()
            .assert(!hasClickAction())
        onNodeWithTag("remote-saved-pc-${other.address}").assert(hasClickAction())
        onNodeWithContentDescription("More options for Office PC").performClick()
        onNodeWithText("Connect").assertDoesNotExist()
        onAllNodesWithText("Disconnect").assertCountEquals(2)
        onNodeWithText("Forget").assertIsDisplayed()

        runOnIdle {
            DesktopRemote.activeDesktop.value = null
            DesktopRemote.connected.value = false
        }
        onNodeWithTag("remote-saved-pc-${saved.address}").assert(hasClickAction())
        onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun searchingForAnotherPcDoesNotReplaceTheActiveConnectionMessage() = runDesktopComposeUiTest(width = 360, height = 802) {
        DesktopRemote.connected.value = true
        DesktopRemote.activeDesktop.value = RemoteDesktop("192.168.1.45", REMOTE_LAN_PORT, "Office PC")
        DesktopRemote.status.value = "Searching nearby Bluetooth PCs…"
        DesktopRemote.scanning.value = true
        show(desktopLayout = false)

        onNodeWithText("PCs").performClick()
        onNodeWithText("Connected to Office PC").assertIsDisplayed()
        onNodeWithText("Searching nearby Bluetooth PCs…").performScrollTo().assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsNotEnabled()
        capture("pc-controller-connected-and-searching")
    }

    @Test
    fun savedPcEditorStaysWithItsDeviceWhenPairingReordersRows() = runDesktopComposeUiTest(width = 412, height = 900) {
        val bluetooth = RememberedRemoteDevice("Office PC", "02:12:34:56:78:9A", 0L, bluetooth = true)
        val lan = RememberedRemoteDevice("Other PC", "192.168.1.45", 0L)
        DesktopRemote.remembered.value = listOf(bluetooth, lan)
        DesktopRemote.connected.value = true
        DesktopRemote.activeDesktop.value = RemoteDesktop(bluetooth.address, 0, bluetooth.name, bluetooth = true)
        show(desktopLayout = false)

        onNodeWithText("PCs").performClick()
        onNodeWithContentDescription("More options for Office PC").performScrollTo().performClick()
        onNodeWithText("Forget").assertIsDisplayed()
        onNodeWithText("Wake-on-LAN settings").assertDoesNotExist()
        runOnIdle { DesktopRemote.remembered.value = listOf(lan, bluetooth) }
        onNodeWithText("Forget").assertIsDisplayed()
        onNodeWithText("Wake-on-LAN settings").assertDoesNotExist()
    }

    @Test
    fun savedLanPcAlwaysOffersWakeAndOpensMacSetupOnFirstUse() = runDesktopComposeUiTest(width = 412, height = 900) {
        val saved = RememberedRemoteDevice("Living room PC", "192.168.1.60", 0L)
        DesktopRemote.remembered.value = listOf(saved)
        DesktopRemote.connected.value = false
        show(desktopLayout = false)
        onNodeWithTag("remote-wake-pc-${saved.address}").performScrollTo().assertIsDisplayed()
            .performClick()
        onNodeWithText("PC network adapter MAC").performScrollTo().assertIsDisplayed()
        onNodeWithText("Save wake address").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pcsTabKeepsItsScrollPositionWhenSwitchingTabs() = runDesktopComposeUiTest(width = 360, height = 700) {
        DesktopRemote.connected.value = true
        DesktopRemote.remembered.value = (1..8).map {
            RememberedRemoteDevice("Office PC $it", "192.168.1.$it", 0L)
        }
        show(desktopLayout = false)

        onNodeWithText("PCs").performClick()
        onNodeWithText("Previously paired PCs".uppercase()).performScrollTo().assertIsDisplayed()
        onNodeWithText("Remote").performClick()
        onNodeWithText("PCs").performClick()
        onNodeWithText("Previously paired PCs".uppercase()).assertIsDisplayed()
    }

    @Test
    fun fullscreenWorksWhenConnectedPcIsIdle() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connected.value = true
        DesktopRemote.remotePlayback.value = RemotePlayback(fullscreenSupported = true, fullscreen = true)
        show(desktopLayout = false)
        onNodeWithTag("remote-toggle-fullscreen").assertIsEnabled()
        onNodeWithText("Exit fullscreen on PC").assertIsDisplayed()
        onNodeWithTag("remote-skip-interval").assertDoesNotExist()
        onNodeWithContentDescription("Next episode").assertDoesNotExist()
        onNodeWithText("Nothing playing yet").assertIsDisplayed()
        capture("pc-controller-idle-fullscreen")
    }

    @Test
    fun desktopWindowSurfacesFirewallProblemsAndKeepsManualNetworkHelp() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.discoveryReady.value = true
        DesktopRemote.status.value = "Ready to pair with your phone on this local network."
        val firewall = FakeFirewall(RemoteFirewallState.Blocked)
        show(desktopLayout = true, firewall = firewall)
        onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
        onNodeWithText("Connection permission isn't confirmed").performScrollTo().assertIsDisplayed()
        runOnIdle { assertEquals(1, firewall.refreshes) }
        onNodeWithTag("remote-network-help").performScrollTo().performClick()
        onNodeWithText("Connection permission isn't confirmed").performScrollTo().assertIsDisplayed()
        runOnIdle { assertEquals(2, firewall.refreshes) }
        onNodeWithTag("remote-firewall-allow").performScrollTo()
        capture("phone-remote-network-help")
        onNodeWithTag("remote-firewall-allow").performScrollTo().performClick()
        runOnIdle { assertEquals(1, firewall.requests) }
        onNodeWithText("Approve the Windows prompt to let Starlit through the firewall…").performScrollTo().assertIsDisplayed()
        runOnIdle { firewall.flow.value = RemoteFirewallState.PublicNetwork }
        onNodeWithTag("remote-firewall-allow-public-ethernet").performScrollTo().performClick()
        runOnIdle { assertEquals(1, firewall.publicEthernetRequests) }
        onNodeWithText("Couldn't update Windows Firewall").performScrollTo().assertIsDisplayed()
        onNodeWithText("Allow on wired Ethernet").performScrollTo().performClick()
        runOnIdle { assertEquals(2, firewall.publicEthernetRequests) }
        runOnIdle { firewall.flow.value = RemoteFirewallState.PublicNetwork }
        onNodeWithText("Open network settings").performScrollTo().performClick()
        runOnIdle { assertEquals(1, firewall.settingsOpened) }
        runOnIdle { firewall.flow.value = RemoteFirewallState.Unverified }
        onNodeWithText("Couldn't check Windows Firewall").performScrollTo().assertIsDisplayed()
        runOnIdle { firewall.flow.value = RemoteFirewallState.Allowed }
        onNodeWithText("Windows rules allow local connections and automatic discovery. Your phone still needs a working network route to this PC.")
            .performScrollTo().assertIsDisplayed()
        capture("phone-remote-firewall-allowed")
        runOnIdle { firewall.flow.value = RemoteFirewallState.NotNeeded }
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
    }

    @Test
    fun connectedPhoneHidesIrrelevantLanDiagnostics() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        val firewall = FakeFirewall(RemoteFirewallState.Blocked)
        show(desktopLayout = true, firewall = firewall)
        onNodeWithTag("remote-network-help").performScrollTo().performClick()
        runOnIdle { DesktopRemote.connected.value = true }
        for (state in listOf(RemoteFirewallState.Blocked, RemoteFirewallState.PublicNetwork,
            RemoteFirewallState.Unverified, RemoteFirewallState.DiscoveryRestricted)) {
            runOnIdle { firewall.flow.value = state }
            onNodeWithTag("remote-firewall-card").assertDoesNotExist()
            onNodeWithTag("remote-network-help").assertDoesNotExist()
        }
        onNodeWithText("A phone is connected to this desktop.").performScrollTo().assertIsDisplayed()
        capture("phone-remote-connected-no-warning")
    }

    @Test
    fun udpRuleWarningKeepsQrPairingAvailable() = runDesktopComposeUiTest(width = 900, height = 760) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.discoveryReady.value = true
        show(desktopLayout = true, firewall = FakeFirewall(RemoteFirewallState.DiscoveryRestricted))
        onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
        onNodeWithTag("remote-network-help").performScrollTo().performClick()
        onNodeWithText("Try QR or an IP address").performScrollTo().assertIsDisplayed()
        onNodeWithText("Connection permission isn't confirmed").assertDoesNotExist()
        onNodeWithText("Allow through firewall").performScrollTo()
        capture("phone-remote-discovery-restricted")
    }

    @Test
    fun idleDesktopNeverShowsAStartupFirewallPrompt() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.visible.value = false
        val firewall = FakeFirewall(RemoteFirewallState.PublicNetwork)
        setContent { NuvioTheme { DesktopRemoteDialogs(desktopLayout = true, firewall = firewall, inline = true) } }
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("Let your phone find this PC?").assertDoesNotExist()
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
        runOnIdle {
            assertEquals(0, firewall.refreshes)
            assertEquals(0, firewall.requests)
        }
    }

    @Test
    fun desktopExplainsOccupiedPortInsteadOfShowingAnIndefiniteSpinner() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = false
        DesktopRemote.hostFailure.value = true
        DesktopRemote.status.value = remoteHostFailureMessage(java.net.BindException("Address already in use"))
        show(desktopLayout = true, firewall = FakeFirewall(RemoteFirewallState.Unverified))
        onNodeWithText("Not ready").assertIsDisplayed()
        onNodeWithTag("remote-network-help").assertDoesNotExist()
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
        onNodeWithText(DesktopRemote.status.value).assertIsDisplayed()
    }

    @Test
    fun desktopWindowShowsThePairingCodeInPlace() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.pairing.value = RemotePairing("482913", "Pixel 9")
        show(desktopLayout = true)
        onNodeWithTag("remote-pairing-code").assertIsDisplayed()
        onNodeWithText("Codes match").assertIsDisplayed()
        onNodeWithTag("desktop-pairing-qr").assertDoesNotExist()
        capture("phone-remote-pairing")
    }

    @Test
    fun narrowDesktopWindowStacksTheQrAboveTheDetails() = runDesktopComposeUiTest(width = 580, height = 900) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.connected.value = true
        show(desktopLayout = true, firewall = FakeFirewall(RemoteFirewallState.Allowed))
        onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
        onNodeWithText("A phone is connected to this desktop.").performScrollTo().assertIsDisplayed()
        onNodeWithText("Disconnect").assertIsDisplayed()
        capture("phone-remote-narrow-connected")
    }
}
