package com.nuvio.app.features.p2p

import com.sun.net.httpserver.HttpServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** A generated, private test torrent with one loopback tracker and one real BT wire peer. */
internal class LocalTorrentSeed(private val payload: ByteArray) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { Thread(it, "local-torrent-seed").apply { isDaemon = true } }
    private val connected = AtomicBoolean()
    private val sockets = CopyOnWriteArrayList<Socket>()
    val blocksServed = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
    private val pieceLength = 256 * 1024
    private val pieceCount = (payload.size + pieceLength - 1) / pieceLength
    val torrentUrl = "http://127.0.0.1:${http.address.port}/fixture.torrent"

    init {
        val pieces = (payload.indices step pieceLength).flatMap { offset ->
            MessageDigest.getInstance("SHA-1")
                .digest(payload.copyOfRange(offset, minOf(offset + pieceLength, payload.size))).asList()
        }.toByteArray()
        val info = "d6:lengthi${payload.size}e4:name".toByteArray() + bencode("fixture.mp4") +
            "12:piece lengthi${pieceLength}e6:pieces".toByteArray() + bencode(pieces) + "7:privatei1ee".toByteArray()
        val hash = MessageDigest.getInstance("SHA-1").digest(info)
        val torrent = "d8:announce".toByteArray() + bencode("http://127.0.0.1:${http.address.port}/announce") +
            "4:info".toByteArray() + info + "e".toByteArray()
        http.createContext("/fixture.torrent") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/x-bittorrent")
            exchange.sendResponseHeaders(200, torrent.size.toLong())
            exchange.responseBody.use { it.write(torrent) }
        }
        http.createContext("/announce") { exchange ->
            val port = exchange.requestURI.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("port=") }?.substringAfter('=')?.toIntOrNull()
            val response = "d8:intervali60e5:peers0:e".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            if (connected.compareAndSet(false, true)) {
                executor.execute {
                    val socket = Socket()
                    sockets += socket
                    try {
                        require(port != null && port in 1..65535) { "Tracker announce has no valid peer port" }
                        // The bundled client initiates MSE and will not fall back after
                        // an unsupported encrypted handshake. Instead our test seed
                        // initiates plaintext; ForceEncrypt=false accepts that normally.
                        socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                        serve(socket, hash)
                    }
                    catch (error: IllegalArgumentException) { failure.compareAndSet(null, error) }
                    catch (error: java.io.IOException) {
                        if (blocksServed.get() == 0) failure.compareAndSet(null, error)
                    }
                    finally { socket.close(); sockets -= socket }
                }
            }
        }
        http.start()
    }

    private fun serve(socket: Socket, hash: ByteArray) = socket.use {
        socket.soTimeout = 15_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        output.writeByte(19)
        output.writeBytes("BitTorrent protocol")
        output.write(ByteArray(8))
        output.write(hash)
        output.writeBytes("-NVTEST-012345678901")
        output.flush()
        val handshake = ByteArray(68).also(input::readFully)
        require(handshake[0] == 19.toByte() && handshake.copyOfRange(28, 48).contentEquals(hash)) {
            "Unexpected peer handshake prefix=${handshake.take(8).joinToString("") { "%02x".format(it) }}"
        }
        output.writeInt(1 + (pieceCount + 7) / 8)
        output.writeByte(5) // bitfield
        repeat((pieceCount + 7) / 8) { byteIndex ->
            val available = minOf(8, pieceCount - byteIndex * 8)
            output.writeByte((0xff shl (8 - available)) and 0xff)
        }
        output.writeInt(1)
        output.writeByte(1) // unchoke
        output.flush()
        while (!socket.isClosed) {
            val length = input.readInt()
            if (length == 0) continue
            require(length in 1..65536) { "Unexpected peer message length=$length" }
            val message = ByteArray(length).also(input::readFully)
            if (message[0] != 6.toByte()) continue
            require(length == 13) { "Unexpected block request message length=$length" }
            val request = DataInputStream(message.inputStream().apply { skip(1) })
            val piece = request.readInt()
            val begin = request.readInt()
            val count = request.readInt()
            val offset = piece.toLong() * pieceLength + begin
            require(piece in 0 until pieceCount && begin >= 0 && count in 1..16384 &&
                begin + count <= pieceLength && offset + count <= payload.size) {
                "Unexpected block request piece=$piece begin=$begin count=$count payload=${payload.size}"
            }
            output.writeInt(9 + count)
            output.writeByte(7) // piece
            output.writeInt(piece)
            output.writeInt(begin)
            output.write(payload, offset.toInt(), count)
            output.flush()
            blocksServed.incrementAndGet()
        }
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        http.stop(0)
        executor.shutdownNow()
    }

    private fun bencode(value: String) = bencode(value.toByteArray())
    private fun bencode(value: ByteArray) = "${value.size}:".toByteArray() + value
}
