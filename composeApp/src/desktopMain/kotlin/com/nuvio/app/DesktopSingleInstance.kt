package com.nuvio.app

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One desktop UI per OS user. The file lock is authoritative; the authenticated loopback
 * socket only raises the existing window and forwards supported launch links. The lock
 * lives outside DesktopStorage because signing out can erase the application's data files.
 */
internal class DesktopSingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val endpoint: Path,
    private val secret: String,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val activationGuard = Any()
    private var onActivation: ((List<String>) -> Unit)? = null
    private var activationPending = false
    private val pendingLinks = mutableListOf<String>()

    fun setActivationHandler(handler: ((List<String>) -> Unit)?) {
        val pending = synchronized(activationGuard) {
            onActivation = handler
            if (handler != null && activationPending) {
                activationPending = false
                pendingLinks.toList().also { pendingLinks.clear() }
            } else {
                null
            }
        }
        if (pending != null) handler?.invoke(pending)
    }

    private fun activate(links: List<String>) {
        val handler = synchronized(activationGuard) {
            onActivation.also {
                if (it == null) {
                    activationPending = true
                    pendingLinks.addAll(links.take((MAX_PENDING_LINKS - pendingLinks.size).coerceAtLeast(0)))
                }
            }
        }
        handler?.invoke(links)
    }

    private fun serve() {
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            }
            runCatching {
                client.use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    val input = DataInputStream(socket.getInputStream())
                    if (!MessageDigest.isEqual(
                            input.readUTF().toByteArray(StandardCharsets.UTF_8),
                            secret.toByteArray(StandardCharsets.UTF_8),
                        )
                    ) return@use

                    val count = input.readInt()
                    if (count !in 0..MAX_LINKS) return@use
                    val links = buildList {
                        repeat(count) {
                            val value = input.readUTF()
                            if (value.length <= MAX_LINK_LENGTH && isDesktopAppUrl(value)) add(value)
                        }
                    }
                    activate(links)
                    DataOutputStream(socket.getOutputStream()).apply {
                        writeBoolean(true)
                        flush()
                    }
                }
            }
        }
    }

    private fun publish() {
        val temp = Files.createTempFile(endpoint.parent, "starlit-endpoint-", ".tmp")
        try {
            Files.writeString(
                temp,
                server.localPort.toString() + "\n" + secret + "\n",
                StandardCharsets.UTF_8,
            )
            protectPosixPermissions(temp, "rw-------")
            try {
                Files.move(temp, endpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, endpoint, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        Thread(::serve, "starlit-instance-activation").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(endpoint) }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val MAX_LINKS = 8
        private const val MAX_LINK_LENGTH = 8192
        private const val MAX_PENDING_LINKS = 32
        private const val SOCKET_TIMEOUT_MS = 1_000
        private const val STARTUP_RETRIES = 25
        private val loopback = InetAddress.getByName("127.0.0.1")

        fun tryStart(
            launchArgs: Array<String>,
            directory: Path = Paths.get(System.getProperty("user.home"), ".starlit-single-instance"),
        ): DesktopSingleInstance? {
            Files.createDirectories(directory)
            protectPosixPermissions(directory, "rwx------")
            val endpoint = directory.resolve("endpoint")
            val channel = FileChannel.open(
                directory.resolve("instance.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val links = launchArgs.asSequence()
                .map(String::trim)
                .filter { it.length <= MAX_LINK_LENGTH && isDesktopAppUrl(it) }
                .take(MAX_LINKS)
                .toList()
            var primary = false
            try {
                for (attempt in 0 until STARTUP_RETRIES) {
                    val heldLock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (heldLock != null) {
                        val server = ServerSocket(0, 16, loopback)
                        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                        val instance = DesktopSingleInstance(channel, heldLock, server, endpoint, secret)
                        try {
                            instance.publish()
                            primary = true
                            return instance
                        } catch (error: Throwable) {
                            instance.close()
                            throw error
                        }
                    }
                    if (forwardToPrimary(endpoint, links)) return null
                    if (attempt != STARTUP_RETRIES - 1) Thread.sleep(100)
                }
                // A live lock must never be ignored just because its IPC endpoint failed.
                System.err.println("Starlit is already running but its window could not be activated.")
                return null
            } finally {
                if (!primary) channel.close()
            }
        }

        private fun forwardToPrimary(endpoint: Path, links: List<String>): Boolean = runCatching {
            if (Files.size(endpoint) > 256L) return false
            val lines = Files.readAllLines(endpoint, StandardCharsets.UTF_8)
            if (lines.size != 2) return false
            val port = lines[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            val secret = lines[1].takeIf { it.length in 32..128 } ?: return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, port), SOCKET_TIMEOUT_MS)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                DataOutputStream(socket.getOutputStream()).apply {
                    writeUTF(secret)
                    writeInt(links.size)
                    links.forEach(::writeUTF)
                    flush()
                }
                DataInputStream(socket.getInputStream()).readBoolean()
            }
        }.getOrDefault(false)

        private fun protectPosixPermissions(path: Path, mode: String) {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
            }
        }
    }
}
