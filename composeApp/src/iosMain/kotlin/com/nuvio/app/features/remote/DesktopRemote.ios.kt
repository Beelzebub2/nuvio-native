package com.nuvio.app.features.remote

internal actual fun createDesktopRemoteTransport(): DesktopRemoteTransport = object : DesktopRemoteTransport {
    override val supported = false
    override fun start(account: RemoteAccount, host: Boolean) {}
    override fun stop() {}
    override fun discover(localIp: String?) {}
    override fun connect(desktop: RemoteDesktop) {}
    override fun confirmPairing() {}
    override fun disconnect() {}
    override fun send(command: RemoteCommand) {}
}
