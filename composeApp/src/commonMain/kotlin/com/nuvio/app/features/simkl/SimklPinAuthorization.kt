package com.nuvio.app.features.simkl

import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SIMKL_V2_SCOPES = "media:read media:write"
internal const val SIMKL_DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

@Serializable
internal data class SimklDeviceRequest(
    @SerialName("client_id") val clientId: String,
    val scope: String = SIMKL_V2_SCOPES,
)

@Serializable
internal data class SimklDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null,
)

@Serializable
internal data class SimklDeviceTokenRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("grant_type") val grantType: String,
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
internal data class SimklDeviceTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
)

internal data class SimklDeviceCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

internal data class SimklPendingPinAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresAtEpochMs: Long,
)

internal sealed interface SimklPinPollResult {
    data class Authorized(val credentials: SimklDeviceCredentials) : SimklPinPollResult
    data object Pending : SimklPinPollResult
    data object SlowDown : SimklPinPollResult
    data object Gone : SimklPinPollResult
    data object Failed : SimklPinPollResult
}

internal fun SimklDeviceResponse.toPendingAuthorization(nowEpochMs: Long): SimklPendingPinAuthorization? {
    val device = deviceCode?.trim()?.takeIf(String::isNotBlank) ?: return null
    val user = userCode?.trim()?.takeIf(String::isNotBlank) ?: return null
    val verificationUrl = verificationUriComplete?.takeIf(::isTrustedSimklVerificationUrl)
        ?: verificationUri?.takeIf(::isTrustedSimklVerificationUrl)
        ?: return null
    val lifetime = expiresIn?.takeIf { it in 1L..3_600L } ?: return null
    val pollInterval = interval?.takeIf { it in 1..3_600 } ?: return null
    if (nowEpochMs < 0L || nowEpochMs > Long.MAX_VALUE - lifetime * 1_000L) return null
    return SimklPendingPinAuthorization(
        deviceCode = device,
        userCode = user,
        verificationUrl = verificationUrl,
        intervalSeconds = pollInterval,
        expiresAtEpochMs = nowEpochMs + lifetime * 1_000L,
    )
}

internal fun SimklDeviceTokenResponse.toCredentials(
    nowEpochMs: Long,
    previousRefreshToken: String? = null,
): SimklDeviceCredentials? {
    val access = accessToken?.trim()?.takeIf(String::isNotBlank) ?: return null
    val refresh = refreshToken?.trim()?.takeIf(String::isNotBlank)
        ?: previousRefreshToken?.takeIf(String::isNotBlank)
        ?: return null
    val lifetime = expiresIn?.takeIf { it in 1L..31_536_000L } ?: return null
    if (nowEpochMs < 0L || nowEpochMs > Long.MAX_VALUE - lifetime * 1_000L) return null
    if (!tokenType.equals("bearer", ignoreCase = true)) return null
    val permissions = scope?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: return null
    if (!permissions.containsAll(setOf("media:read", "media:write"))) return null
    return SimklDeviceCredentials(access, refresh, nowEpochMs + lifetime * 1_000L)
}

internal fun simklPinOAuthError(errorCode: String?): SimklPinPollResult = when (errorCode) {
    "authorization_pending" -> SimklPinPollResult.Pending
    "slow_down" -> SimklPinPollResult.SlowDown
    "expired_token", "access_denied" -> SimklPinPollResult.Gone
    else -> SimklPinPollResult.Failed
}

private fun isTrustedSimklVerificationUrl(value: String): Boolean = runCatching {
    Url(value).let { url ->
        url.protocol == URLProtocol.HTTPS &&
            url.host.equals("simkl.com", ignoreCase = true) &&
            url.port == 443 &&
            url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() &&
            url.fragment.isEmpty()
    }
}.getOrDefault(false)

internal fun isSimklPinAuthorizationExpired(expiresAtEpochMs: Long?, nowEpochMs: Long): Boolean =
    expiresAtEpochMs == null || nowEpochMs >= expiresAtEpochMs
