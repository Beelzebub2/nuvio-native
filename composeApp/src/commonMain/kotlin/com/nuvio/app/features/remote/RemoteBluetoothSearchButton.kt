package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable

/** Platform owns OS runtime permission flow before starting Bluetooth discovery. */
@Composable
internal expect fun RemoteBluetoothSearchButton(enabled: Boolean)
