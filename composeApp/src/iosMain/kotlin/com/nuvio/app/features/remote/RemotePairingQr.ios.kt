package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable

@Composable
internal actual fun DesktopPairingQr(addresses: List<String>) = Unit

@Composable
internal actual fun rememberRemoteQrScanner(onResult: (String?) -> Unit): (() -> Unit)? = null
