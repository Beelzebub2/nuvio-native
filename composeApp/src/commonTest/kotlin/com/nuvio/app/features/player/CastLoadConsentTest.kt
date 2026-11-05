package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastLoadConsentTest {
    private val source = "https://media.example.org/clip.mp4"

    @Test
    fun discoveryAndConnectionNeverAuthorizeLoading() {
        val consent = CastLoadConsent(source)
        assertNull(consent.beginLoad(null))
        consent.updateReceiver("tv-a")
        assertNull(consent.beginLoad("tv-a"))
        consent.rightsConfirmed = true
        assertNull(consent.beginLoad("tv-b"))
        assertNotNull(consent.beginLoad("tv-a"))
        assertNull(consent.beginLoad("tv-a")) // No duplicate load while pending.
    }

    @Test
    fun receiverSwitchAndReconnectRequireFreshConsent() {
        val consent = CastLoadConsent(source)
        consent.updateReceiver("tv-a")
        consent.rightsConfirmed = true
        val first = assertNotNull(consent.beginLoad("tv-a"))
        consent.updateReceiver("tv-b")
        assertFalse(consent.rightsConfirmed)
        assertFalse(consent.completeLoad(first, "tv-b"))
        assertNull(consent.beginLoad("tv-b"))
        consent.rightsConfirmed = true
        consent.updateReceiver(null)
        consent.updateReceiver("tv-b")
        assertFalse(consent.rightsConfirmed)
    }

    @Test
    fun disposedPanelAndChangedSourceRejectLateResults() {
        val old = CastLoadConsent(source)
        old.updateReceiver("tv")
        old.rightsConfirmed = true
        val request = assertNotNull(old.beginLoad("tv"))
        old.dispose()
        assertFalse(old.completeLoad(request, "tv"))
        assertNull(old.beginLoad("tv"))
        val nextSource = CastLoadConsent("https://media.example.org/next.mp4")
        nextSource.updateReceiver("tv")
        assertNull(nextSource.beginLoad("tv"))
    }

    @Test
    fun resultsAreSingleUseAndFailuresCanBeRetriedDeliberately() {
        val consent = CastLoadConsent(source)
        consent.updateReceiver("tv")
        consent.rightsConfirmed = true
        val request = assertNotNull(consent.beginLoad("tv"))
        assertTrue(consent.completeLoad(request, "tv"))
        assertFalse(consent.completeLoad(request, "tv"))
        val retry = assertNotNull(consent.beginLoad("tv"))
        assertFalse(consent.completeLoad(request, "tv"))
        assertTrue(consent.completeLoad(retry, "tv"))
    }

    @Test
    fun consentCannotAuthorizeRestrictedSourcesOrRevokedRights() {
        listOf("https://localhost/video.mp4", "$source?token=secret", "file:///video.mp4").forEach {
            val consent = CastLoadConsent(it)
            consent.updateReceiver("tv")
            consent.rightsConfirmed = true
            assertNull(consent.beginLoad("tv"))
        }
        val consent = CastLoadConsent(source)
        consent.updateReceiver("tv")
        consent.rightsConfirmed = true
        val request = assertNotNull(consent.beginLoad("tv"))
        consent.rightsConfirmed = false
        assertFalse(consent.completeLoad(request, "tv"))
    }
}
