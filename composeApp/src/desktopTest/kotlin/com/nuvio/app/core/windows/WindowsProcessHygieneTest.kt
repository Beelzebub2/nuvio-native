package com.nuvio.app.core.windows

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class WindowsProcessHygieneTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    /**
     * Antivirus engines (Windows Defender's Trojan:Win32/Bearfoos.A!ml) flagged Starlit
     * for starting hidden, encoded or elevated PowerShell. App code must use in-process
     * Windows APIs instead.
     */
    @Test
    fun desktopAppCodeNeverStartsPowerShellOrNetsh() {
        val sources = listOf("src/desktopMain/kotlin", "src/commonMain/kotlin").map(::File)
        assumeTrue(sources.all(File::isDirectory))
        val launchers = Regex("""(?i)"(powershell(\.exe)?|pwsh(\.exe)?|netsh(\.exe)?)"|-EncodedCommand|ExecutionPolicy""")
        val offenders = sources.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                file.readLines().any { line -> !line.trimStart().startsWith("//") && launchers.containsMatchIn(line) }
            }.map { it.relativeTo(root).path }.toList()
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun dpapiRoundTripsInProcess() {
        assumeTrue(windows)
        val secret = "fake-session-token-ü-日本".toByteArray(Charsets.UTF_8)
        val sealed = WindowsDpapi.protect(secret)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("fake-session-token"))
        assertTrue(WindowsDpapi.unprotect(sealed).contentEquals(secret))
        assertTrue(WindowsDpapi.unprotect(WindowsDpapi.protect(ByteArray(0))).isEmpty())
    }

    /** Sessions sealed by earlier Starlit builds (.NET ProtectedData) must still open. */
    @Test
    fun dpapiOpensValuesSealedByEarlierReleasesAndViceVersa() {
        assumeTrue(windows)
        val secret = "fake-legacy-token-for-test-only"
        val legacy = dotNetProtectedData("Protect", Base64.getEncoder().encodeToString(secret.toByteArray()))
        assertEquals(secret, String(WindowsDpapi.unprotect(Base64.getDecoder().decode(legacy)), Charsets.UTF_8))

        val sealed = Base64.getEncoder().encodeToString(WindowsDpapi.protect(secret.toByteArray()))
        assertEquals(secret, String(Base64.getDecoder().decode(dotNetProtectedData("Unprotect", sealed))))
    }

    /** The exact call earlier releases made; test-only, to prove the stored format is unchanged. */
    private fun dotNetProtectedData(operation: String, base64: String): String {
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$bytes=[Convert]::FromBase64String([Console]::In.ReadToEnd()); " +
            "[Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$operation(" +
            "\$bytes,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(base64.toByteArray(Charsets.US_ASCII)) }
        val output = process.inputStream.use { it.readBytes().toString(Charsets.US_ASCII).trim() }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0)
        return output
    }

    @Test
    fun packagedLauncherRelaunchesItselfWithOnlyTheTaskArguments() {
        val command = assertNotNull(selfCommand(
            executable = """C:\Users\me\AppData\Local\Starlit\Starlit.exe""",
            classPath = """C:\Users\me\AppData\Local\Starlit\app\starlit.jar""",
            javaCommand = "com.nuvio.app.MainKt",
            arguments = listOf(ELEVATED_TASK_ARGUMENT, PHONE_REMOTE_FIREWALL_TASK),
        ))
        assertEquals("""C:\Users\me\AppData\Local\Starlit\Starlit.exe""", command.file)
        assertEquals("--starlit-elevated-task phone-remote-firewall", command.parameters)
    }

    @Test
    fun developmentJvmRelaunchesWithItsClassPath() {
        val command = assertNotNull(selfCommand(
            executable = """C:\jdk\bin\java.exe""",
            classPath = """C:\My Projects\app\classes\;C:\lib\a.jar""",
            javaCommand = "com.nuvio.app.MainKt some-arg",
            arguments = listOf(ELEVATED_TASK_ARGUMENT, PHONE_REMOTE_FIREWALL_TASK),
        ))
        assertEquals(
            """-cp "C:\My Projects\app\classes\;C:\lib\a.jar" com.nuvio.app.MainKt --starlit-elevated-task phone-remote-firewall""",
            command.parameters,
        )
        val trailing = assertNotNull(selfCommand("""C:\jdk\bin\java.exe""", """C:\out dir\""", "Main", listOf("x")))
        // A trailing backslash is doubled so it doesn't escape the closing quote.
        assertEquals("""-cp "C:\out dir\\" Main x""", trailing.parameters)
        assertNull(selfCommand("""C:\jdk\bin\java.exe""", null, "Main", listOf("x")))
        assertNull(selfCommand("""C:\jdk\bin\java.exe""", "a.jar", null, listOf("x")))
        assertNull(selfCommand("""C:\jdk\bin\java.exe""", "x".repeat(40_000), "Main", listOf("x")))
        assertNull(selfCommand(null, null, null, listOf("x")))
        assertNull(selfCommand("Starlit.exe", null, null, listOf("bad\"quote")))
    }

    @Test
    fun onlyNamedElevatedTasksRun() {
        assertNull(runElevatedHelperTask(emptyArray()))
        assertNull(runElevatedHelperTask(arrayOf("starlit://open")))
        assertEquals(64, runElevatedHelperTask(arrayOf(ELEVATED_TASK_ARGUMENT)))
        assertEquals(64, runElevatedHelperTask(arrayOf(ELEVATED_TASK_ARGUMENT, "format-disk")))
        assertEquals(64, runElevatedHelperTask(arrayOf(ELEVATED_TASK_ARGUMENT, PHONE_REMOTE_FIREWALL_TASK, "C:\\evil.exe")))
    }
}
