package com.nuvio.app.features.remote

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RemoteQrEncodingTest {
    @Test
    fun displayedMatrixHasQuietZoneAndDecodesToOnlyTheDiscoveryHint() {
        val payload = checkNotNull(remotePairingQrPayload("192.168.1.50"))
        val matrix = remoteQrMatrix(payload)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (x < 4 || y < 4 || x >= matrix.width - 4 || y >= matrix.height - 4)
                assertFalse(matrix[x, y])
        }
        val scale = 6
        val width = matrix.width * scale
        val pixels = IntArray(width * width) { index ->
            if (matrix[(index % width) / scale, (index / width) / scale]) 0xff000000.toInt() else -1
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels))))
        assertEquals(payload, decoded.text)
        assertEquals("192.168.1.50", parseRemotePairingQr(decoded.text))
    }
}
