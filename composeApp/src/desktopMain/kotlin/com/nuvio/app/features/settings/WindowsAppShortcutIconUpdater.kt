package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

internal object WindowsAppShortcutIconUpdater {
    fun updateAsync(icon: AppIconOption) {
        Thread({ update(icon) }, "Nuvio Windows app icon updater").apply { isDaemon = true }.start()
    }

    private fun update(icon: AppIconOption) {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        runCatching {
            val resource = "icons/app-icon-${icon.key}-transparent.ico"
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            val iconDirectory = Path.of(localAppData, "Nuvio", "icons")
            Files.createDirectories(iconDirectory)
            val iconFile = iconDirectory.resolve("app-icon-${icon.key}-transparent.ico")
            Thread.currentThread().contextClassLoader.getResourceAsStream(resource)?.use { input ->
                Files.copy(input, iconFile, StandardCopyOption.REPLACE_EXISTING)
            } ?: return@runCatching

            val shortcuts = shortcutPaths().filter(Files::isRegularFile).toList()
            val elevatedRoots = listOf(
                System.getenv("PUBLIC") ?: "C:/Users/Public",
                System.getenv("ProgramData") ?: "C:/ProgramData",
            ).map { Path.of(it).toAbsolutePath().normalize() }
            val elevated = shortcuts.filter { shortcut ->
                elevatedRoots.any { root -> shortcut.toAbsolutePath().normalize().startsWith(root) }
            }
            shortcuts.filterNot(elevated::contains).forEach { setShortcutIcon(it, iconFile) }
            if (elevated.isNotEmpty()) setShortcutIconsElevated(elevated, iconFile)
        }
    }

    private fun shortcutPaths(): Sequence<Path> = sequence {
        val home = Path.of(System.getProperty("user.home"))
        yield(home.resolve("Desktop/Nuvio.lnk"))
        yield(home.resolve("OneDrive/Desktop/Nuvio.lnk"))
        yield(home.resolve("AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Nuvio.lnk"))
        yield(home.resolve("AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Nuvio/Nuvio.lnk"))
        yield(home.resolve("AppData/Roaming/Microsoft/Internet Explorer/Quick Launch/User Pinned/TaskBar/Nuvio.lnk"))
        yield(Path.of(System.getenv("PUBLIC") ?: "C:/Users/Public", "Desktop", "Nuvio.lnk"))
        yield(Path.of(System.getenv("ProgramData") ?: "C:/ProgramData", "Microsoft", "Windows", "Start Menu", "Programs", "Nuvio", "Nuvio.lnk"))
    }

    private fun setShortcutIconsElevated(shortcuts: List<Path>, iconFile: Path) {
        runCatching {
            val dollar = '$'
            val iconPath = iconFile.toString().replace("'", "''")
            val paths = shortcuts.joinToString(",") { "'${it.toString().replace("'", "''")}'" }
            val script = listOf(
                "${dollar}icon = '${iconPath}'",
                "${dollar}shell = New-Object -ComObject WScript.Shell",
                "${dollar}shortcuts = @(${paths})",
                "${dollar}shortcuts | ForEach-Object {",
                "    ${dollar}shortcut = ${dollar}shell.CreateShortcut(${dollar}_)",
                "    ${dollar}shortcut.IconLocation = ${dollar}icon + ',0'",
                "    ${dollar}shortcut.Save()",
                "}",
            ).joinToString("\n")
            val scriptFile = Files.createTempFile("nuvio-icon-update-", ".ps1")
            Files.writeString(scriptFile, script)
            val escapedScriptPath = scriptFile.toString().replace("'", "''")
            val launcher = "Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -ArgumentList " +
                "@('-WindowStyle','Hidden','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','${escapedScriptPath}')"
            runPowerShell(ProcessBuilder("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", launcher).start(), 60)
            Files.deleteIfExists(scriptFile)
        }
    }

    private fun setShortcutIcon(shortcut: Path, iconFile: Path) {
        val shortcutArg = shortcut.toString().replace("'", "''")
        val iconArg = (iconFile.toString() + ",0").replace("'", "''")
        val dollar = '$'
        val script = listOf(
            "${dollar}shell = New-Object -ComObject WScript.Shell",
            "${dollar}shortcut = ${dollar}shell.CreateShortcut('${shortcutArg}')",
            "${dollar}shortcut.IconLocation = '${iconArg}'",
            "${dollar}shortcut.Save()",
        ).joinToString("\n")
        runCatching { runPowerShell(ProcessBuilder("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script).start()) }
    }

    private fun runPowerShell(process: Process, timeoutSeconds: Long = 3) {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}