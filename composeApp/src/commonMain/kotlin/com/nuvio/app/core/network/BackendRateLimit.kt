package com.nuvio.app.core.network

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.GMTDate
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val DefaultRetryDelayMs = 1_000L
private const val MaximumFallbackDelayMs = 30_000L
private const val MaximumServerRetryDelayMs = 300_000L
private const val MaximumJitterMs = 1_000L

internal class BackendRateLimitCoordinator(
    private val currentTimeMillis: () -> Long = { GMTDate().timestamp },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val cooldownUntilEpochMs = atomic(0L)

    fun record(retryAfterHeader: String?) {
        val now = currentTimeMillis()
        val delayMs = retryAfterDelayMillis(
            retryAfterHeader = retryAfterHeader,
            nowEpochMs = now,
        )
        val candidate = saturatedAdd(now, delayMs)
        while (true) {
            val current = cooldownUntilEpochMs.value
            if (candidate <= current || cooldownUntilEpochMs.compareAndSet(current, candidate)) return
        }
    }

    suspend fun awaitPermission() {
        while (true) {
            val waitMs = cooldownUntilEpochMs.value - currentTimeMillis()
            if (waitMs <= 0L) return
            pause(waitMs)
        }
    }

    fun clear() {
        cooldownUntilEpochMs.value = 0L
    }
}

internal class BackendRateLimitPluginConfig {
    lateinit var coordinator: BackendRateLimitCoordinator
}

internal val BackendRateLimitPlugin = createClientPlugin(
    name = "BackendRateLimit",
    createConfiguration = ::BackendRateLimitPluginConfig,
) {
    val coordinator = pluginConfig.coordinator

    onRequest { _, _ ->
        coordinator.awaitPermission()
    }
    onResponse { response ->
        val retryAfterHeader = response.headers[HttpHeaders.RetryAfter]
        if (shouldApplyBackendCooldown(response.status.value, retryAfterHeader)) {
            coordinator.record(retryAfterHeader)
        }
    }
}

internal fun isRetryableBackendResponse(statusCode: Int): Boolean =
    statusCode == 429 || statusCode == 503

internal fun shouldApplyBackendCooldown(statusCode: Int, retryAfterHeader: String?): Boolean =
    statusCode == 429 || (statusCode == 503 && !retryAfterHeader.isNullOrBlank())

internal fun isSafeBackendRetryRequest(method: String, encodedPath: String): Boolean {
    if (method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)) return true
    if (!method.equals("POST", ignoreCase = true)) return false

    val rpcName = encodedPath.substringAfter("/rpc/", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
    return rpcName.startsWith("sync_pull_") ||
        rpcName.startsWith("sync_get_") ||
        rpcName in safeReadRpcNames
}

internal fun retryAfterDelayMillis(
    retryAfterHeader: String?,
    nowEpochMs: Long,
    fallbackDelayMs: Long = DefaultRetryDelayMs,
): Long {
    val value = retryAfterHeader?.trim().orEmpty()
    val secondsDelay = if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
        (value.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: Long.MAX_VALUE)
            .coerceAtMost(MaximumServerRetryDelayMs / 1_000L) * 1_000L
    } else {
        value.toLongOrNull()?.coerceIn(0L, MaximumServerRetryDelayMs / 1_000L)?.times(1_000L)
    }
    val dateDelay = if (secondsDelay == null && value.isNotEmpty()) {
        runCatching { value.fromHttpToGmtDate().timestamp }
            .getOrNull()
            ?.let { serverEpochMs ->
                when {
                    serverEpochMs <= nowEpochMs -> 0L
                    serverEpochMs >= saturatedAdd(nowEpochMs, MaximumServerRetryDelayMs) -> MaximumServerRetryDelayMs
                    else -> serverEpochMs - nowEpochMs
                }
            }
    } else {
        null
    }
    return secondsDelay ?: dateDelay ?: fallbackDelayMs.coerceIn(0L, MaximumServerRetryDelayMs)
}

internal fun backendRetryDelayMillis(
    retryCount: Int,
    retryAfterHeader: String?,
    nowEpochMs: Long = GMTDate().timestamp,
    jitterMs: Long = Random.nextLong(MaximumJitterMs + 1L),
): Long {
    val exponentialDelay = (DefaultRetryDelayMs shl retryCount.coerceIn(0, 5))
        .coerceAtMost(MaximumFallbackDelayMs)
    val retryDelay = retryAfterDelayMillis(
        retryAfterHeader = retryAfterHeader,
        nowEpochMs = nowEpochMs,
        fallbackDelayMs = exponentialDelay,
    )
    return saturatedAdd(retryDelay, jitterMs.coerceIn(0L, MaximumJitterMs))
        .coerceAtMost(MaximumServerRetryDelayMs)
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private val safeReadRpcNames = setOf(
    "get_avatar_catalog",
    "get_member_profile_avatar_catalog",
    "get_member_profile_background_catalog",
    "get_my_member_access",
    "get_my_membership_overview",
    "get_sync_code",
    "get_sync_overview",
    "get_sync_owner",
)
