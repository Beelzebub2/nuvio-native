package com.nuvio.app.core.windows

import com.nuvio.app.features.remote.allowPhoneRemoteThroughWindowsFirewall
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.KeyboardFocusManager
import java.io.File
import java.util.Locale

internal const val ELEVATED_TASK_ARGUMENT = "--starlit-elevated-task"
internal const val PHONE_REMOTE_FIREWALL_TASK = "phone-remote-firewall"

private const val EXIT_OK = 0
private const val EXIT_FAILED = 2
private const val EXIT_UNKNOWN_TASK = 64

/**
 * Runs a fixed administrator task when Starlit was relaunched elevated for it.
 * Returns the process exit code, or null for a normal app start. Only named tasks
 * are accepted; nothing from the command line is used as a path or rule value.
 */
internal fun runElevatedHelperTask(args: Array<String>): Int? {
    if (args.firstOrNull() != ELEVATED_TASK_ARGUMENT) return null
    if (args.size != 2 || !System.getProperty("os.name", "").startsWith("Windows")) return EXIT_UNKNOWN_TASK
    return when (args[1]) {
        PHONE_REMOTE_FIREWALL_TASK ->
            if (runCatching { allowPhoneRemoteThroughWindowsFirewall() }.isSuccess) EXIT_OK else EXIT_FAILED
        else -> EXIT_UNKNOWN_TASK
    }
}

/** The command that starts this same program again with [arguments]. */
internal data class SelfCommand(val file: String, val parameters: String)

/**
 * The packaged launcher (Starlit.exe) is started directly. A development JVM
 * (java.exe) is started again with its own class path and main class.
 */
internal fun selfCommand(
    executable: String?,
    classPath: String?,
    javaCommand: String?,
    arguments: List<String>,
): SelfCommand? {
    if (executable.isNullOrBlank()) return null
    if (arguments.any { it.isEmpty() || '"' in it }) return null
    val name = File(executable).name.lowercase(Locale.ROOT)
    if (name != "java.exe" && name != "javaw.exe") {
        return SelfCommand(executable, arguments.joinToString(" ", transform = ::quoteArgument))
    }
    val mainClass = javaCommand?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: return null
    if (classPath.isNullOrBlank() || '"' in classPath) return null
    val parameters = (listOf("-cp", classPath, mainClass) + arguments).joinToString(" ", transform = ::quoteArgument)
    // CreateProcess limits the whole command line to 32767 characters.
    return SelfCommand(executable, parameters).takeIf { parameters.length + executable.length < 32_000 }
}

/** Windows argv quoting; a trailing backslash would otherwise escape the closing quote. */
private fun quoteArgument(value: String): String {
    if (value.none { it.isWhitespace() || it == ';' }) return value
    val trailingBackslashes = value.length - value.trimEnd('\\').length
    return "\"" + value + "\\".repeat(trailingBackslashes) + "\""
}

/** Relaunches Starlit elevated (one UAC prompt naming Starlit) and waits for its result. */
internal object WindowsElevation {
    private const val SEE_MASK_NOCLOSEPROCESS = 0x40
    private const val SEE_MASK_NOASYNC = 0x100
    private const val SEE_MASK_FLAG_NO_UI = 0x400
    private const val SW_HIDE = 0
    private const val WAIT_OBJECT_0 = 0

    @Structure.FieldOrder(
        "cbSize", "fMask", "hwnd", "lpVerb", "lpFile", "lpParameters", "lpDirectory", "nShow",
        "hInstApp", "lpIDList", "lpClass", "hkeyClass", "dwHotKey", "hIconOrMonitor", "hProcess",
    )
    class ShellExecuteInfo : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var fMask: Int = 0
        @JvmField var hwnd: Pointer? = null
        @JvmField var lpVerb: WString? = null
        @JvmField var lpFile: WString? = null
        @JvmField var lpParameters: WString? = null
        @JvmField var lpDirectory: WString? = null
        @JvmField var nShow: Int = 0
        @JvmField var hInstApp: Pointer? = null
        @JvmField var lpIDList: Pointer? = null
        @JvmField var lpClass: WString? = null
        @JvmField var hkeyClass: Pointer? = null
        @JvmField var dwHotKey: Int = 0
        @JvmField var hIconOrMonitor: Pointer? = null
        @JvmField var hProcess: Pointer? = null
    }

    private interface Shell32 : StdCallLibrary {
        fun ShellExecuteExW(info: ShellExecuteInfo): Boolean
    }

    private interface Kernel32 : StdCallLibrary {
        fun WaitForSingleObject(handle: Pointer, milliseconds: Int): Int
        fun GetExitCodeProcess(handle: Pointer, exitCode: IntByReference): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val shell32 by lazy { loadSystemLibrary("shell32", Shell32::class.java) }
    private val kernel32 by lazy { loadSystemLibrary("kernel32", Kernel32::class.java) }

    /** The focused Starlit window, so the UAC prompt opens in front of it. */
    fun activeWindowHandle(): Pointer? = runCatching {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?.takeIf { it.isShowing }
            ?.let(Native::getComponentPointer)
    }.getOrNull()

    /** True only when the user approved the prompt and the elevated task exited cleanly. */
    fun runSelf(arguments: List<String>, timeoutSeconds: Long, owner: Pointer?): Boolean {
        val command = selfCommand(
            executable = ProcessHandle.current().info().command().orElse(null),
            classPath = System.getProperty("java.class.path"),
            javaCommand = System.getProperty("sun.java.command"),
            arguments = arguments,
        ) ?: return false
        val info = ShellExecuteInfo().apply {
            fMask = SEE_MASK_NOCLOSEPROCESS or SEE_MASK_NOASYNC or SEE_MASK_FLAG_NO_UI
            hwnd = owner
            lpVerb = WString("runas")
            lpFile = WString(command.file)
            lpParameters = WString(command.parameters)
            nShow = SW_HIDE
            cbSize = size()
        }
        // False includes the user declining the administrator prompt.
        if (!shell32.ShellExecuteExW(info)) return false
        info.read()
        val process = info.hProcess ?: return false
        try {
            val timeout = (timeoutSeconds * 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            if (kernel32.WaitForSingleObject(process, timeout) != WAIT_OBJECT_0) return false
            val exitCode = IntByReference(-1)
            return kernel32.GetExitCodeProcess(process, exitCode) && exitCode.value == EXIT_OK
        } finally {
            kernel32.CloseHandle(process)
        }
    }
}
