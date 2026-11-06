package com.nuvio.app.features.settings

import com.nuvio.app.core.windows.WindowsCom
import com.nuvio.app.core.windows.checkHresult
import com.nuvio.app.core.windows.loadSystemLibrary
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Points Starlit's shortcuts at the chosen icon. Everything runs in-process through
 * the Windows shell APIs; no PowerShell is started.
 */
internal object WindowsAppShortcutIconUpdater {
    // CSIDL values, the same ones .NET's Environment.SpecialFolder used.
    private const val CSIDL_DESKTOP = 0x0000
    private const val CSIDL_PROGRAMS = 0x0002
    private const val CSIDL_COMMON_PROGRAMS = 0x0017
    private const val CSIDL_COMMON_DESKTOPDIRECTORY = 0x0019
    private const val CSIDL_APPDATA = 0x001A
    private const val CSIDL_LOCAL_APPDATA = 0x001C
    private const val MAX_PATH = 260

    private const val CLSID_SHELL_LINK = "00021401-0000-0000-C000-000000000046"
    private const val IID_SHELL_LINK_W = "000214F9-0000-0000-C000-000000000046"
    private const val IID_PERSIST_FILE = "0000010b-0000-0000-C000-000000000046"
    private const val SHELL_LINK_SET_ICON_LOCATION = 17
    private const val PERSIST_FILE_LOAD = 5
    private const val PERSIST_FILE_SAVE = 6
    private const val STGM_READ = 0

    private interface Shell32 : StdCallLibrary {
        fun SHGetFolderPathW(owner: Pointer?, folder: Int, token: Pointer?, flags: Int, path: Memory): Int
    }

    private val shell32 by lazy { loadSystemLibrary("shell32", Shell32::class.java) }

    fun updateAsync(icon: AppIconOption, onComplete: () -> Unit) {
        Thread({
            try {
                update(icon)
            } finally {
                onComplete()
            }
        }, "Nuvio Windows app icon updater").apply { isDaemon = false }.start()
    }

    private fun update(icon: AppIconOption) {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        runCatching {
            val resource = "icons/app-icon-${icon.key}-transparent.ico"
            val localAppData = knownFolder(CSIDL_LOCAL_APPDATA) ?: return@runCatching
            val iconDirectory = localAppData.resolve("Nuvio/icons")
            Files.createDirectories(iconDirectory)
            val iconFile = iconDirectory.resolve("app-icon-${icon.key}-transparent.ico")
            Thread.currentThread().contextClassLoader.getResourceAsStream(resource)?.use { input ->
                Files.copy(input, iconFile, StandardCopyOption.REPLACE_EXISTING)
            } ?: return@runCatching

            val shortcuts = shortcutPaths().filter(Files::isRegularFile).toList()
            // Shell link objects are apartment-threaded; this is a dedicated thread.
            WindowsCom.withApartment(singleThreaded = true) {
                // All-users shortcuts need administrator rights. Starlit's installers only
                // create per-user ones, so a shortcut that can't be written is skipped.
                shortcuts.forEach { shortcut -> runCatching { setShortcutIcon(shortcut, iconFile) } }
            }
        }
    }

    private fun shortcutPaths(): Sequence<Path> = sequence {
        val desktop = knownFolder(CSIDL_DESKTOP)
        val programs = knownFolder(CSIDL_PROGRAMS)
        val applicationData = knownFolder(CSIDL_APPDATA)
        val commonDesktop = knownFolder(CSIDL_COMMON_DESKTOPDIRECTORY)
        val commonPrograms = knownFolder(CSIDL_COMMON_PROGRAMS)

        for (name in listOf("Starlit", "Nuvio Native", "Nuvio")) {
            val shortcut = "$name.lnk"
            desktop?.let { yield(it.resolve(shortcut)) }
            programs?.let {
                yield(it.resolve(shortcut))
                yield(it.resolve("$name/$shortcut"))
            }
            applicationData?.let {
                yield(it.resolve("Microsoft/Internet Explorer/Quick Launch/User Pinned/TaskBar/$shortcut"))
            }
            commonDesktop?.let { yield(it.resolve(shortcut)) }
            commonPrograms?.let {
                yield(it.resolve("$name/$shortcut"))
                yield(it.resolve(shortcut))
            }
        }
    }

    private fun knownFolder(csidl: Int): Path? = runCatching {
        val buffer = Memory((MAX_PATH + 1) * 2L).apply { clear() }
        if (shell32.SHGetFolderPathW(null, csidl, null, 0, buffer) != 0) return@runCatching null
        buffer.getWideString(0).takeIf { it.isNotBlank() }?.let(Path::of)
    }.getOrNull()

    private fun setShortcutIcon(shortcut: Path, iconFile: Path) {
        WindowsCom.create(CLSID_SHELL_LINK, IID_SHELL_LINK_W).use { link ->
            val file = link.queryInterface(IID_PERSIST_FILE) ?: error("Shell link has no IPersistFile")
            file.use {
                val path = WString(shortcut.toString())
                checkHresult("IPersistFile.Load", file.call(PERSIST_FILE_LOAD, path, STGM_READ))
                checkHresult("IShellLinkW.SetIconLocation", link.call(SHELL_LINK_SET_ICON_LOCATION, WString(iconFile.toString()), 0))
                checkHresult("IPersistFile.Save", file.call(PERSIST_FILE_SAVE, path, 1))
            }
        }
    }
}
