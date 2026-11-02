package com.nuvio.app.features.remote

import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopRemoteProtocolTest {
    @Test
    fun socketPeersRequireBothApprovalsAndVerifyBothAccountTokensBeforeCommands() = runBlocking {
        withPeers { host, phone ->
            assertEquals(host.code, phone.code)
            val account = RemoteAccount("account-a", "https://accounts.example", "public")
            val hostApproval = CompletableDeferred<Unit>()
            val phoneApproval = CompletableDeferred<Unit>()
            val verified = CopyOnWriteArrayList<String>()
            val phoneTokenRead = AtomicBoolean()
            val hostAuth = async(Dispatchers.IO) {
                authorizeRemoteChannel(host.channel, account, true, { hostApproval.await() }, { "desktop-token" }, { verified += it })
            }
            val phoneAuth = async(Dispatchers.IO) {
                authorizeRemoteChannel(phone.channel, account, false, { phoneApproval.await() }, { phoneTokenRead.set(true); "phone-token" }, { fail("Phone does not validate tokens locally") })
            }
            delay(100)
            assertTrue(verified.isEmpty())
            assertFalse(phoneTokenRead.get())
            hostApproval.complete(Unit)
            delay(100)
            assertFalse(phoneTokenRead.get(), "Phone must approve before exposing its token")
            phoneApproval.complete(Unit)
            assertNull(phoneAuth.await())
            assertEquals("phone-token", hostAuth.await())
            assertEquals(listOf("desktop-token", "phone-token"), verified.toList())
            phone.channel.send("pause")
            assertEquals("pause", host.channel.receive())
            host.channel.send("paused at 12000")
            assertEquals("paused at 12000", phone.channel.receive())
        }
    }

    @Test
    fun differentAccountOrServerIsRejectedBeforePhoneTokenIsRead() = runBlocking {
        for (wrongServer in listOf(false, true)) {
        withPeers { host, phone ->
            val hostAccount = RemoteAccount("account-a", "https://accounts.example", "public")
            val tokenRead = AtomicBoolean()
            val hostAuth = async(Dispatchers.IO) {
                runCatching { authorizeRemoteChannel(host.channel, hostAccount, true, {}, { "desktop-token" }, {}) }
            }
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val wrongAccount = if (wrongServer) hostAccount.copy(server = "https://different.example") else hostAccount.copy(userId = "account-b")
                    authorizeRemoteChannel(phone.channel, wrongAccount, false, {}, { tokenRead.set(true); "phone-token" }, {})
                }.exceptionOrNull()
            }
            assertIs<IllegalArgumentException>(error)
            assertFalse(tokenRead.get())
            // Host times out/EOF rather than entering a playback loop.
            assertTrue(hostAuth.await().isFailure)
        }
        }
    }

    @Test
    fun rejectedAccountTokenCannotCompleteAuthorization() = runBlocking {
        withPeers { host, phone ->
            val account = RemoteAccount("account-a", "https://accounts.example", "public")
            val hostAuth = async(Dispatchers.IO) {
                runCatching {
                    authorizeRemoteChannel(host.channel, account, true, {}, { "desktop-token" }, {
                        require(it != "revoked-phone-token") { "Server rejected token" }
                    })
                }
            }
            val phoneAuth = async(Dispatchers.IO) {
                runCatching { authorizeRemoteChannel(phone.channel, account, false, {}, { "revoked-phone-token" }, {}) }
            }
            assertIs<IllegalArgumentException>(hostAuth.await().exceptionOrNull())
            assertTrue(phoneAuth.await().isFailure)
        }
    }

    @Test
    fun oversizedUnicodeAndEscapedMetadataIsBoundedBeforeSending() {
        val choices = List(2000) { RemoteChoice("$it-" + "\u0000".repeat(200), "🎞".repeat(150)) }
        val encoded = RemotePlayback(playbackId = "id", episodes = choices, subtitles = choices, sources = choices).toRemoteWireJson()
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= MAX_REMOTE_FRAME / 2)
        assertTrue(encoded.contains("Some choices are hidden"))
    }

    @Test
    fun rejectionAndTimeoutNeverObtainAccountToken() = runBlocking {
        withPeers { host, _ ->
            val account = RemoteAccount("a", "https://accounts.example", "public")
            var tokenReads = 0
            assertFailsWith<IllegalStateException> {
                authorizeRemoteChannel(host.channel, account, true, { error("Codes differ") }, { tokenReads++; "token" }, { fail("Must not verify") })
            }
            assertFailsWith<TimeoutCancellationException> {
                authorizeRemoteChannel(host.channel, account, true, { withTimeout(20) { awaitCancellation() } }, { tokenReads++; "token" }, { fail("Must not verify") })
            }
            assertEquals(0, tokenReads)
        }
    }

    @Test
    fun tokenRefreshIsVerifiedBeforeAdoptionAndRevocationFailsClosed() = runBlocking {
        val verified = mutableListOf<String>()
        val initial = "initial-token-for-the-same-account"
        val refreshed = "refreshed-token-for-the-same-account"
        val wrongAccount = "token-from-a-different-account"
        val peer = RemotePeerAuthorization(initial) {
            verified += it
            require(it != wrongAccount) { "Server returned another user" }
        }
        peer.accept(initial)
        assertTrue(verified.isEmpty())
        peer.accept(refreshed)
        assertEquals(listOf(refreshed), verified)
        peer.accept(refreshed)
        assertEquals(1, verified.size)
        assertFailsWith<IllegalArgumentException> { peer.accept(wrongAccount) }
        assertFailsWith<IllegalArgumentException> { peer.accept(wrongAccount) }
        peer.accept(refreshed, System.nanoTime() + 61_000_000_000)
        assertEquals(refreshed, verified.last())
        assertEquals(4, verified.size)
    }

    @Test
    fun ciphertextTamperingReplayAndReflectionAreRejected() {
        val transcript = randomRemoteBytes()
        val bytes = ByteArrayOutputStream()
        val sender = RemoteCipherChannel(DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(bytes), transcript, true)
        sender.send("a command")
        val frame = bytes.toByteArray()
        fun receiver(data: ByteArray, host: Boolean = false) = RemoteCipherChannel(DataInputStream(ByteArrayInputStream(data)), DataOutputStream(ByteArrayOutputStream()), transcript, host)
        val replay = receiver(frame + frame)
        assertEquals("a command", replay.receive())
        assertFailsWith<IllegalArgumentException> { replay.receive() }
        assertFails { receiver(frame.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }).receive() }
        assertFails { receiver(frame, host = true).receive() }
    }

    @Test
    fun commitmentAndFrameBoundsFailClosed() {
        val keys = remoteRsaKeys()
        val nonce = randomRemoteBytes()
        val secret = randomRemoteBytes()
        val encrypted = encryptRemoteSecret(keys.public.encoded, secret)
        assertContentEquals(secret, decryptRemoteSecret(keys.private, encrypted))
        val commitment = remoteHash(keys.public.encoded, nonce)
        assertFailsWith<IllegalArgumentException> { remoteTranscript(keys.public.encoded, commitment, encrypted, randomRemoteBytes(), secret) }
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }.toByteArray()
        assertFailsWith<IllegalArgumentException> { DataInputStream(ByteArrayInputStream(bytes)).readFrame() }
    }

    @Test
    fun privateAddressMustMatchTheActualInterfaceSubnet() {
        fun ip(value: String) = InetAddress.getByName(value).address
        assertTrue(sameRemoteSubnet(ip("192.168.1.80"), ip("192.168.1.2"), 24))
        assertFalse(sameRemoteSubnet(ip("192.168.2.80"), ip("192.168.1.2"), 24))
        assertTrue(sameRemoteSubnet(ip("172.16.31.255"), ip("172.16.16.2"), 20))
        assertFalse(sameRemoteSubnet(ip("172.16.32.1"), ip("172.16.16.2"), 20))
        assertFalse(sameRemoteSubnet(ip("192.168.1.80"), ip("192.168.1.2"), 0))
    }

    private suspend fun withPeers(block: suspend CoroutineScope.(RemotePairingChannel, RemotePairingChannel) -> Unit) = coroutineScope {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { phone ->
                listener.accept().use { host ->
                    host.soTimeout = 5000
                    phone.soTimeout = 5000
                    val desktopHandshake = async(Dispatchers.IO) { openRemoteChannel(host, true) }
                    val phoneHandshake = async(Dispatchers.IO) { openRemoteChannel(phone, false) }
                    block(desktopHandshake.await(), phoneHandshake.await())
                }
            }
        }
    }
}
