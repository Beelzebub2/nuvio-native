package com.nuvio.app.core.auth

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val RP_ID = "starlit-api.rrmtools.uk"
private const val ORIGIN = "https://$RP_ID"

internal data class WindowsPasskeyRequest(val clientData: ByteArray, val timeoutMillis: Int)

/** This native client is exclusively for Starlit's discoverable, UV-required credentials. */
internal fun windowsPasskeyRequest(optionsJson: String): WindowsPasskeyRequest {
    val options = Json.parseToJsonElement(optionsJson).jsonObject
    require(options["rpId"]?.jsonPrimitive?.content == RP_ID) { "Invalid passkey relying party" }
    require(options["userVerification"]?.jsonPrimitive?.content.let {
        it == null || it in setOf("required", "preferred", "discouraged")
    }) {
        "Invalid passkey user verification policy"
    }
    // Starlit signs in without an account identifier. Never silently ignore an allow list.
    require(options["allowCredentials"]?.jsonArray?.isEmpty() != false) { "Unsupported passkey credential list" }
    require(options["extensions"]?.jsonObject?.isEmpty() != false) { "Unsupported passkey extensions" }
    val challenge = options["challenge"]?.jsonPrimitive?.content.orEmpty()
    require(challenge.length in 22..1024 && challenge.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        "Invalid passkey challenge"
    }
    val decoded = Base64.getUrlDecoder().decode(challenge)
    require(decoded.size in 16..768 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == challenge) {
        "Invalid passkey challenge"
    }
    val timeout = options["timeout"]?.jsonPrimitive?.longOrNull ?: 120_000L
    require(timeout > 0) { "Invalid passkey timeout" }
    return WindowsPasskeyRequest(buildJsonObject {
        put("type", "webauthn.get")
        put("challenge", challenge)
        put("origin", ORIGIN)
        put("crossOrigin", false)
    }.toString().toByteArray(Charsets.UTF_8), timeout.coerceAtMost(120_000L).toInt())
}

internal class WindowsPasskeyCeremony(
    private val api: WindowsWebAuthn,
    private val ownerWindow: suspend () -> Pointer,
) : PasskeyCeremony {
    // Registration remains on Android; Windows only consumes the existing phone credential.
    override val supportsRegistration = false
    override suspend fun create(optionsJson: String): String = error("Add your passkey in Starlit on your phone")

    override suspend fun get(optionsJson: String): String = operationMutex.withLock {
        val request = windowsPasskeyRequest(optionsJson)
        val window = ownerWindow()
        require(Pointer.nativeValue(window) != 0L) { "Return to Starlit and try again" }
        // Keep GUID and all pointed-to buffers alive until the synchronous API has returned.
        Memory(16).use { cancellationId ->
            check(api.WebAuthNGetCancellationId(cancellationId) == 0) { "Unable to start passkey sign-in" }
            coroutineScope {
                val operation = async(Dispatchers.IO) {
                    ensureActive()
                    getAssertion(window, request, cancellationId)
                }
                try {
                    withTimeout(request.timeoutMillis.toLong() + 5_000) { operation.await() }
                } finally {
                    operation.cancel()
                    // Repeat cancellation until the native call returns: cancellation can race
                    // with entry into WebAuthNAuthenticatorGetAssertion. Never free its GUID early.
                    withContext(NonCancellable) {
                        while (!operation.isCompleted) {
                            api.WebAuthNCancelCurrentOperation(cancellationId)
                            delay(50)
                        }
                    }
                }
            }
        }
    }

    private suspend fun getAssertion(window: Pointer, request: WindowsPasskeyRequest, cancellationId: Pointer): String {
        Memory(request.clientData.size.toLong()).use { jsonBuffer ->
            jsonBuffer.write(0, request.clientData, 0, request.clientData.size)
            val client = WebAuthnClientData().apply {
                cbClientDataJSON = request.clientData.size
                pbClientDataJSON = jsonBuffer
            }
            val options = WebAuthnGetOptions().apply {
                dwTimeoutMilliseconds = request.timeoutMillis
                pCancellationId = cancellationId
            }
            val output = PointerByReference()
            try {
                val status = api.WebAuthNAuthenticatorGetAssertion(window, WString(RP_ID), client, options, output)
                currentCoroutineContext().ensureActive()
                check(status == 0) { "Passkey sign-in was cancelled or unavailable. Try again" }
                val result = WebAuthnAssertion(requireNotNull(output.value) { "No passkey was returned" })
                check(result.dwVersion >= 1 && result.Credential.pwszCredentialType?.toString() == "public-key") {
                    "Invalid passkey response"
                }
                val id = encodeNativeBytes(result.Credential.pbId, result.Credential.cbId)
                require(id.isNotEmpty()) { "No passkey was returned" }
                val authenticatorData = nativeBytes(result.pbAuthenticatorData, result.cbAuthenticatorData)
                // GoTrue can advertise UV "preferred". This phone sign-in path always
                // requests UV REQUIRED and refuses proof without the signed UV flag.
                check(authenticatorData.size >= 37 && (authenticatorData[32].toInt() and 0x05) == 0x05) {
                    "Unlock your phone to verify this passkey"
                }
                return buildJsonObject {
                    put("id", id)
                    put("rawId", id)
                    put("type", "public-key")
                    put("clientExtensionResults", buildJsonObject {})
                    put("response", buildJsonObject {
                        put("clientDataJSON", Base64.getUrlEncoder().withoutPadding().encodeToString(request.clientData))
                        put("authenticatorData", Base64.getUrlEncoder().withoutPadding().encodeToString(authenticatorData))
                        put("signature", encodeNativeBytes(result.pbSignature, result.cbSignature))
                        if (result.cbUserId > 0) put("userHandle", encodeNativeBytes(result.pbUserId, result.cbUserId))
                    })
                }.toString()
            } finally {
                output.value?.let(api::WebAuthNFreeAssertion)
            }
        }
    }

    private fun encodeNativeBytes(pointer: Pointer?, size: Int): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nativeBytes(pointer, size))
    }

    private fun nativeBytes(pointer: Pointer?, size: Int): ByteArray {
        require(size in 1..65536 && pointer != null) { "Invalid passkey response" }
        return pointer.getByteArray(0, size)
    }

    private companion object { val operationMutex = Mutex() }
}

