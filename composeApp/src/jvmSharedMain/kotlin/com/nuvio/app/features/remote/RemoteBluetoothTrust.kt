package com.nuvio.app.features.remote

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * App-level Bluetooth trust is separate from Android/Windows OS bonding and from
 * the remembered-PC list. Only the OS-protected store holds these secrets.
 * A reconnect still performs a fresh encrypted handshake and verifies the account.
 */
@Serializable
internal data class RemoteBluetoothTrust(val id: String, val secret: String) {
    fun valid(): Boolean = id.matches(Regex("[0-9a-f]{32}")) &&
        secret.matches(Regex("[0-9a-f]{64}"))
}

internal interface RemoteBluetoothTrustStore {
    fun read(account: RemoteAccount, entry: String): RemoteBluetoothTrust?
    fun write(account: RemoteAccount, entry: String, trust: RemoteBluetoothTrust)
    fun delete(account: RemoteAccount, entry: String)
}

internal fun remoteBluetoothTrustKey(account: RemoteAccount, entry: String): String {
    require(entry.length in 1..80)
    return "bt_" + remoteHash(
        account.server.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        account.userId.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        entry.toByteArray(Charsets.UTF_8),
    ).toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private val trustJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class TrustHello(val id: String = "", val proof: String = "")

@Serializable
private data class TrustReply(val proof: String = "")

@Serializable
private data class TrustEnrollment(val id: String = "", val secret: String = "")

/** Proofs bind both device roles and every byte of this fresh handshake. */
internal fun remoteBluetoothTrustProof(
    trust: RemoteBluetoothTrust, transcript: ByteArray, role: String,
): String {
    require(trust.valid() && transcript.size == 32 && role in listOf("phone", "desktop"))
    val hmac = Mac.getInstance("HmacSHA256")
    hmac.init(SecretKeySpec(trust.secret.hexBytes(), "HmacSHA256"))
    hmac.update("starlit-bluetooth-resume/v1".toByteArray(Charsets.US_ASCII))
    hmac.update(0)
    hmac.update(role.toByteArray(Charsets.US_ASCII))
    hmac.update(0)
    hmac.update(transcript)
    return hmac.doFinal().toHex()
}

private fun safeTrustRead(
    store: RemoteBluetoothTrustStore?, account: RemoteAccount, entry: String,
): RemoteBluetoothTrust? = runCatching {
    store?.read(account, entry)?.takeIf(RemoteBluetoothTrust::valid)
}.getOrNull()

/** Either side returns false on missing/revoked/unreadable trust; normal approval follows. */
internal fun resumeBluetoothTrust(
    pairing: RemotePairingChannel,
    account: RemoteAccount,
    host: Boolean,
    address: String?,
    store: RemoteBluetoothTrustStore?,
): Boolean {
    check(pairing.supportsTrustedReconnect)
    val channel = pairing.channel
    if (host) {
        val hello = trustJson.decodeFromString<TrustHello>(channel.receive())
        val stored = hello.id.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let {
            safeTrustRead(store, account, "host:$it")
        }
        val valid = stored != null && hello.id == stored.id &&
            hello.proof.matches(Regex("[0-9a-f]{64}")) &&
            MessageDigest.isEqual(
                hello.proof.hexBytes(),
                remoteBluetoothTrustProof(stored, pairing.transcript, "phone").hexBytes(),
            )
        channel.send(trustJson.encodeToString(TrustReply(
            if (valid) remoteBluetoothTrustProof(stored, pairing.transcript, "desktop") else "",
        )))
        return valid
    }
    val stored = address?.let { safeTrustRead(store, account, "phone:$it") }
    channel.send(trustJson.encodeToString(TrustHello(
        stored?.id.orEmpty(),
        stored?.let { remoteBluetoothTrustProof(it, pairing.transcript, "phone") }.orEmpty(),
    )))
    val reply = trustJson.decodeFromString<TrustReply>(channel.receive())
    return stored != null && reply.proof.matches(Regex("[0-9a-f]{64}")) &&
        MessageDigest.isEqual(
            reply.proof.hexBytes(),
            remoteBluetoothTrustProof(stored, pairing.transcript, "desktop").hexBytes(),
        )
}

/** Exchange a new proof key only after BOTH code approvals and account verification. */
internal fun enrollBluetoothTrust(
    channel: RemoteCipherChannel,
    account: RemoteAccount,
    host: Boolean,
    address: String?,
    store: RemoteBluetoothTrustStore?,
) {
    if (host) {
        val candidate = RemoteBluetoothTrust(randomRemoteBytes().copyOf(16).toHex(), randomRemoteBytes().toHex())
        // Never send an unpersisted credential: it could cause a phone to trust an
        // identity the desktop cannot subsequently verify.
        val saved = runCatching { store?.write(account, "host:${candidate.id}", candidate); store != null }
            .getOrDefault(false)
        channel.send(trustJson.encodeToString(
            if (saved) TrustEnrollment(candidate.id, candidate.secret) else TrustEnrollment(),
        ))
    } else {
        val enrollment = trustJson.decodeFromString<TrustEnrollment>(channel.receive())
        val trust = RemoteBluetoothTrust(enrollment.id, enrollment.secret)
        if (address != null && trust.valid()) {
            runCatching { store?.write(account, "phone:$address", trust) }
        }
    }
}
