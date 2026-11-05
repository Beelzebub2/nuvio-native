package com.nuvio.app.features.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.nuvio
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

internal fun remoteQrMatrix(payload: String) = QRCodeWriter().encode(
    payload, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4),
)

@Composable
internal actual fun DesktopPairingQr(addresses: List<String>) {
    val candidates = addresses.filter { remotePairingQrPayload(it) != null }
    var selected by remember(candidates) { mutableStateOf(candidates.firstOrNull()) }
    val address = selected ?: return
    val matrix = remember(address) { remoteQrMatrix(checkNotNull(remotePairingQrPayload(address))) }
    val description = stringResource(Res.string.remote_qr_image_description, address)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A white tile with rounded corners keeps the code readable on dark themes.
        Box(
            Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(208.dp).testTag("desktop-pairing-qr").semantics {
                contentDescription = description
            }) {
                drawRect(Color.White)
                // Whole pixels and a four-module quiet zone keep the code crisp at any desktop density.
                val cell = floor(size.minDimension / matrix.width).coerceAtLeast(1f)
                val inset = floor((size.minDimension - cell * matrix.width) / 2f)
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(Color.Black,
                        Offset(inset + x * cell, inset + y * cell), Size(cell, cell))
                }
            }
        }
        Text(
            address,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        if (candidates.size > 1) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                NuvioCompactActionButton(
                    label = stringResource(Res.string.remote_qr_change_network),
                    icon = Icons.Rounded.Lan,
                    trailingIcon = Icons.Rounded.ExpandMore,
                    size = NuvioActionSize.Small,
                    onClick = { expanded = true },
                )
                Menu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    candidates.forEach { candidate ->
                        MenuItem(
                            text = candidate,
                            selected = candidate == address,
                            onClick = {
                                selected = candidate
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal actual fun rememberRemoteQrScanner(onResult: (String?) -> Unit): (() -> Unit)? = null
