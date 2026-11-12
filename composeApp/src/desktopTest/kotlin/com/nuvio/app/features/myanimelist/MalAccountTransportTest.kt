package com.nuvio.app.features.myanimelist
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
class MalAccountTransportTest {
    @Test fun suspendedCredentialWriteKeepsCapturedOwnersBearerAfterAccountSwitch() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val received=AtomicReference<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/rest/v1/rpc/sync_push_provider_credentials") { exchange ->
            received.set(exchange.requestHeaders.getFirst("Authorization"));entered.countDown();release.await(5,TimeUnit.SECONDS)
            val bytes="[]".toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use{it.write(bytes)}
        }
        server.start()
        try {
            var sharedBearer="owner-a-token"
            val captured=MalBoundSession("owner-a","http://127.0.0.1:${server.address.port}","public-key",sharedBearer)
            val job=async(Dispatchers.IO){boundMalAccountRpc(captured,"sync_push_provider_credentials",buildJsonObject{put("p_profile_id",1)})}
            assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
            sharedBearer="owner-b-token";release.countDown();job.await()
            assertEquals("owner-b-token",sharedBearer);assertEquals("Bearer owner-a-token",received.get())
        } finally {release.countDown();server.stop(0)}
    }
}
