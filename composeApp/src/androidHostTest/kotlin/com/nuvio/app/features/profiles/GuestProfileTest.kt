package com.nuvio.app.features.profiles

import android.app.Application
import android.content.Context
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.AuthStorage
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuestProfileTest {
    @Test
    fun freshGuestGetsASelectablePersistentProfileAndExistingProfilesArePreserved() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
        AuthStorage.initialize(context)
        ProfileStorage.initialize(context)
        ProfilePinCacheStorage.initialize(context)
        ProfileRepository.clearInMemory()
        AuthRepository.signInAnonymously()
        val userId = (AuthRepository.state.value as AuthState.Authenticated).userId
        try {
            ProfileRepository.ensureLoaded(userId)
            ProfileRepository.pullProfiles()

            val initial = ProfileRepository.state.value
            assertTrue(initial.isLoaded)
            assertFalse(initial.hasEverSelectedProfile)
            assertEquals(1, initial.profiles.single().profileIndex)
            assertEquals(userId, initial.profiles.single().userId)
            assertEquals(initial.profiles.single(), initial.activeProfile)
            assertFalse(initial.activeProfile!!.pinEnabled)

            ProfileRepository.pushProfiles(listOf(ProfilePushPayload(1, "My guest", "#E53935")))
            ProfileRepository.clearInMemory()
            ProfileRepository.ensureLoaded(userId)
            ProfileRepository.pullProfiles()

            assertEquals("My guest", ProfileRepository.state.value.profiles.single().name)
            assertEquals(1, ProfileRepository.activeProfileId)
        } finally {
            ProfileRepository.clearInMemory()
            context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
            AuthRepository.signOut().getOrThrow()
        }
    }
}
