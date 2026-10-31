package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

private const val OWNER_FILE = ".nuvio-download-cache-owner"
private const val LOCK_FILE = ".nuvio-download-cache.lock"
private const val CHILD_PID_FILE = ".nuvio-download-torrserver.pid"
private const val OWNER_SIGNATURE = "NuvioNative/download-torrent-cache/v1"
private val TORRENT_HASH = Regex("(?:[a-f0-9]{40}|[a-f0-9]{64}|[a-z2-7]{32})")
private val SESSION_ID = Regex("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")

private fun normalizedTorrentHash(hash: String): String = hash.lowercase().also {
    require(TORRENT_HASH.matches(it)) { "Invalid torrent cache hash" }
}

private fun ownerText(hash: String, session: String): String = "$OWNER_SIGNATURE\n$hash\n$session\n"

private fun hasOwnerMarker(session: Path, hash: String): Boolean {
    val id = session.fileName?.toString() ?: return false
    val marker = session.resolve(OWNER_FILE)
    return runCatching {
        Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) <= 256L &&
            Files.readString(marker) == ownerText(hash, id)
    }.getOrDefault(false)
}

/**
 * TorrServer owns the `pieces` subtree only. The immutable marker and advisory lock live next to
 * it so a server dropping its own pieces cannot accidentally erase our ownership evidence.
 */
internal fun isManagedDownloadTorrentCache(cache: File): Boolean {
    val pieces = cache.toPath()
    val session = pieces.parent ?: return false
    val hashDirectory = session.parent ?: return false
    val hash = hashDirectory.fileName?.toString() ?: return false
    val id = session.fileName?.toString() ?: return false
    if (pieces.fileName?.toString() != "pieces" || !TORRENT_HASH.matches(hash) || !SESSION_ID.matches(id)) return false
    if (!Files.isDirectory(pieces, NOFOLLOW_LINKS) || !Files.isDirectory(session, NOFOLLOW_LINKS) ||
        !Files.isDirectory(hashDirectory, NOFOLLOW_LINKS)) return false
    return hasOwnerMarker(session, hash)
}

/**
 * Owns a new, uniquely named cache generation for each running hash. A startup scavenger only
 * removes complete, marked generations it can exclusively lock; old unmarked cache directories,
 * other applications' files and active downloads are left intact.
 */
internal class DownloadTorrentCacheOwnership(private val root: Path) : AutoCloseable {
    private data class Lease(val session: Path, val hash: String, val channel: FileChannel, val lock: FileLock) {
        fun release() {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }

    private val log = Logger.withTag("DownloadTorrentCache")
    private val active = mutableMapOf<String, Lease>()
    private var closed = false

    @Synchronized fun claim(hash: String): File {
        check(!closed) { "Download torrent cache manager is closed" }
        val key = normalizedTorrentHash(hash)
        active[key]?.let { return it.session.resolve("pieces").toFile() }
        ensureManagedDirectory(root)
        val hashDirectory = root.resolve(key)
        ensureManagedDirectory(hashDirectory)

        val session = Files.createDirectory(hashDirectory.resolve(UUID.randomUUID().toString()))
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            val lockFile = Files.createFile(session.resolve(LOCK_FILE))
            channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)
            lock = channel.tryLock() ?: throw IOException("Could not lock new torrent cache")
            Files.createDirectory(session.resolve("pieces"))
            Files.writeString(session.resolve(OWNER_FILE), ownerText(key, session.fileName.toString()),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            active[key] = Lease(session, key, channel, lock)
            return session.resolve("pieces").toFile()
        } catch (failure: Throwable) {
            runCatching { lock?.release() }
            runCatching { channel?.close() }
            // The backend has not been given this path, so only files created above can exist.
            runCatching { Files.deleteIfExists(session.resolve(OWNER_FILE)) }
            runCatching { Files.deleteIfExists(session.resolve("pieces")) }
            runCatching { Files.deleteIfExists(session.resolve(LOCK_FILE)) }
            runCatching { Files.deleteIfExists(session) }
            throw failure
        }
    }

    /** Called after the backend process is stopped, while the hash is still reserved by its owner. */
    @Synchronized fun retire(hash: String): Long {
        val lease = active.remove(normalizedTorrentHash(hash)) ?: return 0L
        return removeOwned(lease)
    }

