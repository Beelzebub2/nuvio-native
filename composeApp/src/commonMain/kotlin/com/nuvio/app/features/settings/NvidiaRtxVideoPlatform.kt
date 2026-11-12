package com.nuvio.app.features.settings

internal enum class NvidiaRtxGpuSupport {
    DETECTED,
    NOT_DETECTED,
    UNKNOWN,
}

internal data class NvidiaRtxVideoEnvironment(
    val gpuSupport: NvidiaRtxGpuSupport = NvidiaRtxGpuSupport.UNKNOWN,
    val gpuName: String? = null,
    val nvidiaAppInstalled: Boolean = false,
)

/** GPU enumeration is only a hardware hint, never proof that the NVIDIA driver applied VSR. */
internal fun detectNvidiaRtxGpu(gpuNames: List<String>?): Pair<NvidiaRtxGpuSupport, String?> {
    val names = gpuNames?.map { it.trim() }?.filter { it.isNotBlank() }
        ?: return NvidiaRtxGpuSupport.UNKNOWN to null
    if (names.isEmpty()) return NvidiaRtxGpuSupport.UNKNOWN to null
    val rtx = names.firstOrNull {
        it.contains("NVIDIA", ignoreCase = true) &&
            Regex("\\bRTX\\s*(?:PRO\\s+)?[A-Z]?\\s*\\d{3,4}\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(it)
    }
    return if (rtx != null) {
        NvidiaRtxGpuSupport.DETECTED to rtx
    } else {
        NvidiaRtxGpuSupport.NOT_DETECTED to null
    }
}

internal expect object NvidiaRtxVideoPlatform {
    suspend fun inspect(): NvidiaRtxVideoEnvironment
    fun openNvidiaApp(): Boolean
    fun openNvidiaAppDownload(): Boolean
}
