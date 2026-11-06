package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable

// A PC hosts the Bluetooth remote; it never searches for other PCs.
@Composable
internal actual fun rememberRemoteBluetoothSearch(): (() -> Unit)? = null
