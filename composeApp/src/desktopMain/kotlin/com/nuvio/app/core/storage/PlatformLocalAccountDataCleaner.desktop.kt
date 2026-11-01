package com.nuvio.app.core.storage

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        com.nuvio.app.features.airing.NativeAiring.reset()
        DesktopStorage.wipe()
    }
}
