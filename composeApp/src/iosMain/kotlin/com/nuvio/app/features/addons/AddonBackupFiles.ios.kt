package com.nuvio.app.features.addons

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberAddonBackupFileLauncher(
    onRead: (Result<String?>) -> Unit,
    onSaved: (Result<Boolean>) -> Unit,
): AddonBackupFileLauncher? = null
