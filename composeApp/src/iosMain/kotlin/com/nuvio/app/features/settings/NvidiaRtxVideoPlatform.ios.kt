package com.nuvio.app.features.settings

internal actual object NvidiaRtxVideoPlatform {
    actual suspend fun inspect(): NvidiaRtxVideoEnvironment = NvidiaRtxVideoEnvironment()
    actual fun openNvidiaApp(): Boolean = false
    actual fun openNvidiaAppDownload(): Boolean = false
}
