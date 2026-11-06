package com.nuvio.app.features.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class RemoteCredentials(val userId: String, val server: String, val token: String)
@Serializable
private data class RemoteIdentity(val userId: String, val server: String)

@Serializable
internal data class RemoteRequest(val command: RemoteCommand, val accessToken: String)

/** A refresh never adopts a new token until the configured server has verified the same account. */
internal class RemotePeerAuthorization(initialToken: String, private val verifyToken: suspend (String) -> Unit) {
    private var token = initialToken
    private var verifiedAt = System.nanoTime()

    suspend fun accept(currentToken: String, now: Long = System.nanoTime()) {
        require(currentToken.length in 20..16_384)
        if (currentToken != token || now - verifiedAt >= 60_000_000_000L) {
            verifyToken(currentToken)
            token = currentToken
            verifiedAt = now
        }
    }
}

/** Phone approval and an approved, server-verified desktop identity precede sending its token over the channel. */
internal suspend fun authorizeRemoteChannel(
    channel: RemoteCipherChannel,
    expected: RemoteAccount,
    host: Boolean,
    approve: suspend () -> Unit,
    currentToken: suspend () -> String,
    verifyToken: suspend (String) -> Unit,
): String? {
    approve()
    if (host) {
        verifyToken(currentToken())
        channel.send(Json.encodeToString(RemoteIdentity(expected.userId, expected.server)))
        val credentials = Json.decodeFromString<RemoteCredentials>(channel.receive())
        require(credentials.server == expected.server && credentials.userId == expected.userId) { "Different account or server" }
        verifyToken(credentials.token)
        channel.send("verified")
        return credentials.token
    }
    val identity = Json.decodeFromString<RemoteIdentity>(channel.receive())
    require(identity.userId == expected.userId && identity.server == expected.server) { "Different account or server" }
    channel.send(Json.encodeToString(RemoteCredentials(expected.userId, expected.server, currentToken())))
    require(channel.receive() == "verified")
    return null
}
