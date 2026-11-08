package com.nuvio.app.features.remote

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal const val REMOTE_PROTOCOL = "starlit-remote/1"
internal const val MAX_REMOTE_FRAME = 1_048_576
private val random = SecureRandom()
internal fun randomRemoteBytes() = ByteArray(32).also(random::nextBytes)
// Backward-compatible capability markers, inside otherwise random 32-byte contributions.
// Old peers ignore them and use the original confirmation flow. Both markers must be
// present before either peer exchanges any resume messages.
private val TRUSTED_HOST_MARKER = "STH2!".toByteArray(Charsets.US_ASCII)
private val TRUSTED_PHONE_MARKER = "STP2!".toByteArray(Charsets.US_ASCII)
// Separate new LAN marker preserves compatibility with Bluetooth-only v2 clients.
private val LAN_HOST_MARKER = "LNH1!".toByteArray(Charsets.US_ASCII)
private val LAN_PHONE_MARKER = "LNP1!".toByteArray(Charsets.US_ASCII)
internal fun remoteHash(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    update(REMOTE_PROTOCOL.toByteArray(Charsets.UTF_8))
    values.forEach { update(it) }
    digest()
}

internal fun DataOutputStream.writeFrame(bytes: ByteArray) {
    require(bytes.size in 1..MAX_REMOTE_FRAME)
    writeInt(bytes.size)
    write(bytes)
    flush()
}

internal fun DataInputStream.readFrame(maximum: Int = MAX_REMOTE_FRAME): ByteArray {
    val length = readInt()
    require(length in 1..maximum) { "Invalid remote frame" }
    return ByteArray(length).also(::readFully)
}

internal fun remoteRsaKeys() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
private fun rsaCipher(mode: Int, key: java.security.Key) = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
    init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
}
internal fun encryptRemoteSecret(publicKey: ByteArray, secret: ByteArray): ByteArray {
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
    require((key as java.security.interfaces.RSAPublicKey).modulus.bitLength() == 2048)
    return rsaCipher(Cipher.ENCRYPT_MODE, key).doFinal(secret)
}
internal fun decryptRemoteSecret(key: java.security.PrivateKey, encrypted: ByteArray): ByteArray =
    rsaCipher(Cipher.DECRYPT_MODE, key).doFinal(encrypted).also { require(it.size == 32) }

internal fun remoteTranscript(key: ByteArray, commitment: ByteArray, encrypted: ByteArray, nonce: ByteArray, secret: ByteArray): ByteArray {
    require(nonce.size == 32 && secret.size == 32 && MessageDigest.isEqual(commitment, remoteHash(key, nonce))) {
        "Desktop pairing commitment changed"
    }
    return remoteHash(key, commitment, encrypted, nonce, secret)
}

internal fun remotePairingCode(transcript: ByteArray): String =
    ((ByteBuffer.wrap(transcript).int.toLong() and 0xffffffffL) % 1_000_000).toString().padStart(6, '0')

internal data class RemotePairingChannel(
    val channel: RemoteCipherChannel,
    val code: String,
    val transcript: ByteArray = byteArrayOf(),
    val supportsTrustedReconnect: Boolean = false,
    val supportsLanTrustedReconnect: Boolean = false,
)

internal fun openRemoteChannel(socket: Socket, host: Boolean): RemotePairingChannel =
    openRemoteChannel(socket.getInputStream(), socket.getOutputStream(), host)

/** Identical encrypted protocol over LAN sockets or Bluetooth RFCOMM streams. */
internal fun openRemoteChannel(source: InputStream, destination: OutputStream, host: Boolean): RemotePairingChannel {
    val input = DataInputStream(source)
    val output = DataOutputStream(destination)
    var trustedReconnectSupported = false
    var lanReconnectSupported = false
    val transcript = if (host) {
        val keys = remoteRsaKeys()
        val publicKey = keys.public.encoded
        val nonce = randomRemoteBytes().also {
            TRUSTED_HOST_MARKER.copyInto(it)
            LAN_HOST_MARKER.copyInto(it, TRUSTED_HOST_MARKER.size)
        }
        val commitment = remoteHash(publicKey, nonce)
        output.writeUTF(REMOTE_PROTOCOL)
        output.writeFrame(publicKey)
        output.writeFrame(commitment)
        // Both contributions are fixed before the desktop reveals its committed random nonce.
        val encrypted = input.readFrame(256).also { require(it.size == 256) }
        val secret = decryptRemoteSecret(keys.private, encrypted)
        trustedReconnectSupported = secret.copyOfRange(0, TRUSTED_PHONE_MARKER.size)
            .contentEquals(TRUSTED_PHONE_MARKER)
        lanReconnectSupported = secret.copyOfRange(
            TRUSTED_PHONE_MARKER.size, TRUSTED_PHONE_MARKER.size + LAN_PHONE_MARKER.size,
        ).contentEquals(LAN_PHONE_MARKER)
        output.writeFrame(nonce)
        remoteTranscript(publicKey, commitment, encrypted, nonce, secret)
    } else {
        require(input.readUTF() == REMOTE_PROTOCOL)
        val publicKey = input.readFrame(512)
        val commitment = input.readFrame(32).also { require(it.size == 32) }
        val secret = randomRemoteBytes().also {
            TRUSTED_PHONE_MARKER.copyInto(it)
            LAN_PHONE_MARKER.copyInto(it, TRUSTED_PHONE_MARKER.size)
        }
        val encrypted = encryptRemoteSecret(publicKey, secret)
        output.writeFrame(encrypted)
        val nonce = input.readFrame(32)
        trustedReconnectSupported = nonce.copyOfRange(0, TRUSTED_HOST_MARKER.size)
            .contentEquals(TRUSTED_HOST_MARKER)
        lanReconnectSupported = nonce.copyOfRange(
            TRUSTED_HOST_MARKER.size, TRUSTED_HOST_MARKER.size + LAN_HOST_MARKER.size,
        ).contentEquals(LAN_HOST_MARKER)
        remoteTranscript(publicKey, commitment, encrypted, nonce, secret)
    }
    return RemotePairingChannel(
        RemoteCipherChannel(input, output, transcript, host),
        remotePairingCode(transcript),
        transcript,
        trustedReconnectSupported,
        trustedReconnectSupported && lanReconnectSupported,
    )
}

/** Directional keys and strict counters prevent reflection, nonce reuse and replay. */
internal class RemoteCipherChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    transcript: ByteArray,
    host: Boolean,
) {
    private val sendKey = SecretKeySpec(remoteHash(transcript, (if (host) "desktop" else "phone").toByteArray()), "AES")
    private val receiveKey = SecretKeySpec(remoteHash(transcript, (if (host) "phone" else "desktop").toByteArray()), "AES")
    private var sent = 0L
    private var received = 0L

    fun send(value: String) {
        val counter = sent++
        val cipher = cipher(Cipher.ENCRYPT_MODE, sendKey, counter)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        output.writeLong(counter)
        output.writeFrame(encrypted)
    }

    fun receive(): String {
        val counter = input.readLong()
        require(counter == received++) { "Remote replay or out-of-order frame" }
        return cipher(Cipher.DECRYPT_MODE, receiveKey, counter).doFinal(input.readFrame()).toString(Charsets.UTF_8)
    }

    private fun cipher(mode: Int, key: SecretKeySpec, counter: Long): Cipher {
        require(counter >= 0)
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(REMOTE_PROTOCOL.toByteArray(Charsets.UTF_8))
        }
    }
}
