package com.nuvio.app.features.remote

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LAN trust is separate from Bluetooth pairing and saved IP addresses.
 * Each new encrypted session requires mutual transcript-bound proofs; HTTPS
 * same-account verification is mandatory even when the code dialog is skipped.
 */
@Serializable
internal data class RemoteLanTrust(val id: String, val secret: String) {
    fun valid(): Boolean = id.matches(Regex("[0-9a-f]{32}")) &&
        secret.matches(Regex("[0-9a-f]{64}"))
}
internal interface RemoteLanTrustStore {
    fun read(account: RemoteAccount, entry: String): RemoteLanTrust?
    fun write(account: RemoteAccount, entry: String, trust: RemoteLanTrust)
    fun delete(account: RemoteAccount, entry: String)
}
internal fun remoteLanTrustKey(account: RemoteAccount, entry: String): String {
    require(entry.length in 1..80)
    return "lan_" + remoteHash(
        "starlit-lan-trust/v1".toByteArray(Charsets.US_ASCII),
        account.server.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        account.userId.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        entry.toByteArray(Charsets.UTF_8),
    ).toLanHex()
}
private fun ByteArray.toLanHex(): String = joinToString("") { "%02x".format(it) }
private fun String.lanHexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private val lanJson = Json { ignoreUnknownKeys = true }
private val lanIdPattern = Regex("[0-9a-f]{32}")
private val lanProofPattern = Regex("[0-9a-f]{64}")

@Serializable private data class LanHostIdentity(val id: String = "")
@Serializable private data class LanProofHello(val id: String = "", val proof: String = "")
@Serializable private data class LanProofReply(val proof: String = "")
@Serializable private data class LanEnrollment(val id: String = "", val secret: String = "")
internal data class LanTrustResume(val desktopId: String?, val trusted: Boolean)

internal fun remoteLanTrustProof(trust: RemoteLanTrust, transcript: ByteArray, role: String): String {
    require(trust.valid() && transcript.size == 32 && role in listOf("phone", "desktop"))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(trust.secret.lanHexBytes(), "HmacSHA256"))
    mac.update("starlit-lan-resume/v1".toByteArray(Charsets.US_ASCII))
    mac.update(0)
    mac.update(role.toByteArray(Charsets.US_ASCII))
    mac.update(0)
    mac.update(transcript)
    return mac.doFinal().toLanHex()
}
private fun safeLanRead(store: RemoteLanTrustStore?, account: RemoteAccount, key: String): RemoteLanTrust? =
    runCatching { store?.read(account, key)?.takeIf(RemoteLanTrust::valid) }.getOrNull()

/** A stable random PC ID is a lookup hint, never authorization or an IP-based identity. */
private fun hostLanIdentity(account: RemoteAccount, store: RemoteLanTrustStore?): RemoteLanTrust? {
    if (store == null) return null
    return runCatching {
        store.read(account, "desktop-identity")?.takeIf(RemoteLanTrust::valid)
            ?: RemoteLanTrust(randomRemoteBytes().copyOf(16).toLanHex(), randomRemoteBytes().toLanHex())
                .also { store.write(account, "desktop-identity", it) }
    }.getOrNull()
}

/** Both peers must advertise the LAN extension before exchanging these frames. */
internal fun resumeLanTrust(
    pairing: RemotePairingChannel,
    account: RemoteAccount,
    host: Boolean,
    store: RemoteLanTrustStore?,
): LanTrustResume {
    check(pairing.supportsLanTrustedReconnect)
    val channel = pairing.channel
    if (host) {
        val identity = hostLanIdentity(account, store)
        channel.send(lanJson.encodeToString(LanHostIdentity(identity?.id.orEmpty())))
        val hello = lanJson.decodeFromString<LanProofHello>(channel.receive())
        val stored = hello.id.takeIf(lanIdPattern::matches)?.let { safeLanRead(store, account, "host:$it") }
        val valid = identity != null && stored != null && stored.id == hello.id &&
            hello.proof.matches(lanProofPattern) && MessageDigest.isEqual(
                hello.proof.lanHexBytes(),
                remoteLanTrustProof(stored, pairing.transcript, "phone").lanHexBytes(),
            )
        channel.send(lanJson.encodeToString(LanProofReply(
            if (valid) remoteLanTrustProof(stored, pairing.transcript, "desktop") else "",
        )))
        return LanTrustResume(identity?.id, valid)
    }

    val desktopId = lanJson.decodeFromString<LanHostIdentity>(channel.receive())
        .id.takeIf(lanIdPattern::matches)
    val stored = desktopId?.let { safeLanRead(store, account, "phone:$it") }
    channel.send(lanJson.encodeToString(LanProofHello(
        stored?.id.orEmpty(),
        stored?.let { remoteLanTrustProof(it, pairing.transcript, "phone") }.orEmpty(),
    )))
    val reply = lanJson.decodeFromString<LanProofReply>(channel.receive())
    val valid = stored != null && reply.proof.matches(lanProofPattern) &&
        MessageDigest.isEqual(
            reply.proof.lanHexBytes(),
            remoteLanTrustProof(stored, pairing.transcript, "desktop").lanHexBytes(),
        )
    return LanTrustResume(desktopId, valid)
}

/** Called ONLY after both code approvals and same-account HTTPS authorization. */
internal fun enrollLanTrust(
    channel: RemoteCipherChannel,
    account: RemoteAccount,
    host: Boolean,
    desktopId: String?,
    store: RemoteLanTrustStore?,
) {
    if (host) {
        val candidate = RemoteLanTrust(randomRemoteBytes().copyOf(16).toLanHex(), randomRemoteBytes().toLanHex())
        val saved = desktopId?.matches(lanIdPattern) == true && runCatching {
            requireNotNull(store).write(account, "host:" + candidate.id, candidate)
            true
        }.getOrDefault(false)
        // A phone must never accept a key which the PC failed to persist.
        channel.send(lanJson.encodeToString(
            if (saved) LanEnrollment(candidate.id, candidate.secret) else LanEnrollment(),
        ))
    } else {
        val payload = lanJson.decodeFromString<LanEnrollment>(channel.receive())
        val trust = RemoteLanTrust(payload.id, payload.secret)
        if (desktopId?.matches(lanIdPattern) == true && trust.valid()) {
            runCatching { store?.write(account, "phone:$desktopId", trust) }
        }
    }
}

