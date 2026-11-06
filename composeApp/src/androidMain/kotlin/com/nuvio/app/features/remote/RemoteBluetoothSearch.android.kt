package com.nuvio.app.features.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun rememberRemoteBluetoothSearch(): (() -> Unit)? {
    if (!DesktopRemote.transport.bluetoothSupported) return null
    val context = LocalContext.current
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (permissions.all { result[it] == true }) {
            DesktopRemote.transport.discoverBluetooth()
        } else {
            DesktopRemote.status.value = "Allow Nearby devices permission to search for Bluetooth PCs."
        }
    }
    return remember(context, request) {
        {
            if (permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }) {
                DesktopRemote.transport.discoverBluetooth()
            } else request.launch(permissions)
        }
    }
}
