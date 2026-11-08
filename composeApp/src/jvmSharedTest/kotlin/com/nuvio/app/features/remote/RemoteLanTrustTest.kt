package com.nuvio.app.features.remote

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlin.test.*

private class TestLanStore : RemoteLanTrustStore {
    private val values = ConcurrentHashMap<String, RemoteLanTrust>()
    override fun read(account: RemoteAccount, entry: String) = values[remoteLanTrustKey(account, entry)]
    override fun write(account: RemoteAccount, entry: String, trust: RemoteLanTrust) {
        values[remoteLanTrustKey(account, entry)] = trust
    }
    override fun delete(account: RemoteAccount, entry: String) {
        values.remove(remoteLanTrustKey(account, entry))
    }
}

class RemoteLanTrustTest {
    private val account = RemoteAccount("owner", "https://accounts.example", "public")

    @Test fun firstPairingAndReconnectionAcrossIpChangeAndForget() = runBlocking {
        val hostStore = TestLanStore()
        val phoneStore = TestLanStore()
        var desktopId: String? = null
        withPeers { host, phone ->
            assertTrue(host.supportsTrustedReconnect, "Bluetooth capability must remain backward compatible")
            assertTrue(host.supportsLanTrustedReconnect)
            assertTrue(phone.supportsLanTrustedReconnect)
            val h = async(Dispatchers.IO) { resumeLanTrust(host, account, true, hostStore) }
            val p = async(Dispatchers.IO) { resumeLanTrust(phone, account, false, phoneStore) }
            val hostResult = h.await()
            val phoneResult = p.await()
            assertFalse(hostResult.trusted, "The initial connection needs two code approvals")
            assertFalse(phoneResult.trusted)
            desktopId = assertNotNull(phoneResult.desktopId)
            assertEquals(hostResult.desktopId, desktopId)

            val desktopAuth = async(Dispatchers.IO) {
                authorizeRemoteChannel(host.channel, account, true, {}, { "desktop-token" }, {})
            }
            val phoneAuth = async(Dispatchers.IO) {
                authorizeRemoteChannel(phone.channel, account, false, {}, { "phone-token" }, {})
            }
            assertEquals("phone-token", desktopAuth.await())
            assertNull(phoneAuth.await())
            val hostEnroll = async(Dispatchers.IO) {
                enrollLanTrust(host.channel, account, true, hostResult.desktopId, hostStore)
            }
            val phoneEnroll = async(Dispatchers.IO) {
                enrollLanTrust(phone.channel, account, false, phoneResult.desktopId, phoneStore)
            }
            hostEnroll.await()
            phoneEnroll.await()
        }
        val saved = assertNotNull(phoneStore.read(account, "phone:$desktopId"))
        assertEquals(saved, hostStore.read(account, "host:" + saved.id))

        // The address never enters the lookup key, so changing Wi-Fi/IP is safe.
        withPeers { host, phone ->
            val h = async(Dispatchers.IO) { resumeLanTrust(host, account, true, hostStore) }
            val p = async(Dispatchers.IO) { resumeLanTrust(phone, account, false, phoneStore) }
            assertTrue(h.await().trusted)
            val result = p.await()
            assertTrue(result.trusted)
            assertEquals(desktopId, result.desktopId)
            assertNotEquals(remoteLanTrustProof(saved, host.transcript, "phone"),
                remoteLanTrustProof(saved, host.transcript, "desktop"))
            assertNotEquals(remoteLanTrustProof(saved, host.transcript, "phone"),
                remoteLanTrustProof(saved, randomRemoteBytes(), "phone"))
        }
        phoneStore.delete(account, "phone:$desktopId")
        withPeers { host, phone ->
            val h = async(Dispatchers.IO) { resumeLanTrust(host, account, true, hostStore) }
            val p = async(Dispatchers.IO) { resumeLanTrust(phone, account, false, phoneStore) }
            assertFalse(h.await().trusted)
            assertFalse(p.await().trusted)
        }
    }

    @Test fun unknownAndUnprotectedPeersNeverSkipApproval() = runBlocking {
        val hostStore = TestLanStore()
        val phoneStore = TestLanStore()
        withPeers { host, phone ->
            val h = async(Dispatchers.IO) { resumeLanTrust(host, account, true, hostStore) }
            val p = async(Dispatchers.IO) { resumeLanTrust(phone, account, false, phoneStore) }
            assertFalse(h.await().trusted)
            val id = assertNotNull(p.await().desktopId)
            phoneStore.write(account, "phone:$id", RemoteLanTrust("f".repeat(32), "a".repeat(64)))
        }
        withPeers { host, phone ->
            val h = async(Dispatchers.IO) { resumeLanTrust(host, account, true, hostStore) }
            val p = async(Dispatchers.IO) { resumeLanTrust(phone, account, false, phoneStore) }
            assertFalse(h.await().trusted)
            assertFalse(p.await().trusted, "A forged credential must not authorize a connection")
        }
        withPeers { host, phone ->
            val h = async(Dispatchers.IO) { resumeLanTrust(host, account, true, null) }
            val p = async(Dispatchers.IO) { resumeLanTrust(phone, account, false, null) }
            assertFalse(h.await().trusted)
            assertFalse(p.await().trusted)
        }
    }

    @Test fun rememberedDesktopIdentitySurvivesIpChangeWithoutDuplicate() {
        val id = "1".repeat(32)
        val old = rememberRemoteDevice(emptyList(), "PC", "192.168.1.20", 1L, trustedId = id)
            .map { it.copy(wakeMac = "02:12:34:56:78:9A") }
        val updated = rememberRemoteDevice(old, "PC", "10.0.0.50", 2L, trustedId = id)
        assertEquals(1, updated.size)
        assertEquals("10.0.0.50", updated.single().address)
        assertEquals("02:12:34:56:78:9A", updated.single().wakeMac)
        assertEquals(id, updated.single().trustedId)
        assertNull(rememberRemoteDevice(emptyList(), "PC", "10.0.0.50", 3L,
            trustedId = "invalid").single().trustedId)
    }

    private suspend fun withPeers(
        block: suspend CoroutineScope.(RemotePairingChannel, RemotePairingChannel) -> Unit,
    ) = coroutineScope {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { phone ->
                listener.accept().use { host ->
                    host.soTimeout = 5_000
                    phone.soTimeout = 5_000
                    val desktopHandshake = async(Dispatchers.IO) { openRemoteChannel(host, true) }
                    val phoneHandshake = async(Dispatchers.IO) { openRemoteChannel(phone, false) }
                    block(desktopHandshake.await(), phoneHandshake.await())
                }
            }
        }
    }
}

