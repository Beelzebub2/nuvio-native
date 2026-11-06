package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SafeCastCompatibilityTest {
    private fun check(
        url: String?,
        headers: Map<String, String> = emptyMap(),
        p2p: Boolean = false,
        separateAudio: Boolean = false,
    ) = assessCastCompatibility(url, headers, p2p, separateAudio)

    @Test
    fun localPeersAndCredentialsNeverQualifyForDirectCasting() {
        assertEquals(CastCompatibility.NoSource, check(null))
        assertEquals(CastCompatibility.PeerToPeerOrLocal, check("magnet:?xt=urn:btih:test", p2p = true))
        assertEquals(CastCompatibility.PeerToPeerOrLocal, check("file:///private/video.mp4"))
        assertEquals(CastCompatibility.PeerToPeerOrLocal, check("content://app/private/video"))
        assertEquals(CastCompatibility.AuthenticatedHeaders, check(
            "https://cdn.example.org/video.mp4",
            headers = mapOf("Authorization" to "Bearer secret"),
        ))
        assertEquals(CastCompatibility.SeparateAudio, check(
            "https://cdn.example.org/video.mp4", separateAudio = true,
        ))
        assertEquals(CastCompatibility.SignedOrOpaqueUrl, check(
            "https://cdn.example.org/video.mp4?token=PRIVATE",
        ))
        assertEquals(CastCompatibility.SignedOrOpaqueUrl, check(
            "https://cdn.example.org/video.mpd#fragment",
        ))
        assertEquals(CastCompatibility.NonHttpsSource, check(
            "http://cdn.example.org/video.m3u8",
        ))
    }

    @Test
    fun privateHostsAndUnrecognizedSchemesAreRejected() {
        listOf(
            "https://localhost/video.mp4",
            "https://127.0.0.1/video.mp4",
            "https://192.168.1.2/video.mp4",
            "https://device.local/video.mp4",
            "https://inside.internal/video.mp4",
            "https://user:pass@cdn.example.org/video.mp4",
            "https://cd..n.example.org/video.mp4",
            "https://router.home/video.mp4",
            "https://router.home.arpa/video.mp4",
            "https://router.localdomain/video.mp4",
            "https://-cdn.example.org/video.mp4",
            "https://cdn-.example.org/video.mp4",
            "https://${"a".repeat(64)}.example.org/video.mp4",
            "https://0x7f.1/video.mp4",
            "https://[::1]/video.mp4",
            "https://media.example.org:443/video.mp4",
        ).forEach { assertEquals(CastCompatibility.PrivateOrUnknownHost, check(it), it) }
        assertEquals(CastCompatibility.UnsupportedMediaFormat, check(
            "https://cdn.example.org/player",
        ))
    }

    @Test
    fun parserAmbiguitiesAndControlCharactersCannotReachTheReceiver() {
        listOf(
            " https://media.example.org/clip.mp4",
            "https://media.example.org/clip.mp4 ",
            "https://media.example.org/a\n/clip.mp4",
            "https://media.example.org/a\t/clip.mp4",
            "https://media.example.org/a%0D%0a/clip.mp4",
            "https://media.example.org/a%00/clip.mp4",
            "https://media.example.org/a\\clip.mp4",
            "https://media.example.org/a clip.mp4",
        ).forEach {
            assertEquals(CastCompatibility.SignedOrOpaqueUrl, check(it), it)
            assertNull(eligibleCastMimeType(it))
        }
    }

    @Test
    fun onlyObviousDirectFormatsAreCandidatesNeverAnAuthorizationGrant() {
        listOf(".mp4", ".webm", ".m3u8", ".mpd").forEach { ext ->
            assertEquals(
                CastCompatibility.PotentiallyCompatibleDirectMedia,
                check("https://media.example.org/title$ext"),
            )
        }
        val outcome = check("https://media.example.org/title.mp4")
        assertFalse(outcome.name.contains("https", ignoreCase = true))
        assertFalse(outcome.name.contains("token", ignoreCase = true))
    }

    @Test
    fun receiverMimeTypesAreResolvedOnlyForPublicDirectMedia() {
        assertEquals("video/mp4", eligibleCastMimeType("https://media.example.org/clip.mp4"))
        assertEquals("video/webm", eligibleCastMimeType("https://media.example.org/clip.WEBM"))
        assertEquals("application/x-mpegURL", eligibleCastMimeType("https://media.example.org/master.m3u8"))
        assertEquals("application/dash+xml", eligibleCastMimeType("https://media.example.org/manifest.mpd"))
        assertNull(eligibleCastMimeType("https://localhost/media.mp4"))
        assertNull(eligibleCastMimeType("http://media.example.org/media.mp4"))
        assertNull(eligibleCastMimeType("https://media.example.org/media.mp4?private=secret"))
        assertNull(eligibleCastMimeType("https://media.example.org/opaque"))
    }
}
