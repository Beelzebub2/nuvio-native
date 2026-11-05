package com.nuvio.app.core.diagnostics

internal actual val diagnosticsPlatform: String = "desktop"

internal actual fun createDiagnosticsInstallationId(): String =
    ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
