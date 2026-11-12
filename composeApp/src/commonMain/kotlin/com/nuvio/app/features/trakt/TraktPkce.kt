package com.nuvio.app.features.trakt

import com.nuvio.app.features.simkl.SimklPkceCrypto
import com.nuvio.app.features.simkl.base64UrlWithoutPadding
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TraktPkceMaterial(
    val state: String,
    val verifier: String,
    val challenge: String,
)

internal fun generateTraktPkceMaterial(): TraktPkceMaterial =
    createTraktPkceMaterial(
        verifierEntropy = SimklPkceCrypto.secureRandomBytes(32),
        stateEntropy = SimklPkceCrypto.secureRandomBytes(32),
    )

internal fun createTraktPkceMaterial(
    verifierEntropy: ByteArray,
    stateEntropy: ByteArray,
): TraktPkceMaterial {
    require(verifierEntropy.size >= 32) { "PKCE verifier requires 256 bits of entropy" }
    require(stateEntropy.size >= 16) { "OAuth state requires 128 bits of entropy" }
    val verifier = verifierEntropy.base64UrlWithoutPadding()
    require(verifier.length in 43..128) { "Invalid PKCE verifier length" }
    return TraktPkceMaterial(
        state = stateEntropy.base64UrlWithoutPadding(),
        verifier = verifier,
        challenge = traktPkceChallenge(verifier),
    )
}

internal fun isValidTraktPkceVerifier(verifier: String?): Boolean =
    verifier != null && verifier.length in 43..128 &&
        verifier.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }

internal fun traktPkceChallenge(verifier: String): String {
    require(isValidTraktPkceVerifier(verifier))
    return SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding()
}

/** Production callbacks must be HTTPS URLs on the app's owned, verified domain. */
internal fun isValidTraktRedirectUri(redirectUri: String): Boolean {
    val uri = runCatching { Url(redirectUri) }.getOrNull() ?: return false
    return uri.protocol.name.equals("https", ignoreCase = true) &&
        uri.host.isNotBlank() && uri.host.contains('.') &&
        uri.fragment.isBlank() && uri.parameters.isEmpty() &&
        uri.encodedPath.startsWith("/") &&
        !redirectUri.substringAfter("://").substringBefore('/').contains('@')
}

internal fun isTraktRedirectCallback(candidate: String, redirectUri: String): Boolean {
    if (!isValidTraktRedirectUri(redirectUri)) return false
    val callback = runCatching { Url(candidate) }.getOrNull() ?: return false
    val expected = Url(redirectUri)
    return callback.protocol.name.equals("https", ignoreCase = true) &&
        callback.host.equals(expected.host, ignoreCase = true) &&
        callback.port == expected.port &&
        callback.encodedPath == expected.encodedPath &&
        callback.fragment.isBlank() &&
        callback.parameters.getAll("state")?.size == 1 &&
        (callback.parameters.getAll("code")?.size ?: 1) == 1 &&
        (callback.parameters.getAll("error")?.size ?: 1) == 1 &&
        !candidate.substringAfter("://").substringBefore('/').contains('@')
}

/** A device-code flow remains the safe fallback until the HTTPS app link is deployed. */
internal fun shouldUseTraktDeviceAuthorization(desktop: Boolean, redirectUri: String): Boolean =
    desktop || !isValidTraktRedirectUri(redirectUri)

internal fun buildTraktPkceAuthorizationUrl(clientId: String, redirectUri: String, material: TraktPkceMaterial): String {
    require(clientId.isNotBlank())
    require(isValidTraktRedirectUri(redirectUri))
    return "https://auth.trakt.tv/oauth/authorize?response_type=code" +
        "&client_id=" + clientId.encodeURLParameter() +
        "&redirect_uri=" + redirectUri.encodeURLParameter() +
        "&state=" + material.state.encodeURLParameter() +
        "&code_challenge=" + material.challenge.encodeURLParameter() +
        "&code_challenge_method=S256"
}

/** Device grants have no callback URI. Never send an empty redirect_uri in refresh requests. */
internal fun buildTraktRefreshTokenBody(refreshToken: String, clientId: String, redirectUri: String?): String =
    buildJsonObject {
        put("refresh_token", refreshToken)
        put("client_id", clientId)
        if (!redirectUri.isNullOrBlank()) put("redirect_uri", redirectUri)
        put("grant_type", "refresh_token")
    }.toString()
