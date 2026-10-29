package com.nuvio.app.features.addons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberAddonBackupFileLauncher(
    onRead: (Result<String?>) -> Unit,
    onSaved: (Result<Boolean>) -> Unit,
): AddonBackupFileLauncher? {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null) onSaved(Result.success(false))
        else scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                checkNotNull(payload)
                checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(payload.encodeToByteArray()) }
                true
            } }
            onSaved(result)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onRead(Result.success(null))
        else scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                checkNotNull(resolver.openInputStream(uri)).use { stream ->
                    val bytes = stream.readBytesBounded(AddonBackupMaxBytes + 1)
                    if (bytes.size > AddonBackupMaxBytes) throw AddonBackupException(AddonBackupError.TooLarge)
                    bytes.decodeToString(throwOnInvalidSequence = true)
                }
            } }
            onRead(result)
        }
    }
    return AddonBackupFileLauncher(
        save = { payload ->
            pendingExport = payload
            try { save.launch("starlit-addons.json") } catch (error: Exception) { pendingExport = null; onSaved(Result.failure(error)) }
        },
        open = { try { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } catch (error: Exception) { onRead(Result.failure(error)) } },
    )
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
