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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
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
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // A white tile with rounded corners keeps the code readable on dark themes.
        Box(
            Modifier.size(260.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().testTag("desktop-pairing-qr").semantics {
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
        Row(
            Modifier
                .fillMaxWidth()
                .background(tokens.colors.surfaceCard, tokens.shapes.compactCard)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.remote_window_local_address),
                    style = MaterialTheme.nuvioTypeScale.labelSm,
                    fontWeight = FontWeight.Normal,
                    color = tokens.colors.textMuted,
                )
                Text(
                    address,
                    style = MaterialTheme.typography.bodyLarge.tabular,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NuvioSquareIconButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(Res.string.remote_window_copy_address),
                onClick = { clipboard.setText(AnnotatedString(address)) },
                size = 36.dp,
                iconSize = 18.dp,
            )
        }
        if (candidates.size > 1) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                NuvioPillButton(
                    text = stringResource(Res.string.remote_qr_change_network),
                    onClick = { expanded = true },
                    icon = Icons.Rounded.Lan,
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