    /** Persist the actual TorrServer child PID after successful preparation, including restarts. */
    @Synchronized fun recordProcessId(hash: String, pid: Long?) {
        if (pid == null || pid <= 0L) return
        val lease = active[normalizedTorrentHash(hash)] ?: return
        val destination = lease.session.resolve(CHILD_PID_FILE)
        val temporary = Files.createTempFile(lease.session, "torrserver-pid-", ".tmp")
        try {
            Files.writeString(temporary, "$pid\n")
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    /**
     * Fence the previous child PID before any prepare/restart. If the app crashes after this
     * point, an unknown successor child may still be alive, so the sweeper must preserve cache.
     */
    @Synchronized fun invalidateProcessId(hash: String) {
        val lease = active[normalizedTorrentHash(hash)] ?: return
        val pidFile = lease.session.resolve(CHILD_PID_FILE)
        check(!Files.exists(pidFile, NOFOLLOW_LINKS) || Files.isRegularFile(pidFile, NOFOLLOW_LINKS)) {
            "Unsafe torrent child PID record"
        }
        Files.deleteIfExists(pidFile)
    }

    /** Reclaims only previously marked caches whose process lock was released after a crash. */
    @Synchronized fun scavenge(): Long {
        check(!closed) { "Download torrent cache manager is closed" }
        if (!Files.exists(root, NOFOLLOW_LINKS)) return 0L
        if (!isExpectedDirectory(root)) return 0L
        var reclaimed = 0L
        Files.newDirectoryStream(root).use { hashes ->
            for (hashDirectory in hashes) {
                val hash = hashDirectory.fileName.toString()
                if (!TORRENT_HASH.matches(hash) || !isExpectedDirectory(hashDirectory)) continue
                Files.newDirectoryStream(hashDirectory).use { sessions ->
                    for (session in sessions) {
                        if (!SESSION_ID.matches(session.fileName.toString()) || !isExpectedDirectory(session)) continue
                        if (active[hash]?.session == session) continue
                        val orphanIsSafe = runCatching { ownsSession(hash, session) && !childMightStillBeRunning(session) }
                            .getOrElse { log.w(it) { "Cannot verify orphan torrent cache ownership" }; false }
                        if (!orphanIsSafe) continue
                        val lockFile = session.resolve(LOCK_FILE)
                        if (Files.exists(lockFile, NOFOLLOW_LINKS) && !Files.isRegularFile(lockFile, NOFOLLOW_LINKS)) continue
                        val channel = try {
                            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                        } catch (failure: IOException) {
                            log.w(failure) { "Could not inspect an abandoned torrent cache lock" }
                            continue
                        }
                        val lock = try {
                            channel.tryLock()
                        } catch (_: OverlappingFileLockException) {
                            null
                        } catch (e: IOException) {
                            log.w(e) { "Could not lock an abandoned torrent cache" }
                            null
                        }
                        if (lock == null) {
                            channel.close()
                            continue
                        }
                        reclaimed += runCatching { removeOwned(Lease(session, hash, channel, lock)) }
                            .getOrElse { log.w(it) { "Preserving an unsafe or unreadable orphan torrent cache" }; 0L }
                    }
                }
                removeEmptyHashDirectory(hashDirectory)
            }
        }
        return reclaimed
    }

    /** A clean shutdown releases locks; remaining caches are recognized as orphans next launch. */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        active.values.forEach(Lease::release)
        active.clear()
    }

    private fun removeOwned(lease: Lease): Long {
        val session = lease.session
        val marker = session.resolve(OWNER_FILE)
        val lockFile = session.resolve(LOCK_FILE)
        try {
            check(ownsSession(lease.hash, session)) { "Torrent cache ownership marker is invalid" }
            val verified = verifiedFiles(session)
            var reclaimed = 0L
            // Keep the marker until every cache file has been removed; failures remain recoverable.
            verified.first.filter { it != marker && it != lockFile }.forEach { file ->
                reclaimed += Files.size(file)
                Files.delete(file)
            }
            verified.second.filter { it != session }.asReversed().forEach(Files::delete)

            lease.release()
            Files.deleteIfExists(lockFile)
            Files.delete(marker)
            Files.delete(session)
            removeEmptyHashDirectory(session.parent)
            return reclaimed
        } finally {
            lease.release()
        }
    }

    private fun ownsSession(hash: String, session: Path): Boolean {
        if (session.parent?.fileName?.toString() != hash || !SESSION_ID.matches(session.fileName.toString())) return false
        if (!isExpectedDirectory(root) || !isExpectedDirectory(session.parent) || !isExpectedDirectory(session)) return false
        if (!hasOwnerMarker(session, hash)) return false
        // TorrServer can remove its entire pieces directory during /drop.
        val pieces = session.resolve("pieces")
        if (Files.exists(pieces, NOFOLLOW_LINKS) && !isExpectedDirectory(pieces)) return false
        val realRoot = root.toRealPath()
        return session.toRealPath().startsWith(realRoot) && session.parent.toRealPath().startsWith(realRoot)
    }

    private fun childMightStillBeRunning(session: Path): Boolean {
        val pidFile = session.resolve(CHILD_PID_FILE)
        if (Files.exists(pidFile, NOFOLLOW_LINKS)) {
            // A damaged PID record cannot prove that an orphan is safe to reclaim.
            if (!Files.isRegularFile(pidFile, NOFOLLOW_LINKS) || Files.size(pidFile) > 32L) return true
            val pid = Files.readString(pidFile).trim().toLongOrNull()?.takeIf { it > 0L } ?: return true
            return try {
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            } catch (_: SecurityException) {
                true
            }
        }
        // A crash between process start and PID recording can leave the child alive indefinitely.
        // Without proof of a dead child, keep the marked cache until explicit safe cleanup.
        return true
    }

    /** Verify the entire tree before the first deletion, refusing symlinks and junction escapes. */
    private fun verifiedFiles(session: Path): Pair<List<Path>, List<Path>> {
        val rootReal = root.toRealPath()
        val files = mutableListOf<Path>()
        val directories = mutableListOf<Path>()
        Files.walkFileTree(session, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(attrs.isDirectory && !Files.isSymbolicLink(dir) && dir.toRealPath().startsWith(rootReal)) {
                    "Unsafe torrent cache directory"
                }
                directories.add(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(attrs.isRegularFile && !attrs.isSymbolicLink && file.toRealPath().startsWith(rootReal)) {
                    "Unsafe torrent cache file"
                }
                files.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return files to directories
    }

    private fun ensureManagedDirectory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            check(isExpectedDirectory(path)) { "Unsafe torrent cache directory" }
        } else Files.createDirectories(path).also {
            check(isExpectedDirectory(path)) { "Unsafe torrent cache directory" }
        }
    }

    private fun isExpectedDirectory(path: Path): Boolean = Files.isDirectory(path, NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(path)

    private fun removeEmptyHashDirectory(hashDirectory: Path) {
        if (isExpectedDirectory(hashDirectory) && TORRENT_HASH.matches(hashDirectory.fileName.toString())) {
            // Nonempty directories (including legacy unmarked caches) are never removed.
            runCatching { Files.delete(hashDirectory) }
        }
    }
}
