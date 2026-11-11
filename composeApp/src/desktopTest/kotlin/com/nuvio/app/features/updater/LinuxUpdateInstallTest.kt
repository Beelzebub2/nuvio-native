package com.nuvio.app.features.updater

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxUpdateInstallTest {

    // The asset names of a real release, in the order the GitHub API returns them.
    private val releaseAssets = listOf(
        asset("Nuvio-Linux-x86_64-0.1.22-alpha.AppImage"),
        asset("Nuvio-Linux-x86_64-0.1.22-alpha.AppImage.zsync"),
        asset("Nuvio-Linux-x86_64-0.1.22-alpha.deb"),
        asset("Nuvio-Linux-x86_64-0.1.22-alpha.flatpak"),
        asset("Nuvio-Linux-x86_64-0.1.22-alpha.rpm"),
        asset("Nuvio-macOS-arm64-0.1.22-alpha.dmg"),
        asset("Nuvio-macOS-x86_64-0.1.22-alpha.dmg"),
        asset("Nuvio-Windows-x64-0.1.22-alpha.msi"),
    )

    @Test
    fun `rpm install is offered the rpm asset`() {
        assertEquals(
            "Nuvio-Linux-x86_64-0.1.22-alpha.rpm",
            selectFor(LinuxInstallMethod.RPM)?.name,
        )
    }

    @Test
    fun `deb install is offered the deb asset`() {
        assertEquals(
            "Nuvio-Linux-x86_64-0.1.22-alpha.deb",
            selectFor(LinuxInstallMethod.DEB)?.name,
        )
    }

    @Test
    fun `app image install is offered the image itself and never its zsync file`() {
        assertEquals(
            "Nuvio-Linux-x86_64-0.1.22-alpha.AppImage",
            selectFor(LinuxInstallMethod.APP_IMAGE)?.name,
        )
    }

    @Test
    fun `app image env wins over every other marker`() {
        val method = resolveLinuxInstallMethod(
            appImagePath = "/home/user/Apps/Nuvio.AppImage",
            appImageExists = true,
            flatpakId = "com.nuvio.media.desktop",
            flatpakInfoExists = true,
            launcherPath = "/opt/Nuvio/bin/Nuvio",
            isOwnedByRpm = { true },
            isOwnedByDpkg = { true },
        )

        assertEquals(LinuxInstallMethod.APP_IMAGE, method)
    }

    @Test
    fun `a stale app image path does not claim the install`() {
        val method = resolveLinuxInstallMethod(
            appImagePath = "/home/user/Apps/deleted.AppImage",
            appImageExists = false,
            flatpakId = null,
            flatpakInfoExists = false,
            launcherPath = "/opt/Nuvio/bin/Nuvio",
            isOwnedByRpm = { true },
            isOwnedByDpkg = { false },
        )

        assertEquals(LinuxInstallMethod.RPM, method)
    }

    @Test
    fun `flatpak is recognised from its sandbox marker file alone`() {
        val method = resolveLinuxInstallMethod(
            appImagePath = null,
            appImageExists = false,
            flatpakId = null,
            flatpakInfoExists = true,
            launcherPath = "/app/opt/Nuvio/bin/Nuvio",
            isOwnedByRpm = { false },
            isOwnedByDpkg = { false },
        )

        assertEquals(LinuxInstallMethod.FLATPAK, method)
    }

    @Test
    fun `dpkg ownership is only consulted when rpm does not claim the launcher`() {
        var rpmQueried = false
        var dpkgQueried = false

        val method = resolveLinuxInstallMethod(
            appImagePath = null,
            appImageExists = false,
            flatpakId = null,
            flatpakInfoExists = false,
            launcherPath = "/opt/Nuvio/bin/Nuvio",
            isOwnedByRpm = { rpmQueried = true; false },
            isOwnedByDpkg = { dpkgQueried = true; true },
        )

        assertEquals(LinuxInstallMethod.DEB, method)
        assertTrue(rpmQueried)
        assertTrue(dpkgQueried)
    }

    @Test
    fun `an unknown launcher is never handed to a package manager`() {
        var queried = false

        val method = resolveLinuxInstallMethod(
            appImagePath = null,
            appImageExists = false,
            flatpakId = null,
            flatpakInfoExists = false,
            launcherPath = null,
            isOwnedByRpm = { queried = true; true },
            isOwnedByDpkg = { queried = true; true },
        )

        assertEquals(LinuxInstallMethod.UNKNOWN, method)
        assertFalse(queried)
    }

    @Test
    fun `the jpackage launcher is preferred over the process command`() {
        assertEquals(
            "/opt/nuvio/bin/Nuvio",
            linuxLauncherPathFrom(
                jpackageAppPath = "/opt/nuvio/bin/Nuvio",
                processCommand = "/usr/lib/jvm/java-17-openjdk/bin/java",
            ),
        )
    }

    // rpm -qf answers for the distribution's own java package, so trusting the
    // JVM path would report a source run as a packaged install.
    @Test
    fun `a jvm process command is not treated as the installed launcher`() {
        assertNull(
            linuxLauncherPathFrom(
                jpackageAppPath = null,
                processCommand = "/usr/lib/jvm/java-17-openjdk/bin/java",
            ),
        )
    }

    @Test
    fun `a real launcher process command is still used when jpackage is silent`() {
        assertEquals(
            "/opt/nuvio/bin/Nuvio",
            linuxLauncherPathFrom(
                jpackageAppPath = "   ",
                processCommand = "/opt/nuvio/bin/Nuvio",
            ),
        )
    }

    @Test
    fun `an unresolved install keeps the previous asset list`() {
        assertEquals(
            listOf(".deb", ".AppImage"),
            linuxUpdateFileExtensions(LinuxInstallMethod.UNKNOWN),
        )
    }

    @Test
    fun `a package install is handed to the desktop package handler`() {
        val command = linuxInstallerCommand(
            method = LinuxInstallMethod.RPM,
            updateFile = File("/tmp/updates/Nuvio.rpm"),
            appImagePath = null,
            currentPid = 4242L,
        )

        assertEquals(listOf("xdg-open", File("/tmp/updates/Nuvio.rpm").absolutePath), command)
    }

    @Test
    fun `an app image replaces itself once this process is gone`() {
        val command = linuxInstallerCommand(
            method = LinuxInstallMethod.APP_IMAGE,
            updateFile = File("/tmp/updates/Nuvio.AppImage"),
            appImagePath = "/home/user/Apps/Nuvio.AppImage",
            currentPid = 4242L,
        )

        assertEquals("sh", command[0])
        assertEquals("-c", command[1])
        val script = command[2]
        assertTrue(script.contains("kill -0 4242"))
        assertTrue(script.contains("stage=\$(mktemp -- \"\${target}.update.XXXXXXXXXX\")"))
        assertTrue(script.contains("backup=\$(mktemp -- \"\${target}.backup.XXXXXXXXXX\")"))
        assertTrue(script.contains("cp -- \"\$downloaded\" \"\$stage\""))
        assertTrue(script.contains("chmod u+x -- \"\$stage\""))
        assertTrue(script.contains("mv -f -- \"\$stage\" \"\$target\""))
        assertTrue(script.contains("cp -p -- \"\$backup\" \"\$target\""))
        assertTrue(script.contains("exec \"\$target\""))
        assertTrue(script.contains("xdg-open \"\$downloaded\""))
        assertFalse(script.contains("cp -f --"))
    }

    @Test
    fun `an app image with no known path falls back to the desktop handler`() {
        val command = linuxInstallerCommand(
            method = LinuxInstallMethod.APP_IMAGE,
            updateFile = File("/tmp/updates/Nuvio.AppImage"),
            appImagePath = null,
            currentPid = 4242L,
        )

        assertEquals(listOf("xdg-open", File("/tmp/updates/Nuvio.AppImage").absolutePath), command)
    }

    @Test
    fun `a quote in the app image path cannot break out of the script`() {
        val script = appImageReplaceScript(
            downloadedPath = "/tmp/updates/Nuvio.AppImage",
            appImagePath = "/home/user/Ar'jun/Nuvio.AppImage",
            currentPid = 7L,
        )

        assertTrue(script.contains("'/home/user/Ar'\\''jun/Nuvio.AppImage'"))
        assertFalse(script.contains("Ar'jun"))
    }

    @Test
    fun `app image update swaps a staged executable and relaunches it`() = withLinuxSandbox { root ->
        val target = File(root, "Apps with ' quote/Image; touch INJECTED.AppImage")
        target.parentFile.mkdirs()
        target.writeExecutable("#!/bin/sh\nprintf old > \"\$RESTART_LOG\"\n")
        val downloaded = File(root, "updates/New ' AppImage")
        downloaded.parentFile.mkdirs()
        val updatedContents = "#!/bin/sh\nprintf updated > \"\$RESTART_LOG\"\n"
        downloaded.writeExecutable(updatedContents)

        val result = runShellUpdater(root, downloaded, target)

        assertEquals(0, result.exitCode, result.output)
        assertEquals(updatedContents, target.readText())
        assertTrue(target.canExecute())
        assertEquals("updated", File(root, "restarted.log").readText())
        assertFalse(downloaded.exists())
        assertFalse(File(root, "INJECTED.AppImage").exists())
        assertFalse(File(root, "fallback.log").exists())
        assertNoStagedFiles(target)
    }

    @Test
    fun `partial staged copy leaves original app image intact`() = withLinuxSandbox { root ->
        val target = File(root, "Original.AppImage")
        target.writeExecutable("original executable")
        val downloaded = File(root, "updated.AppImage")
        downloaded.writeText("new executable")
        File(root, "commands/cp").writeExecutable(
            "#!/bin/sh\n" +
                "if [ \"\$1\" = '--' ]; then printf partial > \"\$3\"; kill -KILL \$\$; fi\n" +
                "exec /bin/cp \"\$@\"\n",
        )

        val result = runShellUpdater(root, downloaded, target)

        assertEquals(0, result.exitCode, result.output)
        assertEquals("original executable", target.readText())
        assertTrue(target.canExecute())
        assertTrue(downloaded.exists())
        assertEquals(downloaded.absolutePath, File(root, "fallback.log").readText())
        assertNoStagedFiles(target)
    }

    @Test
    fun `failed rename restores a partially damaged target from backup`() = withLinuxSandbox { root ->
        val target = File(root, "Original.AppImage")
        target.writeExecutable("original executable")
        val downloaded = File(root, "updated.AppImage")
        downloaded.writeText("new executable")
        // Simulate a nonconforming rename operation that has damaged its target
        // before reporting failure. The rollback must also work without mv.
        File(root, "commands/mv").writeExecutable(
            "#!/bin/sh\nprintf damaged > \"\$4\"\nexit 73\n",
        )

        val result = runShellUpdater(root, downloaded, target)

        assertEquals(0, result.exitCode, result.output)
        assertEquals("original executable", target.readText())
        assertTrue(target.canExecute())
        assertTrue(downloaded.exists())
        assertEquals(downloaded.absolutePath, File(root, "fallback.log").readText())
        assertNoStagedFiles(target)
    }

    private inline fun withLinuxSandbox(block: (File) -> Unit) {
        // The generated shell script is still checked on Windows; filesystem
        // replacement behavior needs the Linux shell and GNU utilities.
        if (!System.getProperty("os.name").contains("linux", ignoreCase = true)) return

        val root = Files.createTempDirectory("appimage-install-test-").toFile()
        try {
            val commands = File(root, "commands")
            commands.mkdirs()
            File(commands, "xdg-open").writeExecutable(
                "#!/bin/sh\nprintf '%s' \"\$1\" > \"\$FALLBACK_LOG\"\n",
            )
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runShellUpdater(root: File, downloaded: File, target: File): ShellResult {
        val process = ProcessBuilder(
            "sh", "-c", appImageReplaceScript(downloaded.absolutePath, target.absolutePath, 999_999_999L),
        ).directory(root).redirectErrorStream(true)
        process.environment().apply {
            put("PATH", File(root, "commands").absolutePath + File.pathSeparator + get("PATH").orEmpty())
            put("FALLBACK_LOG", File(root, "fallback.log").absolutePath)
            put("RESTART_LOG", File(root, "restarted.log").absolutePath)
        }

        val running = process.start()
        if (!running.waitFor(10, TimeUnit.SECONDS)) {
            running.destroyForcibly()
            error("AppImage updater shell command timed out")
        }
        return ShellResult(running.exitValue(), running.inputStream.bufferedReader().use { it.readText() })
    }

    private fun File.writeExecutable(contents: String) {
        parentFile?.mkdirs()
        writeText(contents)
        check(setExecutable(true)) { "Unable to create executable test fixture: $name" }
    }

    private fun assertNoStagedFiles(target: File) {
        val prefix = target.name
        assertTrue(
            target.parentFile.listFiles().orEmpty().none { file ->
                file.name.startsWith("$prefix.update.") || file.name.startsWith("$prefix.backup.")
            },
            "Temporary AppImage replacement and backup files must be removed",
        )
    }

    private data class ShellResult(val exitCode: Int, val output: String)

    private fun selectFor(method: LinuxInstallMethod): AppUpdateAssetCandidate? =
        selectBestUpdateAsset(
            assets = releaseAssets,
            selector = AppUpdateAssetSelector(
                fileExtensions = linuxUpdateFileExtensions(method),
                preferredNameFragments = listOf("x64", "x86_64", "amd64", "linux"),
                fallbackNameFragments = listOf("universal", "all"),
            ),
        )

    private fun asset(name: String): AppUpdateAssetCandidate =
        AppUpdateAssetCandidate(
            name = name,
            downloadUrl = "https://example.test/$name",
        )
}