// ABI matches Microsoft's webauthn.h, including inline (not pointer) structures.
// https://github.com/microsoft/webauthn/blob/master/webauthn.h
internal interface WindowsWebAuthn : StdCallLibrary {
    fun WebAuthNGetApiVersionNumber(): Int
    fun WebAuthNGetCancellationId(id: Pointer): Int
    fun WebAuthNCancelCurrentOperation(id: Pointer): Int
    fun WebAuthNAuthenticatorGetAssertion(window: Pointer, rpId: WString, client: WebAuthnClientData,
        options: WebAuthnGetOptions, result: PointerByReference): Int
    fun WebAuthNFreeAssertion(assertion: Pointer)
}

@Structure.FieldOrder("dwVersion", "cbClientDataJSON", "pbClientDataJSON", "pwszHashAlgId")
internal class WebAuthnClientData : Structure() {
    @JvmField var dwVersion = 1
    @JvmField var cbClientDataJSON = 0
    @JvmField var pbClientDataJSON: Pointer? = null
    @JvmField var pwszHashAlgId = WString("SHA-256")
}

@Structure.FieldOrder("count", "entries")
internal class WebAuthnList : Structure() {
    @JvmField var count = 0
    @JvmField var entries: Pointer? = null
}

// Version 3 supplies a cancellation GUID; hybrid transport needs no linked-device data.
// Attachment ANY (0) leaves phone/QR, security key and OS credential choices to Windows.
@Structure.FieldOrder("dwVersion", "dwTimeoutMilliseconds", "CredentialList", "Extensions",
    "dwAuthenticatorAttachment", "dwUserVerificationRequirement", "dwFlags", "pwszU2fAppId",
    "pbU2fAppId", "pCancellationId")
internal class WebAuthnGetOptions : Structure() {
    @JvmField var dwVersion = 3
    @JvmField var dwTimeoutMilliseconds = 120_000
    @JvmField var CredentialList = WebAuthnList()
    @JvmField var Extensions = WebAuthnList()
    @JvmField var dwAuthenticatorAttachment = 0
    @JvmField var dwUserVerificationRequirement = 1
    @JvmField var dwFlags = 0
    @JvmField var pwszU2fAppId: Pointer? = null
    @JvmField var pbU2fAppId: Pointer? = null
    @JvmField var pCancellationId: Pointer? = null
}

@Structure.FieldOrder("dwVersion", "cbId", "pbId", "pwszCredentialType")
internal class WebAuthnCredential : Structure() {
    @JvmField var dwVersion = 1
    @JvmField var cbId = 0
    @JvmField var pbId: Pointer? = null
    @JvmField var pwszCredentialType: WString? = null
}

// Read only the version-1 prefix, which is present in every returned assertion.
@Structure.FieldOrder("dwVersion", "cbAuthenticatorData", "pbAuthenticatorData", "cbSignature",
    "pbSignature", "Credential", "cbUserId", "pbUserId")
internal class WebAuthnAssertion(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField var dwVersion = 1
    @JvmField var cbAuthenticatorData = 0
    @JvmField var pbAuthenticatorData: Pointer? = null
    @JvmField var cbSignature = 0
    @JvmField var pbSignature: Pointer? = null
    @JvmField var Credential = WebAuthnCredential()
    @JvmField var cbUserId = 0
    @JvmField var pbUserId: Pointer? = null
    init { if (pointer != null) read() }
}
