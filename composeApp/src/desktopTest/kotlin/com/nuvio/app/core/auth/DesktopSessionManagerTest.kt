package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSessionManagerTest {
    private fun withSessionStore(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("starlit-desktop-session")
        try {
            test(directory.resolve(DesktopSessionManager.SESSION_STORE_FILE))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun manager(file: Path, server: String = "https://accounts.example.test") =
        DesktopSessionManager(server, DesktopStorage.Store(file))

    private fun session() = UserSession(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        expiresIn = 3600L,
        tokenType = "bearer",
        user = UserInfo(id = "test-user", aud = "authenticated", email = "test@example.test"),
    )

    @Test
    fun session_survives_restart_and_guest_workspace_cleanup() = withSessionStore { file ->
        runBlocking {
            val initial = manager(file)
            initial.saveSession(session())
            val previousWorkspace = file.parent.resolve("nuvio_library.properties")
            Files.writeString(previousWorkspace, "guest-library")

            DesktopStorage.wipeFiles(file.parent)

            assertFalse(Files.exists(previousWorkspace))
            assertTrue(Files.exists(file))
            val restored = manager(file).loadSession()
            assertEquals("test-user", restored?.user?.id)
            assertEquals("test-refresh-token", restored?.refreshToken)
        }
    }

    @Test
    fun sign_out_removes_tokens_from_both_disk_generations() = withSessionStore { file ->
        runBlocking {
            val initial = manager(file)
            initial.saveSession(session())
            initial.deleteSession()

            assertNull(manager(file).loadSession())
            val backup = file.resolveSibling(file.fileName.toString() + ".bak")
            assertFalse(Files.readString(file).contains("test-refresh-token"))
            assertFalse(Files.readString(backup).contains("test-refresh-token"))
            assertNull(DesktopStorage.Store(backup).getString(DesktopSessionManager.SESSION_KEY))
        }
    }

    @Test
    fun a_session_cannot_be_restored_on_a_different_server() = withSessionStore { file ->
        runBlocking {
            manager(file).saveSession(session())
            assertNull(manager(file, "https://other.example.test").loadSession())
            assertNull(manager(file).loadSession())
        }
    }

    @Test
    fun corrupt_session_does_not_block_login() = withSessionStore { file ->
        runBlocking {
            DesktopStorage.Store(file).putString(DesktopSessionManager.SESSION_KEY, "{bad json")

            assertNull(manager(file).loadSession())
            assertNull(manager(file).loadSession())
        }
    }
}
