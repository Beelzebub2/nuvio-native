package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
internal actual fun DesktopPairingQr(addresses: List<String>) = Unit

@Composable
internal actual fun rememberRemoteQrScanner(onResult: (String?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latestResult = rememberUpdatedState(onResult)
    return remember(context) {
        {
            try {
                // Optional Play services are initialized only after an explicit scan request.
                val scanner = GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build())
                scanner.startScan()
                    .addOnSuccessListener { latestResult.value(it.rawValue) }
                    .addOnFailureListener { latestResult.value(null) }
                // Cancellation leaves the current connection and manual input unchanged.
            } catch (_: Exception) {
                latestResult.value(null)
            }
            Unit
        }
    }
}
