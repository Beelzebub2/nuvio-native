package com.nuvio.app.features.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import com.nuvio.app.core.ui.NuvioCompactActionButton
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.remote_bluetooth_search
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun RemoteBluetoothSearchButton(enabled: Boolean) {
    if (!DesktopRemote.transport.bluetoothSupported) return
    val context = LocalContext.current
    val permissions = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (permissions.all { result[it] == true }) {
            DesktopRemote.transport.discoverBluetooth()
        } else {
            DesktopRemote.status.value = "Allow Nearby devices permission to search for Bluetooth PCs."
        }
    }
    NuvioCompactActionButton(
        label = stringResource(Res.string.remote_bluetooth_search),
        icon = Icons.Rounded.BluetoothSearching,
        enabled = enabled,
        modifier = Modifier.testTag("desktop-remote-search-bluetooth"),
        onClick = {
            if (permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }) {
                DesktopRemote.transport.discoverBluetooth()
            } else request.launch(permissions)
        },
    )
}
