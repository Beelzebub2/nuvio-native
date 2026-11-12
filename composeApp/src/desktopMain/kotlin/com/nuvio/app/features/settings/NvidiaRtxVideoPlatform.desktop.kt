package com.nuvio.app.features.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

internal actual object NvidiaRtxVideoPlatform {
    private fun onWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    actual suspend fun inspect(): NvidiaRtxVideoEnvironment = withContext(Dispatchers.IO) {
        if (!onWindows()) return@withContext NvidiaRtxVideoEnvironment()

        val windowsAdapters = gpuNamesFromCommand(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-CimInstance -ClassName Win32_VideoController -ErrorAction Stop).Name",
        )
        // Windows may hide a discrete NVIDIA adapter behind a hybrid display adapter.
        val nvidiaAdapters = if (windowsAdapters?.any(::isNvidiaRtx) == true) {
            emptyList()
        } else {
            gpuNamesFromCommand("nvidia-smi.exe", "--query-gpu=name", "--format=csv,noheader")
                .orEmpty()
        }
        val detected = detectNvidiaRtxGpu(
            (windowsAdapters.orEmpty() + nvidiaAdapters).takeIf { it.isNotEmpty() },
        )
        NvidiaRtxVideoEnvironment(
            gpuSupport = detected.first,
            gpuName = detected.second,
            nvidiaAppInstalled = installedNvidiaApp() != null,
        )
    }

    actual fun openNvidiaApp(): Boolean {
        if (!onWindows()) return false
        val executable = installedNvidiaApp() ?: return false
        return runCatching { ProcessBuilder(executable.absolutePath).start() }.isSuccess
    }

    actual fun openNvidiaAppDownload(): Boolean = runCatching {
        Desktop.getDesktop().browse(URI("https://www.nvidia.com/en-us/software/nvidia-app/"))
    }.isSuccess

    private fun isNvidiaRtx(name: String): Boolean =
        detectNvidiaRtxGpu(listOf(name)).first == NvidiaRtxGpuSupport.DETECTED

    private fun installedNvidiaApp(): File? =
        listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .map { File(it, "NVIDIA Corporation/NVIDIA app/CEF/NVIDIA App.exe") }
            .firstOrNull(File::isFile)

    private fun gpuNamesFromCommand(vararg command: String): List<String>? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                null
            } else {
                process.inputStream.bufferedReader().use { input ->
                    input.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrNull()
}
