package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_download_failed
import nuvio.composeapp.generated.resources.updates_download_failed_http
import nuvio.composeapp.generated.resources.updates_downloaded_file_missing
import nuvio.composeapp.generated.resources.updates_empty_download_body
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val desktopUpdaterPreferencesName = "nuvio_updater"
private const val ignoredTagKey = "ignored_release_tag"
private const val updateChannelKey = "update_channel"

private val desktopUpdaterHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

actual object AppUpdaterPlatform {
    private val currentOs: DesktopUpdaterOs = DesktopUpdaterOs.current()
    private val store = DesktopStorage.store(desktopUpdaterPreferencesName)
    actual val isDebugBuild: Boolean = false
    actual val hasSingleUpdateChannel: Boolean = true

    // Linux ships four package formats and the running app is the only place
    // that can tell which one it was installed from, so the format is resolved
    // once and then drives both the asset choice and the install command.
    private val linuxInstallMethod: LinuxInstallMethod by lazy {
        if (currentOs == DesktopUpdaterOs.LINUX) detectLinuxInstallMethod() else LinuxInstallMethod.UNKNOWN
    }

    // A Flatpak cannot install anything for itself: the sandbox has no write
    // access to /app and the manifest grants no talk-name for the host Flatpak
    // service, so the update belongs to the user's store, not to this dialog.
    actual val isSupported: Boolean
        get() = currentOs != DesktopUpdaterOs.UNKNOWN && linuxInstallMethod != LinuxInstallMethod.FLATPAK

    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = "Beelzebub2",
        repo = "starlit",
        channelBranch = null,
        includePrereleases = true,
        userAgent = "NuvioNative",
        privateRepository = true,
    )

    actual val assetSelector: AppUpdateAssetSelector
        get() = currentOs.assetSelector(linuxInstallMethod)

    actual val currentVersionName: String = AppVersionConfig.DESKTOP_VERSION_NAME

    actual suspend fun releaseRequestHeaders(): Map<String, String> = withContext(Dispatchers.IO) {
        mapOf("Authorization" to "Bearer ${PrivateReleaseAuth.token()}")
    }

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun getIgnoredTag(): String? = store.getString(ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        store.putString(ignoredTagKey, tag)
    }

    actual fun getUpdateChannel(): String? = store.getString(updateChannelKey)

    actual fun setUpdateChannel(channel: String) {
        store.putString(updateChannelKey, channel)
    }

    actual fun deleteDownloadedUpdate(path: String) {
        File(path).delete()
    }

    // Local cache, not the roaming profile: installers are large and only needed until setup runs.
    private val installerFolder by lazy {
        UpdateInstallerFolder(
            current = DesktopStorage.cacheDir.resolve("updates").toFile(),
            legacy = listOf(DesktopStorage.rootDir.resolve("updates").toFile()),
        )
    }

    /**
     * Removes installers left by earlier updates. Setup may still be open (it can launch the
     * updated app), keeping its own file locked, so locked files are retried a little later.
     */
    suspend fun removeLeftoverUpdateInstallers() = withContext(Dispatchers.IO) {
        for (retryDelayMs in listOf(60_000L, 300_000L)) {
            if (installerFolder.removeLeftovers()) return@withContext
            delay(retryDelayMs)
        }
        installerFolder.removeLeftovers()
    }

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        expectedSha256: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = installerFolder.claimForDownload()
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(updatesDir, safeName)
            val tempFile = File(updatesDir, "$safeName.part")

            require(expectedSha256 != null && expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                "This private update has no SHA-256 digest. Publish it through the Windows release workflow."
            }
            val apiUri = URI(assetUrl)
            require(isPrivateReleaseAssetUri(apiUri)) { "Unexpected private update download address" }
            var downloadUri = apiUri
            var response: HttpResponse<java.io.InputStream>? = null
            repeat(5) {
                if (response != null) return@repeat
                currentCoroutineContext().ensureActive()
                val builder = HttpRequest.newBuilder(downloadUri).GET().timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "NuvioNative")
                if (downloadUri == apiUri) {
                    builder.header("Accept", "application/octet-stream")
                        .header("Authorization", "Bearer ${PrivateReleaseAuth.token()}")
                }
                val next = desktopUpdaterHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                if (next.statusCode() in listOf(301, 302, 303, 307, 308)) {
                    next.body().close()
                    val location = next.headers().firstValue("Location").orElseThrow()
                    downloadUri = downloadUri.resolve(location)
                    require(downloadUri.scheme == "https" && downloadUri.userInfo == null) { "Unsafe update redirect" }
                } else response = next
            }
            val completedResponse = response ?: error("Too many update redirects")
            val digest = MessageDigest.getInstance("SHA-256")
            val responseBody = completedResponse
            if (responseBody.statusCode() !in 200..299) {
                responseBody.body().close()
                error(runBlocking { getString(Res.string.updates_download_failed_http, responseBody.statusCode()) })
            }

            val totalBytes = responseBody.headers().firstValue("Content-Length").orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            var downloadedBytes = 0L
            try {
                responseBody.body()?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            onProgress(downloadedBytes, totalBytes)
                        }
                        output.flush()
                    }
                } ?: error(runBlocking { getString(Res.string.updates_empty_download_body) })

                if (totalBytes != null && downloadedBytes != totalBytes) {
                    error(runBlocking { getString(Res.string.updates_download_failed) })
                }
                val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(downloadedBytes > 0 && actualDigest.equals(expectedSha256, ignoreCase = true)) {
                    "Update checksum verification failed. The installer was discarded."
                }

                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                destination.absolutePath
            } catch (t: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                throw t
            }
        }
    }

    actual fun canInstallDownloadedUpdate(): Boolean = true

    actual fun openInstallPermissionSettings() = Unit

    actual fun installDownloadedUpdate(path: String): Result<Unit> = runCatching {
        val updateFile = File(path)
        check(updateFile.exists()) { runBlocking { getString(Res.string.updates_downloaded_file_missing) } }

        launchInstaller(updateFile)
        scheduleAppExit()
    }

    private fun launchInstaller(updateFile: File) {
        val command = when (currentOs) {
            DesktopUpdaterOs.WINDOWS -> windowsInstallerCommand(updateFile)
            DesktopUpdaterOs.MACOS -> listOf("open", updateFile.absolutePath)
            DesktopUpdaterOs.LINUX -> linuxInstallerCommand(
                method = linuxInstallMethod,
                updateFile = updateFile,
                appImagePath = System.getenv(appImageEnvName),
                currentPid = ProcessHandle.current().pid(),
            )
            DesktopUpdaterOs.UNKNOWN -> error("Desktop updates are not supported on this operating system.")
        }
        // Not the app's own working directory: that is the install folder, which the
        // Windows installer has to move aside.
        ProcessBuilder(command).directory(updateFile.parentFile).start()
    }

    private fun scheduleAppExit() {
        thread(name = "nuvio-updater-exit", isDaemon = true) {
            Thread.sleep(500)
            exitProcess(0)
        }
    }
}

