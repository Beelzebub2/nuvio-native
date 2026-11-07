package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class DesktopStoragePermissionsTest {
    private val ownerOnly = PosixFilePermissions.fromString("rw-------")
    private val legacyPublic = PosixFilePermissions.fromString("rw-r--r--")

    private fun withPosixStore(test: (Path) -> Unit) {
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        assumeTrue(
            "Owner-only POSIX permissions are not available on this filesystem",
            Files.getFileAttributeView(tempRoot, PosixFileAttributeView::class.java) != null,
        )
        val dir = Files.createTempDirectory("starlit-store-owner-only")
        try {
            test(dir.resolve("account.properties"))
        } finally {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun newPrimaryAndRecoveryBackupAreOwnerOnly() = withPosixStore { file ->
        val store = DesktopStorage.Store(file)
        store.putString("credential", "first secret")
        assertEquals(ownerOnly, Files.getPosixFilePermissions(file))

        store.putString("credential", "second secret")
        val backup = file.resolveSibling(file.fileName.toString() + ".bak")
        assertEquals(ownerOnly, Files.getPosixFilePermissions(file))
        assertEquals(ownerOnly, Files.getPosixFilePermissions(backup))
        assertEquals("second secret", DesktopStorage.Store(file).getString("credential"))
        assertEquals("first secret", DesktopStorage.Store(backup).getString("credential"))
    }

    @Test
    fun loadingLegacyStoreRestrictsBothGenerationsWithoutRewritingTheirContents() =
        withPosixStore { file ->
            val store = DesktopStorage.Store(file)
            store.putString("credential", "older secret")
            store.putString("credential", "newer secret")
            val backup = file.resolveSibling(file.fileName.toString() + ".bak")

            val originalFile = Files.readAllBytes(file)
            val originalBackup = Files.readAllBytes(backup)
            Files.setPosixFilePermissions(file, legacyPublic)
            Files.setPosixFilePermissions(backup, legacyPublic)

            assertEquals("newer secret", DesktopStorage.Store(file).getString("credential"))
            assertEquals(ownerOnly, Files.getPosixFilePermissions(file))
            assertEquals(ownerOnly, Files.getPosixFilePermissions(backup))
            assertContentEquals(originalFile, Files.readAllBytes(file))
            assertContentEquals(originalBackup, Files.readAllBytes(backup))
            assertEquals("older secret", DesktopStorage.Store(backup).getString("credential"))
        }
}
