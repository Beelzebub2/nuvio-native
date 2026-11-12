package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NvidiaRtxVideoPlatformTest {
    @Test
    fun detectsRtxBehindVirtualDisplayAdapter() {
        val (support, name) = detectNvidiaRtxGpu(
            listOf("Parsec Virtual Display Adapter", "NVIDIA GeForce RTX 5070 Ti"),
        )
        assertEquals(NvidiaRtxGpuSupport.DETECTED, support)
        assertEquals("NVIDIA GeForce RTX 5070 Ti", name)
    }

    @Test
    fun recognizesProfessionalRtxCards() {
        for (name in listOf("NVIDIA RTX A5000", "NVIDIA RTX PRO 6000 Blackwell", "NVIDIA Quadro RTX 5000")) {
            assertEquals(NvidiaRtxGpuSupport.DETECTED, detectNvidiaRtxGpu(listOf(name)).first, name)
        }
    }

    @Test
    fun doesNotClaimSupportForNonRtxOrUnrelatedAdapters() {
        val (support, name) = detectNvidiaRtxGpu(
            listOf("Intel(R) UHD Graphics", "NVIDIA GeForce GTX 1080", "Parsec Virtual Display Adapter"),
        )
        assertEquals(NvidiaRtxGpuSupport.NOT_DETECTED, support)
        assertNull(name)
        assertEquals(
            NvidiaRtxGpuSupport.NOT_DETECTED,
            detectNvidiaRtxGpu(listOf("NVIDIA GeForce RTX Virtual Adapter")).first,
        )
    }

    @Test
    fun missingOrFailedAdapterProbeIsUnknown() {
        assertEquals(NvidiaRtxGpuSupport.UNKNOWN, detectNvidiaRtxGpu(null).first)
        assertEquals(NvidiaRtxGpuSupport.UNKNOWN, detectNvidiaRtxGpu(emptyList()).first)
        assertEquals(NvidiaRtxGpuSupport.UNKNOWN, detectNvidiaRtxGpu(listOf("  ")).first)
    }
}
