package com.nuvio.app.features.myanimelist

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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

/** Windows DPAPI binds account tokens to the current Windows user; no key is bundled. */
internal fun protectMalCredential(value: String, decrypt: Boolean = false): String {
    check(System.getProperty("os.name").startsWith("Windows")) { "MyAnimeList secure sign-in currently requires Windows." }
    val operation = if (decrypt) "Unprotect" else "Protect"
    val script = "Add-Type -AssemblyName System.Security; try { " +
        "\$bytes=[Convert]::FromBase64String([Console]::In.ReadToEnd()); " +
        "\$result=[Security.Cryptography.ProtectedData]::$operation(\$bytes,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
        "[Console]::Out.Write([Convert]::ToBase64String(\$result)) } catch { exit 1 }"
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(64 * 1024 + 1) } }
        val input = if (decrypt) value else Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        process.outputStream.use { it.write(input.toByteArray(Charsets.US_ASCII)) }
        check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Windows could not unlock MyAnimeList credentials. Reconnect your account." }
        val bytes = output.get(2, TimeUnit.SECONDS)
        check(bytes.size <= 64 * 1024) { "Credential response is too large" }
        val encoded = bytes.toString(Charsets.US_ASCII).trim()
        return if (decrypt) Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8) else encoded
    } finally { if (process.isAlive) process.destroyForcibly() }
}
