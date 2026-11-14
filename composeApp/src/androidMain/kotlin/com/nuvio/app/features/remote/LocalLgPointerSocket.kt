package com.nuvio.app.features.remote

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * webOS sometimes returns an unencrypted ws://3000 pointer channel even when its
 * SSAP pairing channel used TLS. Open only that TV-provided local endpoint, rather
 * than weakening Android's global cleartext-network policy for the entire app.
 */
internal class LocalLgPointerSocket(private val uri: URI) {
    private var socket: Socket? = null
    private val random = SecureRandom()

    fun open() {
        require(uri.scheme == "ws" && uri.port == 3000 &&
            isPrivateSmartTvAddress(uri.host ?: "") && uri.userInfo == null)
        val connection = Socket()
        try {
            connection.connect(InetSocketAddress(uri.host, uri.port), 3000)
            connection.soTimeout = 4000
            val nonce = ByteArray(16).also(random::nextBytes)
            val key = Base64.encodeToString(nonce, Base64.NO_WRAP)
            val path = uri.rawPath.orEmpty().ifBlank { "/" } +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            connection.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            // Read individual lines without a BufferedReader that might consume
            // subsequent WebSocket frames while parsing HTTP headers.
            val headers = mutableListOf<String>()
            val input = connection.getInputStream()
            var line = StringBuilder()
            while (headers.size < 48) {
                val b = input.read()
                require(b >= 0) { "TV closed the pointer handshake" }
                if (b == 10) {
                    val value = line.toString().trimEnd('\r')
                    if (value.isEmpty()) break
                    headers.add(value)
                    line = StringBuilder()
                } else {
                    require(line.length < 2048) { "Invalid TV pointer headers" }
                    line.append(b.toChar())
                }
            }
            require(headers.firstOrNull()?.startsWith("HTTP/1.1 101 ") == true) {
                "TV rejected pointer WebSocket upgrade"
            }
            val accept = headers.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
                ?.substringAfter(':')?.trim().orEmpty()
            val expected = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                Base64.NO_WRAP)
            require(accept == expected) { "TV pointer handshake was not authenticated" }
            connection.soTimeout = 0
            socket = connection
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    @Synchronized fun send(text: String) {
        val output = socket?.getOutputStream() ?: throw IllegalStateException("TV pointer disconnected")
        val data = text.toByteArray(Charsets.UTF_8)
        require(data.size < 126) { "Pointer command is too long" }
        val mask = ByteArray(4).also(random::nextBytes)
        val frame = ByteArray(data.size + 6)
        frame[0] = 0x81.toByte()
        frame[1] = (0x80 or data.size).toByte()
        mask.copyInto(frame, 2)
        for (i in data.indices) frame[i + 6] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        output.write(frame)
        output.flush()
    }

    @Synchronized fun close() {
        socket?.close()
        socket = null
    }
}
