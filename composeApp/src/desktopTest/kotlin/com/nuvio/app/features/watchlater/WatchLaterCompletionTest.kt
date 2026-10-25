package com.nuvio.app.features.watchlater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchLaterCompletionTest {
    @Test fun `playback completion removes only the finished saved episode and persists after reload`() {
        val profile = ProfileRepository.activeProfileId
        val saved = episode("watch-later-completion", "saved-provider-id", 1, 2)
        val session = session(saved, profile).copy(videoId = "playback-provider-id")
        val siblings = listOf(
            saved.copy(watchLaterEpisode = WatchLaterEpisode(session.videoId, "Other season", 2, 2)),
            episode(saved.id, "unfinished", 1, 3),
            episode("other-series", session.videoId, 1, 2),
            saved.copy(watchLaterEpisode = null),
            saved.copy(type = "movie"),
        )
        try {
            withSaved(profile, listOf(saved) + siblings) {
                WatchProgressRepository.upsertPlaybackProgress(session, snapshot(600_000), syncRemote = false)
                assertEquals(6, WatchLaterRepository.items.value.size, "Partial progress keeps every bookmark")
                WatchProgressRepository.flushPlaybackProgress(session, snapshot(9_000, 10_000, ended = true), syncRemote = false)
                assertEquals(6, WatchLaterRepository.items.value.size, "A short placeholder is not completion")

                WatchProgressRepository.upsertPlaybackProgress(session, snapshot(1_080_000), syncRemote = false)
                val expected = siblings.map { it.toLibraryItem(0L).watchLaterKey() }.toSet()
                assertEquals(expected, WatchLaterRepository.items.value.map { it.watchLaterKey() }.toSet())
                WatchLaterRepository.clearLocalState()
                WatchLaterRepository.ensureLoaded()
                assertEquals(expected, WatchLaterRepository.items.value.map { it.watchLaterKey() }.toSet())
                assertTrue(WatchProgressRepository.progressForVideo(session.videoId, saved.id, 1, 2)!!.isEffectivelyCompleted)
                withSaved(profile, listOf(saved)) {
                    assertEquals(1, WatchLaterRepository.items.value.size, "A bookmark saved for rewatch survives loading historical completion")
                }
            }
        } finally {
            WatchProgressRepository.clearProgress(session.videoId, saved.id)
        }
    }

    @Test fun `an ended flush with unknown episode numbers removes only the original profile bookmark`() {
        val activeProfile = ProfileRepository.activeProfileId
        val previousProfile = activeProfile + 201
        val saved = episode("watch-later-late-flush", "video-id", null, null)
        val session = session(saved, previousProfile)
        val progressStore = DesktopStorage.store("nuvio_watch_progress")
        val progressKey = "watch_progress_$previousProfile"
        val previousProgress = progressStore.getString(progressKey)
        try {
            withSaved(activeProfile, listOf(saved)) {
                withSaved(previousProfile, listOf(saved, saved.copy(watchLaterEpisode = WatchLaterEpisode("other", "Other")))) {
                    WatchProgressRepository.flushPlaybackProgress(session, snapshot(0, 0, ended = true), syncRemote = false)
                    assertEquals("video-id", WatchLaterRepository.items.value.single().watchLaterEpisode?.videoId)
                    val restored = WatchLaterList({ previousProfile },
                        { DesktopStorage.store("nuvio_watch_later").getString("items_$it") }, { _, _ -> error("Read only") })
                    restored.ensureLoaded()
                    assertEquals("other", restored.items.value.single().watchLaterEpisode?.videoId)
                }
            }
        } finally {
            progressStore.putString(progressKey, previousProgress)
        }
    }

    @Test fun `repeated Watch Later disk failures retain the bookmark without blocking playback progress`() {
        val profile = ProfileRepository.activeProfileId
        val saved = episode("watch-later-write-failure", "failure-video", 1, 1)
        val session = session(saved, profile)
        try {
            withSaved(profile, listOf(saved)) {
                val store = DesktopStorage.store("nuvio_watch_later")
                val file = DesktopStorage.rootDir.resolve("nuvio_watch_later.properties")
                val backup = Files.createTempFile(DesktopStorage.rootDir, "watch-later-completion-", ".backup")
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
                try {
                    Files.createDirectory(file) // Force this store's writes to fail; progress uses a separate file.
                    repeat(2) {
                        WatchProgressRepository.flushPlaybackProgress(session, snapshot(1_200_000, ended = true), syncRemote = false)
                        assertEquals("failure-video", WatchLaterRepository.items.value.single().watchLaterEpisode?.videoId)
                        assertTrue(WatchProgressRepository.progressForVideo(session.videoId, saved.id, 1, 1)!!.isEffectivelyCompleted)
                    }
                } finally {
                    Files.deleteIfExists(file)
                    Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING)
                    store.clearInMemory()
                }
            }
        } finally {
            WatchProgressRepository.clearProgress(session.videoId, saved.id)
        }
    }

    private fun episode(id: String, videoId: String, season: Int?, episode: Int?) =
        MetaPreview(id, "series", "Test series", watchLaterEpisode = WatchLaterEpisode(videoId, "Saved episode", season, episode))

    private fun session(saved: MetaPreview, profile: Int) = WatchProgressPlaybackSession(
        profile, saved.type, saved.id, saved.type, saved.watchLaterEpisode!!.videoId, saved.name,
        seasonNumber = saved.watchLaterEpisode.seasonNumber, episodeNumber = saved.watchLaterEpisode.episodeNumber,
    )

    private fun snapshot(position: Long, duration: Long = 1_200_000, ended: Boolean = false) =
        PlayerPlaybackSnapshot(isLoading = false, positionMs = position, durationMs = duration, isEnded = ended)

    private fun withSaved(profile: Int, saved: List<MetaPreview>, block: () -> Unit) {
        val store = DesktopStorage.store("nuvio_watch_later")
        val key = "items_$profile"
        val previous = store.getString(key)
        try {
            store.putString(key, Json.encodeToString(saved.map { it.toLibraryItem(0L) }))
            WatchLaterRepository.clearLocalState()
            WatchLaterRepository.ensureLoaded()
            block()
        } finally {
            store.putString(key, previous)
            WatchLaterRepository.clearLocalState()
            WatchLaterRepository.ensureLoaded()
        }
    }
}
