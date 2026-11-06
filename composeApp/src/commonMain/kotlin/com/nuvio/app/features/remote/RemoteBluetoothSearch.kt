package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable

/**
 * Starts a Bluetooth PC search after the platform's runtime permission flow, or
 * null where Bluetooth control is unavailable. The caller draws its own button.
 */
@Composable
internal expect fun rememberRemoteBluetoothSearch(): (() -> Unit)?
