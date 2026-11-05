package com.nuvio.app.features.remote

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows Bluetooth Classic service. A small native RFCOMM helper forwards
 * bytes into the app's private loopback listener; all credentials and
 * authorization stay inside the existing encrypted Starlit remote session.
 */
internal class WindowsBluetoothRemoteHost : RemoteBluetoothHost {
    private var process: Process? = null
    private var folder: Path? = null

    @Synchronized
    override fun start(loopbackPort: Int): Boolean {
        stop()
        if (!System.getProperty("os.name", "").contains("windows", ignoreCase = true) ||
            loopbackPort !in 1024..65535) return false
        val input = javaClass.getResourceAsStream("/native/windows/starlit_bluetooth_bridge.exe") ?: return false
        return runCatching {
            val directory = Files.createTempDirectory("starlit-remote-bluetooth-")
            folder = directory
            val binary = directory.resolve("starlit_bluetooth_bridge.exe")
            input.use { Files.copy(it, binary, StandardCopyOption.REPLACE_EXISTING) }
            val launched = ProcessBuilder(binary.toString(), loopbackPort.toString())
                .redirectErrorStream(true)
                .start()
            process = launched
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "starlit-bluetooth-start").apply { isDaemon = true }
            }
            try {
                val ready = executor.submit<Boolean> {
                    launched.inputStream.bufferedReader().readLine() == "READY"
                }
                val started = runCatching { ready.get(4, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!started) stop()
                started
            } finally {
                executor.shutdownNow()
            }
        }.getOrElse {
            stop()
            false
        }
    }

    @Synchronized
    override fun stop() {
        process?.let { child ->
            runCatching { child.outputStream.close() }
            runCatching { child.destroy() }
            if (runCatching { !child.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                runCatching { child.destroyForcibly() }
            }
        }
        process = null
        folder?.let { dir ->
            runCatching { Files.deleteIfExists(dir.resolve("starlit_bluetooth_bridge.exe")) }
            runCatching { Files.deleteIfExists(dir) }
        }
        folder = null
    }
}
