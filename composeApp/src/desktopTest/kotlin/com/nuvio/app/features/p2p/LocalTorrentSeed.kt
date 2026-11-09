package com.nuvio.app.features.p2p

import com.sun.net.httpserver.HttpServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A generated, private test torrent with one loopback tracker and one real BT wire peer. */
internal class LocalTorrentSeed(private val payload: ByteArray) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { Thread(it, "local-torrent-seed").apply { isDaemon = true } }
    private val peer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
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
            val compactPeer = byteArrayOf(127, 0, 0, 1, (peer.localPort shr 8).toByte(), peer.localPort.toByte())
            val response = "d8:intervali60e5:peers".toByteArray() + bencode(compactPeer) + "e".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        http.start()
        executor.execute {
            while (!peer.isClosed) {
                val socket = try { peer.accept() } catch (_: Exception) { break }
                sockets += socket
                executor.execute {
                    try { serve(socket, hash) }
                    catch (error: IllegalArgumentException) { failure.compareAndSet(null, error) }
                    catch (_: java.io.IOException) { /* Reader disconnects and fixture shutdown close sockets. */ }
                    finally { sockets -= socket }
                }
            }
        }
    }

    private fun serve(socket: Socket, hash: ByteArray) = socket.use {
        socket.soTimeout = 15_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val handshake = ByteArray(68).also(input::readFully)
        require(handshake[0] == 19.toByte() && handshake.copyOfRange(28, 48).contentEquals(hash))
        output.writeByte(19)
        output.writeBytes("BitTorrent protocol")
        output.write(ByteArray(8))
        output.write(hash)
        output.writeBytes("-NVTEST-012345678901")
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
            require(length in 1..65536)
            val message = ByteArray(length).also(input::readFully)
            if (message[0] != 6.toByte()) continue
            require(length == 13)
            val request = DataInputStream(message.inputStream().apply { skip(1) })
            val piece = request.readInt()
            val begin = request.readInt()
            val count = request.readInt()
            val offset = piece.toLong() * pieceLength + begin
            require(piece in 0 until pieceCount && begin >= 0 && count in 1..16384 &&
                begin + count <= pieceLength && offset + count <= payload.size)
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
        peer.close()
        sockets.forEach { runCatching { it.close() } }
        http.stop(0)
        executor.shutdownNow()
    }

    private fun bencode(value: String) = bencode(value.toByteArray())
    private fun bencode(value: ByteArray) = "${value.size}:".toByteArray() + value
}
