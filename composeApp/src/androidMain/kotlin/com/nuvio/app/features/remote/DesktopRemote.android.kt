package com.nuvio.app.features.remote

internal actual fun createDesktopRemoteTransport(): DesktopRemoteTransport = LanDesktopRemote()
