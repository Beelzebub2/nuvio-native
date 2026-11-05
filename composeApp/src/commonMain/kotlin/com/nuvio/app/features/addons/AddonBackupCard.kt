package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal class AddonBackupFileLauncher(val save: (String) -> Unit, val open: () -> Unit)

@Composable
internal expect fun rememberAddonBackupFileLauncher(
    onRead: (Result<String?>) -> Unit,
    onSaved: (Result<Boolean>) -> Unit,
): AddonBackupFileLauncher?

/** Below this width the export/import buttons move under the description. */
private val BackupWideLayoutMinWidth = 640.dp

/** Below this width the two buttons stack so their labels are never cut off. */
private val BackupTwoButtonMinWidth = 440.dp

@Composable
internal fun AddonBackupCard(modifier: Modifier = Modifier) {
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
    val tokens = MaterialTheme.nuvio
    val exportAction: @Composable (Modifier) -> Unit = { buttonModifier ->
        NuvioCompactActionButton(
            label = stringResource(if (files != null) Res.string.addons_backup_export else Res.string.addons_backup_copy),
            icon = if (files != null) Icons.Rounded.FileUpload else Icons.Rounded.ContentCopy,
            enabled = !busy,
            size = NuvioActionSize.Medium,
            modifier = buttonModifier,
            onClick = {
                clearMessage()
                try {
                    val payload = AddonRepository.exportBackup()
                    if (files != null) { busy = true; files.save(payload) }
                    else { clipboard.setText(AnnotatedString(payload)); saved = true }
                } catch (failure: AddonBackupException) { error = failure.reason }
            },
        )
    }
    val importAction: @Composable (Modifier) -> Unit = { buttonModifier ->
        NuvioCompactActionButton(
            label = stringResource(if (files != null) Res.string.addons_backup_import else Res.string.addons_backup_paste),
            icon = if (files != null) Icons.Rounded.FileDownload else Icons.Rounded.ContentPaste,
            enabled = !busy && canImport,
            tone = NuvioCompactActionTone.Primary,
            size = NuvioActionSize.Medium,
            modifier = buttonModifier,
            onClick = {
                clearMessage()
                requestedProfile = ProfileRepository.activeProfileId
                requestedRevision = AddonRepository.backupProfileRevision
                if (files != null) { busy = true; files.open() }
                else { pasteText = clipboard.getText()?.text.orEmpty().take(AddonBackupMaxBytes + 1) }
            },
        )
    }
    // Desktop matches the bordered groups on its add-ons page; phones match the other cards.
    val shape = if (isDesktop) RoundedCornerShape(NuvioTokens.Radius.xl) else tokens.shapes.card
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surface)
            .then(if (isDesktop) Modifier.border(NuvioTokens.Border.hairline, tokens.colors.borderSubtle, shape) else Modifier)
            .padding(if (isDesktop) 16.dp else tokens.spacing.cardPadding),
    ) {
        val wide = maxWidth >= BackupWideLayoutMinWidth
        val stacked = maxWidth < BackupTwoButtonMinWidth
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = if (wide) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ImportExport, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.addons_backup_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                    Text(
                        stringResource(Res.string.addons_backup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        exportAction(Modifier)
                        importAction(Modifier)
                    }
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    importAction(Modifier.fillMaxWidth())
                    exportAction(Modifier.fillMaxWidth())
                }
            } else if (!wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    exportAction(Modifier.weight(1f))
                    importAction(Modifier.weight(1f))
                }
            }
            if (!canImport) {
                BackupStatus(stringResource(Res.string.profile_primary_addons_required), Icons.Rounded.Info, tokens.colors.textMuted)
            }
            error?.let {
                val resource = when (it) {
                    AddonBackupError.Invalid -> Res.string.addons_backup_invalid
                    AddonBackupError.UnsupportedVersion -> Res.string.addons_backup_version
                    AddonBackupError.TooLarge -> Res.string.addons_backup_too_large
                    AddonBackupError.ProfileChanged -> Res.string.addons_backup_profile_changed
                    AddonBackupError.PrimaryProfileRequired -> Res.string.profile_primary_addons_required
                    AddonBackupError.FileAccess -> Res.string.addons_backup_file_error
                }
                BackupStatus(stringResource(resource), Icons.Rounded.ErrorOutline, tokens.colors.danger)
            }
            success?.let {
                BackupStatus(
                    stringResource(Res.string.addons_backup_imported, it.imported, it.skipped),
                    Icons.Rounded.CheckCircle,
                    tokens.colors.success,
                )
            }
            if (saved) {
                BackupStatus(
                    stringResource(if (files != null) Res.string.addons_backup_saved else Res.string.addons_backup_copied),
                    Icons.Rounded.CheckCircle,
                    tokens.colors.success,
                )
            }
        }
    }
    pasteText?.let { text ->
        AddonBackupPasteDialog(
            text = text,
            onTextChange = { pasteText = it.take(AddonBackupMaxBytes + 1) },
            onImport = { pasteText = null; importPayload(text) },
            onDismiss = { pasteText = null },
        )
    }
}

@Composable
private fun BackupStatus(text: String, icon: ImageVector, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary)
    }
}

/** Phones without a file picker paste the backup text here instead. */
@Composable
internal fun AddonBackupPasteDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.addons_backup_paste),
        message = stringResource(Res.string.addons_backup_paste_hint),
    ) {
        DialogTextField(
            value = text,
            onValueChange = onTextChange,
            label = stringResource(Res.string.addons_backup_json),
            singleLine = false,
            minLines = 5,
            maxLines = 9,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.addons_backup_import),
                onClick = onImport,
                style = DialogButtonStyle.Primary,
                enabled = text.isNotBlank(),
            )
        }
    }
}
