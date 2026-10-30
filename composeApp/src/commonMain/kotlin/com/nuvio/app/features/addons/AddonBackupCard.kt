package com.nuvio.app.features.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal class AddonBackupFileLauncher(val save: (String) -> Unit, val open: () -> Unit)

@Composable
internal expect fun rememberAddonBackupFileLauncher(
    onRead: (Result<String?>) -> Unit,
    onSaved: (Result<Boolean>) -> Unit,
): AddonBackupFileLauncher?

@Composable
internal fun AddonBackupCard() {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profile = profileState.activeProfile
    val canImport = profile == null || profile.profileIndex == 1 || !profile.usesPrimaryAddons
    val clipboard = LocalClipboardManager.current
    var requestedProfile by rememberSaveable { mutableStateOf(ProfileRepository.activeProfileId) }
    var requestedRevision by rememberSaveable { mutableStateOf(AddonRepository.backupProfileRevision) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<AddonBackupError?>(null) }
    var success by remember { mutableStateOf<AddonBackupMerge?>(null) }
    var saved by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf<String?>(null) }
    fun clearMessage() { error = null; success = null; saved = false }
    val importPayload: (String) -> Unit = { payload ->
        try {
            success = AddonRepository.importBackup(payload, requestedProfile, requestedRevision)
        } catch (failure: AddonBackupException) {
            error = failure.reason
        }
    }
    val files = rememberAddonBackupFileLauncher(
        onRead = { result ->
            busy = false
            result.fold(onSuccess = { it?.let(importPayload) }, onFailure = {
                error = (it as? AddonBackupException)?.reason ?: AddonBackupError.FileAccess
            })
        },
        onSaved = { result ->
            busy = false
            result.fold(onSuccess = { saved = it }, onFailure = { error = AddonBackupError.FileAccess })
        },
    )
    NuvioSurfaceCard {
        Text(stringResource(Res.string.addons_backup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.addons_backup_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NuvioCompactActionButton(
                label = stringResource(if (files != null) Res.string.addons_backup_export else Res.string.addons_backup_copy),
                icon = null,
                enabled = !busy,
                onClick = {
                    clearMessage()
                    try {
                        val payload = AddonRepository.exportBackup()
                        if (files != null) { busy = true; files.save(payload) }
                        else { clipboard.setText(AnnotatedString(payload)); saved = true }
                    } catch (failure: AddonBackupException) { error = failure.reason }
                },
            )
            NuvioCompactActionButton(
                label = stringResource(if (files != null) Res.string.addons_backup_import else Res.string.addons_backup_paste),
                icon = null,
                enabled = !busy && canImport,
                onClick = {
                    clearMessage()
                    requestedProfile = ProfileRepository.activeProfileId
                    requestedRevision = AddonRepository.backupProfileRevision
                    if (files != null) { busy = true; files.open() }
                    else { pasteText = clipboard.getText()?.text.orEmpty().take(AddonBackupMaxBytes + 1) }
                },
            )
        }
        if (!canImport) Text(stringResource(Res.string.profile_primary_addons_required), style = MaterialTheme.typography.bodySmall)
        error?.let {
            val resource = when (it) {
                AddonBackupError.Invalid -> Res.string.addons_backup_invalid
                AddonBackupError.UnsupportedVersion -> Res.string.addons_backup_version
                AddonBackupError.TooLarge -> Res.string.addons_backup_too_large
                AddonBackupError.ProfileChanged -> Res.string.addons_backup_profile_changed
                AddonBackupError.PrimaryProfileRequired -> Res.string.profile_primary_addons_required
                AddonBackupError.FileAccess -> Res.string.addons_backup_file_error
            }
            Text(stringResource(resource), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        success?.let { Text(stringResource(Res.string.addons_backup_imported, it.imported, it.skipped), style = MaterialTheme.typography.bodySmall) }
        if (saved) Text(stringResource(if (files != null) Res.string.addons_backup_saved else Res.string.addons_backup_copied), style = MaterialTheme.typography.bodySmall)
    }
    pasteText?.let { text ->
        AlertDialog(
            onDismissRequest = { pasteText = null },
            title = { Text(stringResource(Res.string.addons_backup_paste)) },
            text = { OutlinedTextField(value = text, onValueChange = { pasteText = it.take(AddonBackupMaxBytes + 1) },
                modifier = Modifier.heightIn(max = 240.dp), label = { Text(stringResource(Res.string.addons_backup_json)) }) },
            confirmButton = { TextButton(onClick = { pasteText = null; importPayload(text) }) { Text(stringResource(Res.string.addons_backup_import)) } },
            dismissButton = { TextButton(onClick = { pasteText = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
