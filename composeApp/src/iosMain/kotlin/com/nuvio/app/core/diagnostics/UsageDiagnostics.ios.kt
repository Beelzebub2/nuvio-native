package com.nuvio.app.core.diagnostics

internal actual val diagnosticsPlatform: String = "ios"

internal actual fun createDiagnosticsInstallationId(): String =
    platform.Foundation.NSUUID().UUIDString.replace("-", "").lowercase()