/**
 * The folder update installers are downloaded into. A downloaded installer is only remembered
 * in memory, so whatever is here when the app starts belongs to an update that already ran.
 */
internal class UpdateInstallerFolder(
    private val current: File,
    private val legacy: List<File> = emptyList(),
) {
    private var claimed = false

    /** Empties the folder for a new download; later leftover cleanup must not touch it. */
    @Synchronized
    fun claimForDownload(): File {
        claimed = true
        clear(current)
        check(current.isDirectory || current.mkdirs()) { "Cannot create the update download folder" }
        return current
    }

    /** Returns false while a file is still locked, for example by setup that is still open. */
    @Synchronized
    fun removeLeftovers(): Boolean {
        if (claimed) return true
        val cleared = (legacy + current).map(::clear).all { it }
        legacy.forEach { it.delete() } // only succeeds once empty
        return cleared
    }

    private fun clear(directory: File): Boolean {
        if (!directory.isDirectory) return true
        directory.listFiles()?.forEach { it.deleteRecursively() }
        return directory.listFiles().isNullOrEmpty()
    }
}

private enum class DesktopUpdaterOs {
    WINDOWS,
    MACOS,
    LINUX,
    UNKNOWN;

    fun assetSelector(linuxInstallMethod: LinuxInstallMethod): AppUpdateAssetSelector {
        val archFragments = desktopArchitectureFragments()
        return when (this) {
            WINDOWS -> AppUpdateAssetSelector(
                fileExtensions = listOf(".msi", ".exe"),
                preferredNameFragments = archFragments + listOf("windows", "win"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            MACOS -> AppUpdateAssetSelector(
                fileExtensions = listOf(".dmg", ".pkg"),
                preferredNameFragments = archFragments + listOf("macos", "mac", "darwin"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            LINUX -> AppUpdateAssetSelector(
                fileExtensions = linuxUpdateFileExtensions(linuxInstallMethod),
                preferredNameFragments = archFragments + listOf("linux"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            UNKNOWN -> AppUpdateAssetSelector(fileExtensions = emptyList())
        }
    }

    companion object {
        fun current(): DesktopUpdaterOs {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return when {
                osName.contains("win") -> WINDOWS
                osName.contains("mac") -> MACOS
                osName.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

private fun desktopArchitectureFragments(): List<String> {
    val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    return when {
        arch == "aarch64" || arch == "arm64" -> listOf("arm64", "aarch64")
        arch == "x86" || arch == "i386" || arch == "i686" -> listOf("x86", "i386", "i686")
        arch.contains("64") -> listOf("x64", "x86_64", "amd64")
        else -> emptyList()
    }
}

internal fun windowsInstallerCommand(updateFile: File): List<String> {
    if (!updateFile.extension.equals("msi", ignoreCase = true)) {
        return listOf(updateFile.absolutePath)
    }

    return listOf("msiexec", "/i", updateFile.absolutePath)
}

internal enum class LinuxInstallMethod {
    APP_IMAGE,
    FLATPAK,
    RPM,
    DEB,
    UNKNOWN,
}

internal const val appImageEnvName = "APPIMAGE"
private const val flatpakIdEnvName = "FLATPAK_ID"
private const val flatpakInfoPath = "/.flatpak-info"
private const val jpackageAppPathProperty = "jpackage.app-path"
private const val javaExecutableName = "java"
private const val packageQueryTimeoutSeconds = 3L
private const val appImageExitWaitTicks = 100

// A release carries every Linux format at once, so the extension list has to be
// the one the running install can actually consume. An unresolved install keeps
// the previous list, leaving source and tarball builds no worse off than before.
internal fun linuxUpdateFileExtensions(method: LinuxInstallMethod): List<String> = when (method) {
    LinuxInstallMethod.APP_IMAGE -> listOf(".AppImage")
    LinuxInstallMethod.RPM -> listOf(".rpm")
    LinuxInstallMethod.DEB -> listOf(".deb")
    LinuxInstallMethod.FLATPAK -> emptyList()
    LinuxInstallMethod.UNKNOWN -> listOf(".deb", ".AppImage")
}

internal fun resolveLinuxInstallMethod(
    appImagePath: String?,
    appImageExists: Boolean,
    flatpakId: String?,
    flatpakInfoExists: Boolean,
    launcherPath: String?,
    isOwnedByRpm: (String) -> Boolean,
    isOwnedByDpkg: (String) -> Boolean,
): LinuxInstallMethod {
    if (!appImagePath.isNullOrBlank() && appImageExists) return LinuxInstallMethod.APP_IMAGE
    if (!flatpakId.isNullOrBlank() || flatpakInfoExists) return LinuxInstallMethod.FLATPAK

    val path = launcherPath?.takeIf { it.isNotBlank() } ?: return LinuxInstallMethod.UNKNOWN
    if (isOwnedByRpm(path)) return LinuxInstallMethod.RPM
    if (isOwnedByDpkg(path)) return LinuxInstallMethod.DEB
    return LinuxInstallMethod.UNKNOWN
}

private fun detectLinuxInstallMethod(): LinuxInstallMethod {
    val appImagePath = System.getenv(appImageEnvName)
    return resolveLinuxInstallMethod(
        appImagePath = appImagePath,
        appImageExists = !appImagePath.isNullOrBlank() && File(appImagePath).exists(),
        flatpakId = System.getenv(flatpakIdEnvName),
        flatpakInfoExists = File(flatpakInfoPath).exists(),
        launcherPath = linuxLauncherPath(),
        isOwnedByRpm = { path -> packageQuerySucceeds(listOf("rpm", "-qf", path)) },
        isOwnedByDpkg = { path -> packageQuerySucceeds(listOf("dpkg", "-S", path)) },
    )
}

private fun linuxLauncherPath(): String? = linuxLauncherPathFrom(
    jpackageAppPath = System.getProperty(jpackageAppPathProperty),
    processCommand = ProcessHandle.current().info().command().orElse(null),
)

// jpackage records the launcher it started, which is the only path that belongs
// to this app's package. Without it the process command is the JVM itself, and
// the JVM belongs to the distribution's own java package -- asking rpm or dpkg
// who owns that would report a packaged install that is not Nuvio, so a source
// or tarball run is left unresolved instead.
internal fun linuxLauncherPathFrom(
    jpackageAppPath: String?,
    processCommand: String?,
): String? {
    jpackageAppPath?.takeIf { it.isNotBlank() }?.let { return it }

    return processCommand?.takeIf { it.isNotBlank() && File(it).name != javaExecutableName }
}

private fun packageQuerySucceeds(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(packageQueryTimeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching false
    }
    process.exitValue() == 0
}.getOrDefault(false)

internal fun linuxInstallerCommand(
    method: LinuxInstallMethod,
    updateFile: File,
    appImagePath: String?,
    currentPid: Long,
): List<String> {
    if (method == LinuxInstallMethod.APP_IMAGE && !appImagePath.isNullOrBlank()) {
        return listOf("sh", "-c", appImageReplaceScript(updateFile.absolutePath, appImagePath, currentPid))
    }

    return listOf("xdg-open", updateFile.absolutePath)
}

// An AppImage has no installer: updating one means replacing the file that is
// running. The copy waits for this process to exit first, both because the
// running image is still mounted from that file and because a half-written one
// would leave nothing to start. When the image sits somewhere this user cannot
// write, the download is handed to the desktop rather than failing in silence.
internal fun appImageReplaceScript(
    downloadedPath: String,
    appImagePath: String,
    currentPid: Long,
): String {
    val downloaded = singleQuote(downloadedPath)
    val target = singleQuote(appImagePath)
    return buildString {
        append("i=0; ")
        append("while [ \$i -lt $appImageExitWaitTicks ] && kill -0 $currentPid 2>/dev/null; do ")
        append("sleep 0.1; i=\$((i+1)); ")
        append("done; ")
        append("if cp -f -- $downloaded $target; then ")
        append("chmod +x -- $target; rm -f -- $downloaded; exec $target; ")
        append("else xdg-open $downloaded; fi")
    }
}

private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
