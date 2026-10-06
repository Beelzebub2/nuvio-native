package com.nuvio.app.features.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrServerStatsTest {
    @Test fun nullFileStatsWhileFetchingMetadataIsNotAnEngineFailure() {
        val stats = parseTorrServerStats(Json.parseToJsonElement(
            """{"file_stats":null,"download_speed":0,"active_peers":0}""").jsonObject)
        assertTrue(stats.files.isEmpty())
        assertEquals(0, stats.peers)
    }

    @Test fun readyMetadataDecodesFloatRatesAndLargeFiles() {
        val stats = parseTorrServerStats(Json.parseToJsonElement("""
            {"download_speed":12345.67,"upload_speed":20.9,"active_peers":4,
             "file_stats":[{"id":3,"path":"sample.mp4","length":5000000000}],
             "loaded_size":3000000000,"torrent_size":5000000000,"preloaded_bytes":1234}
        """).jsonObject)
        assertEquals(12345L, stats.downloadSpeed)
        assertEquals(20L, stats.uploadSpeed)
        assertEquals(TorrServerFile(3, "sample.mp4", 5000000000L), stats.files.single())
        assertEquals(3000000000L, stats.loadedSize)
    }
}
