package com.nuvio.app.features.myanimelist

import com.nuvio.app.core.windows.WindowsDpapi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Base64

/** Encryption can block; commit only while this account lifecycle is still current. */
internal suspend fun commitProtectedMalCredential(
    plaintext: String, lifecycleLock: Any, isCurrent: () -> Boolean, save: (String) -> Unit,
    protect: (String) -> String = { protectMalCredential(it) },
) {
    val encrypted = protect(plaintext)
    val context = currentCoroutineContext()
    context.ensureActive()
    synchronized(lifecycleLock) {
        context.ensureActive()
        check(isCurrent()) { "Account changed; retry from the current profile." }
        save(encrypted)
    }
}

/**
 * Windows DPAPI binds account tokens to the current Windows user; no key is bundled.
 * Runs in-process: sealing secrets through a hidden PowerShell made antivirus
 * engines treat Starlit as a credential stealer.
 */
internal fun protectMalCredential(value: String, decrypt: Boolean = false): String {
    check(System.getProperty("os.name").startsWith("Windows")) { "MyAnimeList secure sign-in currently requires Windows." }
    return try {
        if (decrypt) {
            WindowsDpapi.unprotect(Base64.getDecoder().decode(value.trim())).toString(Charsets.UTF_8)
        } else {
            Base64.getEncoder().encodeToString(WindowsDpapi.protect(value.toByteArray(Charsets.UTF_8)))
        }
    } catch (error: Exception) {
        throw IllegalStateException("Windows could not unlock MyAnimeList credentials. Reconnect your account.", error)
    }
}
