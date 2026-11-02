package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {

    @Test
    fun `display title uses exact type formatting`() {
        assertEquals("Movie", "movie".toLibraryDisplayTitle())
        assertEquals("Anime Series", "anime-series".toLibraryDisplayTitle())
        assertEquals("Tv", "tv".toLibraryDisplayTitle())
        assertEquals("Other", "".toLibraryDisplayTitle())
    }

    @Test
    fun `meta preview mapping preserves exact type and poster shape`() {
        val item = LibraryItem(
            id = "tt1",
            type = "anime-series",
            name = "Title",
            poster = "poster",
            banner = "banner",
            logo = "logo",
            description = "desc",
            releaseInfo = "2024",
            imdbRating = "8.4",
            genres = listOf("Drama"),
            posterShape = PosterShape.Poster,
            savedAtEpochMs = 1L,
        )

        val preview = item.toMetaPreview()

        assertEquals("anime-series", preview.type)
        assertEquals(PosterShape.Poster, preview.posterShape)
        assertEquals("banner", preview.banner)
    }

    @Test
    fun `metadata mappings keep imdb ids for Trakt-compatible sync`() {
        val previewItem = MetaPreview(
            id = "tt1234567",
            type = "movie",
            name = "Movie",
        ).toLibraryItem(savedAtEpochMs = 1L)
        val detailsItem = MetaDetails(
            id = "tt7654321",
            type = "series",
            name = "Show",
        ).toLibraryItem(savedAtEpochMs = 2L)
        val tmdbOnlyItem = MetaPreview(
            id = "tmdb:42",
            type = "movie",
            name = "TMDB",
        ).toLibraryItem(savedAtEpochMs = 3L)

        assertEquals("tt1234567", previewItem.imdbId)
        assertEquals("tt7654321", detailsItem.imdbId)
        assertEquals(null, tmdbOnlyItem.imdbId)
    }

    @Test
    fun `library tabs include local Nuvio library before Trakt tabs`() {
        val traktTab = TrackingLibraryTab(
            key = "trakt:watchlist",
            title = "Watchlist",
            providerId = TrackingProviderId.TRAKT,
            kind = TrackingLibraryTabKind.WATCHLIST,
        )

        val tabs = libraryTabsWithLocal(listOf(traktTab))

        assertEquals(listOf("local", "trakt:watchlist"), tabs.map { it.key })
        assertEquals("Starlit Library", tabs.first().title)
    }

    @Test
    fun `library membership always includes local state before Trakt membership`() {
        val membership = libraryMembershipWithLocal(
            inLocal = true,
            providerMembership = mapOf("trakt:watchlist" to false),
        )

        assertEquals(
            mapOf(
                "local" to true,
                "trakt:watchlist" to false,
            ),
            membership,
        )
    }

    @Test
    fun `local snapshot remains attached to its profile after replacement`() {
        val state = LibraryLocalState()
        val profileOneLoad = state.beginProfileLoad(profileId = 1).snapshot
        assertFalse(profileOneLoad.hasLoaded)
        assertTrue(profileOneLoad.isLoading)

        val profileOneSnapshot = assertNotNull(
            state.completeProfileLoad(
                token = profileOneLoad.token,
                activeProfileId = 1,
                items = listOf(libraryItem(id = "profile-one", savedAtEpochMs = 1L)),
            ),
        )
        assertTrue(profileOneSnapshot.hasLoaded)
        assertFalse(profileOneSnapshot.isLoading)

        val profileTwoToken = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = profileTwoToken,
            activeProfileId = 2,
            items = listOf(libraryItem(id = "profile-two", savedAtEpochMs = 2L)),
        )
        val profileTwoSnapshot = state.snapshot()

        assertEquals(1, profileOneSnapshot.token.profileId)
        assertEquals(listOf("profile-one"), profileOneSnapshot.items.map { it.id })
        assertEquals(2, profileTwoSnapshot.token.profileId)
        assertEquals(listOf("profile-two"), profileTwoSnapshot.items.map { it.id })
    }

    @Test
    fun `stale profile load cannot replace current profile items`() {
        val state = LibraryLocalState()
        val staleToken = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = staleToken,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "old", savedAtEpochMs = 1L)),
        )
        val staleSnapshot = state.snapshot()

        val currentToken = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = currentToken,
            activeProfileId = 2,
            items = listOf(libraryItem(id = "current", savedAtEpochMs = 2L)),
        )

        val staleCommit = state.completeProfileLoad(
            token = staleToken,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "stale", savedAtEpochMs = 3L)),
        )

        assertNull(staleCommit)
        var stalePersistenceAccepted = false
        assertFalse(
            state.runIfContentCurrent(staleSnapshot) {
                stalePersistenceAccepted = true
            },
        )
        assertFalse(stalePersistenceAccepted)
        assertEquals(2, state.snapshot().token.profileId)
        assertEquals(listOf("current"), state.snapshot().items.map { it.id })
    }

    @Test
    fun `stale publication revision is rejected after same profile mutation`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "first", savedAtEpochMs = 1L)),
        )
        val staleSnapshot = state.snapshot()
        state.upsert(libraryItem(id = "second", savedAtEpochMs = 2L))

        var committed = false
        val accepted = state.runIfCurrent(staleSnapshot) {
            committed = true
        }

        assertFalse(accepted)
        assertFalse(committed)
        assertEquals(setOf("first", "second"), state.snapshot().items.map { it.id }.toSet())
    }

    @Test
    fun `pull lifecycle does not invalidate pending local push`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = emptyList(),
        )
        val localSnapshot = state.upsert(libraryItem(id = "local", savedAtEpochMs = 1L))

        state.markPullStarted(token)

        assertTrue(state.isContentCurrent(localSnapshot))
    }

    @Test
    fun `server pull preserves local items awaiting push`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))
        val pullSnapshot = assertNotNull(state.markPullStarted(token))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
            ),
        )

        assertTrue(result.preservedLocalItems)
        assertEquals(
            setOf("remote-old", "local-new"),
            result.snapshot.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `server pull preserves a local mutation made while pulling`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        val pullSnapshot = assertNotNull(state.markPullStarted(token))
        state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
            ),
        )

        assertTrue(result.preservedLocalItems)
        assertEquals(
            setOf("remote-old", "local-new"),
            result.snapshot.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `server pull applies after local push completes`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        val localSnapshot = state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))
        state.markPushCompleted(localSnapshot)
        val pullSnapshot = assertNotNull(state.markPullStarted(token))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-new", savedAtEpochMs = 3L)),
            ),
        )

        assertFalse(result.preservedLocalItems)
        assertEquals(listOf("remote-new"), result.snapshot.items.map { it.id })
    }

    @Test
    fun `guest library import keeps account entries and does not resurrect pending deletions`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 2,
            items = listOf(
                libraryItem(id = "account", savedAtEpochMs = 8L),
                libraryItem(id = "removed", savedAtEpochMs = 3L),
            ),
        )
        state.remove("removed", "movie")
        val guest = listOf(
            libraryItem(id = "account", savedAtEpochMs = 1L),
            libraryItem(id = "removed", savedAtEpochMs = 1L),
            libraryItem(id = "new-from-guest", savedAtEpochMs = 2L),
        )

        val result = state.importMissing(guest)

        assertEquals(setOf("account", "new-from-guest"), result.items.map { it.id }.toSet())
        assertEquals(8L, result.items.single { it.id == "account" }.savedAtEpochMs)
        assertEquals(listOf("new-from-guest"), result.pendingUpsertKeys.map { it.contentId })
        assertEquals(listOf("removed"), result.pendingDeleteKeys.map { it.contentId })
        assertEquals(result.revision, state.importMissing(guest).revision)
    }

    @Test
    fun `guest library import survives a new profile load with upload outbox intact`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(token, activeProfileId = 1, items = emptyList())
        val imported = state.importMissing(listOf(libraryItem("guest", 33L)))
        val payload = LibraryStoragePayloadCodec.encode(imported)
        val saved = LibraryStoragePayloadCodec.decodeStrict(payload)

        val restarted = LibraryLocalState()
        val reloadToken = restarted.beginProfileLoad(profileId = 1).snapshot.token
        val restored = assertNotNull(restarted.completeProfileLoad(
            token = reloadToken,
            activeProfileId = 1,
            items = saved.items,
            deltaCursorEventId = saved.deltaCursorEventId,
            deltaInitialized = saved.deltaInitialized,
            pendingUpsertKeys = saved.pendingUpsertKeys,
            pendingDeleteKeys = saved.pendingDeleteKeys,
        ))
        assertEquals(listOf("guest"), restored.items.map { it.id })
        assertTrue(restored.hasPendingPush)
        assertEquals(listOf("guest"), restored.pendingUpsertKeys.map { it.contentId })
    }

    @Test
    fun `guest cloud merge keeps the remote account record when both contain the same title`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(token, activeProfileId = 2, items = emptyList())
        val guest = libraryItem("shared", 11L)
        state.importMissing(listOf(guest))
        val remote = libraryItem("shared", 42L)

        val merged = state.acknowledgeGuestImport(listOf(guest), listOf(remote))

        assertEquals(listOf(remote), merged.items)
        assertFalse(merged.hasPendingPush)
        assertEquals(merged.revision, state.acknowledgeGuestImport(listOf(guest), listOf(remote)).revision)
    }

    private fun libraryItem(id: String, savedAtEpochMs: Long): LibraryItem =
        LibraryItem(
            id = id,
            type = "movie",
            name = id,
            savedAtEpochMs = savedAtEpochMs,
        )
}
