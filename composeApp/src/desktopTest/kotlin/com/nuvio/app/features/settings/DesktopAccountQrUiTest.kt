package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.RegisteredPasskey
import com.nuvio.app.core.auth.STARLIT_GOOGLE_ORIGIN
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.remote.DesktopPairingQr
import com.nuvio.app.features.remote.DesktopRemote
import com.nuvio.app.features.remote.DesktopRemoteDialogs
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopAccountQrUiTest {
    private val namedAccount = AuthState.Authenticated("fixture-account",
        "ricardo.with.a.long.account.address@example.com", false)

    private fun withHttpsServer(block: () -> Unit) {
        val original = ServerConfigurationRepository.active.value
        try {
            assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                STARLIT_GOOGLE_ORIGIN, "sb_publishable_fixture", ServerCapabilities(true, false), true)))
            block()
        } finally {
            if (original.isCustom) ServerConfigurationRepository.saveCustom(original)
            else ServerConfigurationRepository.useOfficial()
        }
    }

    private fun ComposeUiTest.account(state: AuthState, addresses: () -> List<String> = { listOf("192.168.1.50", "10.0.0.2") }) {
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background).padding(40.dp)) {
                    Column(Modifier.widthIn(max = 800.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Account", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.nuvio.colors.textPrimary)
                        DesktopAccountCard(state, onSignInClick = {}, onSignOutClick = {})
                        Text("Devices", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                        DesktopPhoneConnectionCard(state)
                    }
                }
                DesktopRemoteDialogs(localAddresses = addresses)
            }
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/account-qr-capture/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun signedInAccountShowsEveryAvailableSectionAtDesktopWidths() {
        val original = ServerConfigurationRepository.active.value
        try {
            assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                STARLIT_GOOGLE_ORIGIN, "sb_publishable_fixture", ServerCapabilities(true, false), true)))
            listOf(1000, 520).forEach { width ->
                runDesktopComposeUiTest(width = width, height = 1100) {
                    setContent {
                        NuvioTheme {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background).padding(40.dp)) {
                                Column(Modifier.widthIn(max = 800.dp).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Account", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.nuvio.colors.textPrimary)
                                    DesktopAccountCard(namedAccount, onSignInClick = {}, onSignOutClick = {})
                                    Text("Sign-in & security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                                    GoogleAccountLinkCard(namedAccount, isAvailable = true, loadIdentity = { false })
                                    PasskeySecurityCard(namedAccount, loadCapability = { true }, loadPasskeys = {
                                        listOf(RegisteredPasskey("fixture-key", "Windows Hello", "2026-11-03"))
                                    })
                                    Text("Data & sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                                    LocalSettingsImportCard(namedAccount, isAvailable = true)
                                    Text("Devices", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                                    DesktopPhoneConnectionCard(namedAccount)
                                }
                            }
                        }
                    }
                    onNodeWithText("Link Google").assertIsDisplayed()
                    onNodeWithText("Windows Hello").assertIsDisplayed()
                    onNodeWithText("Remove").assertIsDisplayed()
                    capture("account-all-options-$width")
                    onNodeWithText("Connect phone").performScrollTo().assertIsDisplayed()
                    capture("account-all-options-$width-bottom")
                }
            }
        } finally {
            if (original.isCustom) ServerConfigurationRepository.saveCustom(original)
            else ServerConfigurationRepository.useOfficial()
        }
    }

    @Test
    fun namedAccountKeepsItsIdentityAndConnectionActionAtDesktopWidths() = withHttpsServer {
        listOf(1000, 520).forEach { width ->
            runDesktopComposeUiTest(width = width, height = 720) {
                account(namedAccount)
                onNodeWithText(namedAccount.email!!).assertIsDisplayed()
                onNodeWithText("Sign Out").assertIsDisplayed()
                onNodeWithText("Connect phone").assertIsDisplayed().assertIsEnabled()
                capture("account-$width")
            }
        }
    }

    @Test
    fun loadingStateDoesNotOfferAReadyPhoneConnection() =
        runDesktopComposeUiTest(width = 520, height = 720) {
            account(AuthState.Loading)
            onNodeWithText("Loading account…").assertIsDisplayed()
            onNodeWithText("Connect phone").assertIsNotEnabled()
            onNodeWithText("Sign Out").assertDoesNotExist()
            capture("account-loading")
        }

    @Test
    fun guestKeepsSignOutAndExplainsWhyPairingIsUnavailable() =
        runDesktopComposeUiTest(width = 520, height = 720) {
            account(AuthState.Authenticated("fixture-guest", null, true))
            onNodeWithText("Connect phone").assertIsNotEnabled()
            onNodeWithText("Sign in to your Starlit account to connect your phone.").assertIsDisplayed()
            onNodeWithText("Sign Out").assertIsDisplayed()
            capture("account-guest")
        }

    @Test
    fun localHttpServerDisablesPhoneConnectionAndHttpsServerReenablesIt() {
        val original = ServerConfigurationRepository.active.value
        try {
            assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                "http://localhost:54321", "sb_publishable_fixture", ServerCapabilities(true, false), true)))
            runDesktopComposeUiTest(width = 520, height = 720) {
                account(namedAccount)
                onNodeWithText("Connect phone").assertIsNotEnabled()
                onNodeWithText("Phone remote requires an HTTPS account server. Switch to an HTTPS server to connect your phone.")
                    .assertIsDisplayed()
                onNodeWithTag("desktop-pairing-qr").assertDoesNotExist()

                runOnIdle {
                    assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                        "https://example.com", "sb_publishable_fixture", ServerCapabilities(true, false), true)))
                }
                onNodeWithText("Connect phone").assertIsEnabled()
                onNodeWithText("Phone remote requires an HTTPS account server. Switch to an HTTPS server to connect your phone.")
                    .assertDoesNotExist()

                runOnIdle { assertTrue(ServerConfigurationRepository.useOfficial()) }
                // CI builds may not have official backend secrets configured.
                if (ServerConfigurationRepository.active.value.backendUrl.startsWith("https://")) {
                    onNodeWithText("Connect phone").assertIsEnabled()
                } else {
                    onNodeWithText("Connect phone").assertIsNotEnabled()
                }
            }
        } finally {
            if (original.isCustom) ServerConfigurationRepository.saveCustom(original)
            else ServerConfigurationRepository.useOfficial()
        }
    }

    @Test
    fun phoneConnectionOpensTheActualQrDialog() = withHttpsServer {
        runDesktopComposeUiTest(width = 1000, height = 900) {
        val previousReady = DesktopRemote.hostReady.value
        val previousStatus = DesktopRemote.status.value
        try {
            DesktopRemote.hostReady.value = true
            DesktopRemote.status.value = "Ready to pair with your phone on this local network."
            account(namedAccount)
            onNodeWithText("Connect phone").performClick()
            onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
            // Capture the code actually drawn by Compose, including desktop scaling.
            val image = onNodeWithTag("desktop-pairing-qr").captureToImage().toAwtImage()
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(image.width, image.height, pixels))))
            assertEquals("starlit-pc:1:192.168.1.50", result.text)
            val file = File("build/account-qr-capture/phone-remote-dialog.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", file)
            onNodeWithText("Close").performClick()
        } finally {
            DesktopRemote.visible.value = false
            DesktopRemote.hostReady.value = previousReady
            DesktopRemote.status.value = previousStatus
        }
        }
    }

    @Test
    fun reopeningAfterJoiningOrChangingNetworksUsesFreshAddressesWithoutRestartingTheListener() =
        withHttpsServer {
        runDesktopComposeUiTest(width = 1000, height = 900) {
            val previousReady = DesktopRemote.hostReady.value
            var addresses = emptyList<String>()
            try {
                DesktopRemote.hostReady.value = true
                account(namedAccount, addresses = { addresses })
                onNodeWithText("Connect phone").performClick()
                onNodeWithTag("desktop-pairing-qr").assertDoesNotExist()
                onNodeWithText("Connect this PC to Wi-Fi or Ethernet, then reopen Phone remote to show a QR code.").assertIsDisplayed()
                onNodeWithText("Close").performClick()
                runOnIdle { addresses = listOf("192.168.1.50") }
                onNodeWithText("Connect phone").performClick()
                onNodeWithContentDescription("Desktop pairing QR code for 192.168.1.50").assertIsDisplayed()
                onNodeWithText("Local PC address: 192.168.1.50").assertIsDisplayed()
                onNodeWithText("Close").performClick()
                runOnIdle { addresses = listOf("10.0.0.2") }
                onNodeWithText("Connect phone").performClick()
                onNodeWithContentDescription("Desktop pairing QR code for 10.0.0.2").assertIsDisplayed()
                onNodeWithText("Local PC address: 10.0.0.2").assertIsDisplayed()
            } finally {
                DesktopRemote.visible.value = false
                DesktopRemote.hostReady.value = previousReady
            }
        }
        }

    @Test
    fun qrStillDecodesAtFractionalDesktopDensityAndNetworkSelectionWorks() =
        runDesktopComposeUiTest(width = 600, height = 600) {
            setContent {
                NuvioTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1.25f)) {
                        DesktopPairingQr(listOf("192.168.1.50", "10.0.0.2"))
                    }
                }
            }
            onNodeWithText("Choose network address").performClick()
            onNodeWithText("10.0.0.2").performClick()
            val image = onNodeWithTag("desktop-pairing-qr").captureToImage().toAwtImage()
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(image.width, image.height, pixels))))
            assertEquals("starlit-pc:1:10.0.0.2", result.text)
        }
}
