package com.nuvio.app.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopStorageTest {
    private fun withPreferences(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("desktop-store-recovery")
        try {
            test(directory.resolve("preferences.properties"))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + ".bak"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun legacy_preferences_are_upgraded_and_a_truncated_primary_restores_the_last_generation() =
        withPreferences { file ->
            Files.writeString(file, "#Nuvio desktop preferences\nkey=legacy\nother=retain\n")
            val store = DesktopStorage.Store(file)
            assertEquals("legacy", store.getString("key"))
            store.putString("key", "first")
            val backup = file.resolveSibling(file.fileName.toString() + ".bak")
            assertTrue(Files.readString(file).startsWith("# NuvioStore sha256="))
            assertEquals("legacy", DesktopStorage.Store(backup).getString("key"))

            store.putString("key", "second")
            assertEquals("first", DesktopStorage.Store(backup).getString("key"))
            val bytes = Files.readAllBytes(file)
            Files.write(file, bytes.copyOf(bytes.size - 4))

            val restored = DesktopStorage.Store(file)
            assertEquals("first", restored.getString("key"))
            assertEquals("retain", restored.getString("other"))
            assertEquals("first", DesktopStorage.Store(file).getString("key"))
            assertTrue(Files.readAllBytes(file).contentEquals(Files.readAllBytes(backup)))

            // A torn header must not be misread as an empty legacy Properties
            // file and replace the still-good previous generation.
            Files.writeString(file, "# Nuvi")
            assertEquals("first", DesktopStorage.Store(file).getString("key"))
        }

    @Test
    fun invalid_primary_and_backup_are_never_loaded_as_empty_preferences() =
        withPreferences { file ->
            val store = DesktopStorage.Store(file)
            store.putString("key", "first")
            store.putString("key", "second")
            val backup = file.resolveSibling(file.fileName.toString() + ".bak")
            val broken = Files.readAllBytes(file).copyOf(16)
            Files.write(file, broken)
            Files.write(backup, broken)

            assertFailsWith<IOException> { DesktopStorage.Store(file).getString("key") }
            assertTrue(Files.readAllBytes(file).contentEquals(broken))
            assertTrue(Files.readAllBytes(backup).contentEquals(broken))
        }

    @Test
    fun a_failed_commit_restores_the_in_memory_value_so_a_retry_can_succeed() =
        withPreferences { file ->
            val store = DesktopStorage.Store(file)
            store.putString("key", "before")
            val held = file.resolveSibling("held.properties")
            Files.move(file, held)
            Files.createDirectory(file)
            Files.writeString(file.resolve("sentinel"), "must remain")
            try {
                assertFailsWith<IOException> { store.putString("key", "after") }
                assertEquals("before", store.getString("key"))
            } finally {
                Files.delete(file.resolve("sentinel"))
                Files.delete(file)
                Files.move(held, file)
            }

            store.putString("key", "after")
            assertEquals("after", DesktopStorage.Store(file).getString("key"))
        }

    @Test
    fun validated_value_repair_preserves_newer_other_profile_values_and_the_good_backup() =
        withPreferences { file ->
            val store = DesktopStorage.Store(file)
            store.putString("profile_a", "valid")
            store.putString("profile_b", "newer")
            store.putString("profile_a", "damaged")

            assertEquals("valid", store.getStringValidated("profile_a") { it == "valid" })
            assertEquals("newer", DesktopStorage.Store(file).getString("profile_b"))
            val backup = file.resolveSibling(file.fileName.toString() + ".bak")
            assertEquals("valid", DesktopStorage.Store(backup).getString("profile_a"))
            assertEquals("valid", DesktopStorage.Store(file).getString("profile_a"))
        }
}
