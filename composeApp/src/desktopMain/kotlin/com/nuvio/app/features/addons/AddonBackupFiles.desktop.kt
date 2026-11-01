package com.nuvio.app.features.addons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.nuvio.app.core.ui.DesktopFileDialogs

@Composable
internal actual fun rememberAddonBackupFileLauncher(
    onRead: (Result<String?>) -> Unit,
    onSaved: (Result<Boolean>) -> Unit,
): AddonBackupFileLauncher? {
    val scope = rememberCoroutineScope()
    return AddonBackupFileLauncher(
        save = { payload -> scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val file = chooseBackupFile(save = true) ?: return@runCatching false
                // Replace only after the entire backup has been written successfully.
                val temporary = Files.createTempFile(file.toPath().parent, ".starlit-addons-", ".tmp")
                try {
                    Files.write(temporary, payload.encodeToByteArray())
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
                true
            } }
            onSaved(result)
        } },
        open = { scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val file = chooseBackupFile(save = false) ?: return@runCatching null
                file.inputStream().use {
                    val bytes = it.readNBytes(AddonBackupMaxBytes + 1)
                    if (bytes.size > AddonBackupMaxBytes) throw AddonBackupException(AddonBackupError.TooLarge)
                    bytes.decodeToString(throwOnInvalidSequence = true)
                }
            } }
            onRead(result)
        } },
    )
}

private suspend fun chooseBackupFile(save: Boolean): File? {
    val title = getString(if (save) Res.string.addons_backup_export else Res.string.addons_backup_import)
    val overwrite = getString(Res.string.addons_backup_overwrite)
    return DesktopFileDialogs.json(title, save, overwrite)
}
