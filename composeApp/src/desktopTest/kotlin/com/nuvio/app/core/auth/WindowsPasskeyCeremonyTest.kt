package com.nuvio.app.core.auth

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import java.util.Base64
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPasskeyCeremonyTest {
    private val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val options get() = """{"rpId":"starlit-api.rrmtools.uk","challenge":"$challenge","userVerification":"required","timeout":60000}"""

    @Test
    fun loadsRealWindowsApiAndAllocatesCancellationIdWithoutOpeningAPrompt() {
        if (!Platform.isWindows()) return
        val api = Native.load(File(System.getenv("SystemRoot"), "System32/webauthn.dll").absolutePath,
            WindowsWebAuthn::class.java)
        val version = api.WebAuthNGetApiVersionNumber()
        assertTrue(version >= 1)
        println("Windows WebAuthn API version: $version; hybrid available: ${version >= 6}")
        Memory(16).use { assertEquals(0, api.WebAuthNGetCancellationId(it)) }
    }

    @Test
    fun bindsTheOriginalServerChallengeToTheOfficialOriginAndRequiresVerification() {
        val request = windowsPasskeyRequest(options)
        val client = Json.parseToJsonElement(request.clientData.toString(Charsets.UTF_8)).jsonObject
        assertEquals(challenge, client.getValue("challenge").jsonPrimitive.content)
        assertEquals("webauthn.get", client.getValue("type").jsonPrimitive.content)
        assertEquals("https://starlit-api.rrmtools.uk", client.getValue("origin").jsonPrimitive.content)
        assertEquals("false", client.getValue("crossOrigin").jsonPrimitive.content)
        assertEquals(60000, request.timeoutMillis)
        assertEquals(60000, windowsPasskeyRequest(options.replace("required", "preferred")).timeoutMillis)
        assertEquals(120000, windowsPasskeyRequest(options.replace("60000", "99999999999")).timeoutMillis)
        for (invalid in listOf(
            options.replace("starlit-api.rrmtools.uk", "attacker.test"),
            options.replace("required", "unknown"),
            options.replace(challenge, "bad+challenge"),
            options.replace(challenge, "YWJj"),
            options.replace("60000", "0"),
            options.dropLast(1) + """, "allowCredentials":[{"id":"not-ignored"}]}""",
            options.dropLast(1) + """, "extensions":{"appid":"https://attacker.test"}}""",
        )) assertFailsWith<Exception> { windowsPasskeyRequest(invalid) }
    }

    @Test
    fun nativeStructuresMatchTheWindowsHeaderPrefix() {
        val is64 = Native.POINTER_SIZE == 8
        assertEquals(if (is64) 24 else 16, WebAuthnClientData().size())
        assertEquals(if (is64) 16 else 8, WebAuthnList().size())
        assertEquals(if (is64) 80 else 48, WebAuthnGetOptions().size())
        assertEquals(if (is64) 24 else 16, WebAuthnCredential().size())
        assertEquals(if (is64) 72 else 44, WebAuthnAssertion().size())
    }

    @Test
    fun returnsNativeProofWithOriginalClientDataAndFreesTheAssertion() = runBlocking {
        val api = FakeWebAuthn()
        val ceremony = WindowsPasskeyCeremony(api) { Pointer(1234) }
        assertFalse(ceremony.supportsRegistration)
        val credential = Json.parseToJsonElement(ceremony.get(options)).jsonObject
        val response = credential.getValue("response").jsonObject
        assertEquals("AQID", credential.getValue("id").jsonPrimitive.content)
        assertEquals("AQID", response.getValue("signature").jsonPrimitive.content)
        assertEquals("AQID", response.getValue("userHandle").jsonPrimitive.content)
        assertEquals(windowsPasskeyRequest(options).clientData.toList(),
            Base64.getUrlDecoder().decode(response.getValue("clientDataJSON").jsonPrimitive.content).toList())
        assertTrue(api.freed)
        assertEquals(1, api.verificationRequirement)
        assertEquals(0, api.attachment) // Windows can offer the phone/QR option.
        assertEquals("starlit-api.rrmtools.uk", api.rp)
        assertEquals(1234L, api.window)
    }

    @Test
    fun cancellationAbortsNativePromptBeforeReleasingItsBuffers() = runBlocking {
        val api = FakeWebAuthn(block = true)
        val pending = async { WindowsPasskeyCeremony(api) { Pointer(1234) }.get(options) }
        withContext(Dispatchers.IO) { assertTrue(api.entered.await(5, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        assertTrue(api.cancelled)
        assertTrue(api.returned)
    }

    @Test
    fun timeoutAbortsNativePromptWithoutReturningProof() = runBlocking {
        val api = FakeWebAuthn(block = true)
        assertFailsWith<TimeoutCancellationException> {
            WindowsPasskeyCeremony(api) { Pointer(1234) }.get(options.replace("60000", "1"))
        }
        assertTrue(api.cancelled)
        assertTrue(api.returned)
    }

    @Test
    fun failedNativeProofNeverReturnsACredential() = runBlocking {
        val api = FakeWebAuthn(fail = true)
        assertFailsWith<IllegalStateException> {
            WindowsPasskeyCeremony(api) { Pointer(1234) }.get(options)
        }
        assertFalse(api.freed)
        val noVerification = FakeWebAuthn(verified = false)
        assertFailsWith<IllegalStateException> {
            WindowsPasskeyCeremony(noVerification) { Pointer(1234) }.get(options.replace("required", "preferred"))
        }
        assertTrue(noVerification.freed)
    }

    private class FakeWebAuthn(val block: Boolean = false, val fail: Boolean = false, verified: Boolean = true) : WindowsWebAuthn {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        @Volatile var cancelled = false
        @Volatile var returned = false
        var freed = false
        var verificationRequirement = 0
        var attachment = -1
        var rp = ""
        var window = 0L
        private val bytes = Memory(3).apply { write(0, byteArrayOf(1, 2, 3), 0, 3) }
        private val authData = Memory(37).apply { clear(); setByte(32, if (verified) 0x05 else 0x01) }
        private val assertion = WebAuthnAssertion().apply {
            cbAuthenticatorData = 37; pbAuthenticatorData = authData
            cbSignature = 3; pbSignature = bytes
            cbUserId = 3; pbUserId = bytes
            Credential.cbId = 3; Credential.pbId = bytes
            Credential.pwszCredentialType = WString("public-key")
            write()
        }

        override fun WebAuthNGetApiVersionNumber() = 9
        override fun WebAuthNGetCancellationId(id: Pointer): Int { id.setLong(0, 42); return 0 }
        override fun WebAuthNCancelCurrentOperation(id: Pointer): Int {
            assertEquals(42L, id.getLong(0))
            cancelled = true
            finish.countDown()
            return 0
        }
        override fun WebAuthNAuthenticatorGetAssertion(window: Pointer, rpId: WString,
            client: WebAuthnClientData, options: WebAuthnGetOptions, result: PointerByReference): Int {
            this.window = Pointer.nativeValue(window)
            rp = rpId.toString()
            verificationRequirement = options.dwUserVerificationRequirement
            attachment = options.dwAuthenticatorAttachment
            entered.countDown()
            if (block) {
                check(finish.await(10, TimeUnit.SECONDS))
                assertEquals(42L, options.pCancellationId!!.getLong(0))
                // The client JSON is still live until the OS returns.
                assertTrue(client.pbClientDataJSON!!.getByteArray(0, client.cbClientDataJSON).isNotEmpty())
                returned = true
                return 1
            }
            returned = true
            if (fail) return 1
            result.value = assertion.pointer
            return 0
        }
        override fun WebAuthNFreeAssertion(assertion: Pointer) { freed = true }
    }
}
