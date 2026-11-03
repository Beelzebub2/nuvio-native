package com.nuvio.app.core.auth

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidPasskeyCeremonyTest {
    @Test
    fun accountScreenDoesNotInitializeAnUnavailablePasskeyProvider() = runBlocking {
        var providerAttempts = 0
        val ceremony = AndroidPasskeyCeremony(RuntimeEnvironment.getApplication()) {
            providerAttempts++
            throw IllegalStateException("No provider configuration")
        }

        // Simply composing the Account screen must not require a passkey
        // provider. Failure is only surfaced when the user requests one.
        assertEquals(0, providerAttempts)
        val failure = assertFailsWith<IllegalStateException> { ceremony.create("{}") }
        assertEquals(1, providerAttempts)
        assertTrue(failure.message.orEmpty().contains("No compatible passkey manager"))
    }
}
