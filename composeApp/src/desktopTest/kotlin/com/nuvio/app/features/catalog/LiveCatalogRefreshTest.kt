package com.nuvio.app.features.catalog

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveCatalogRefreshTest {
    @Test fun forcedRefreshReplacesDataWithoutBlankingTheVisibleCatalog() = runBlocking {
        val enteredRefresh = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        var requestCount = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestCount++
            if (requestCount > 1) {
                assertEquals("no-cache", exchange.requestHeaders.getFirst("Cache-Control"))
                enteredRefresh.complete(Unit)
                runBlocking { finishRefresh.await() }
            }
            val name = if (requestCount == 1) "Old episode" else "New episode"
            val bytes = """{"metas":[{"id":"tt0000001","type":"series","name":"$name"}]}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val target = CatalogTarget.Addon("http://127.0.0.1:${server.address.port}/manifest.json", "series", "airing", supportsPagination = false)
            CatalogRepository.clear()
            CatalogRepository.load(target)
            val initial = withTimeout(10_000) { CatalogRepository.uiState.first { !it.isLoading && it.items.isNotEmpty() } }
            assertEquals("Old episode", initial.items.single().name)
            CatalogRepository.load(target, force = true)
            withTimeout(10_000) { enteredRefresh.await() }
            assertTrue(CatalogRepository.uiState.value.isLoading)
            assertEquals("Old episode", CatalogRepository.uiState.value.items.single().name)
            finishRefresh.complete(Unit)
            val updated = withTimeout(10_000) { CatalogRepository.uiState.first { !it.isLoading } }
            assertEquals("New episode", updated.items.single().name)
        } finally {
            finishRefresh.complete(Unit)
            CatalogRepository.clear()
            server.stop(0)
        }
    }
}
