package com.nuvio.app.features.trailer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.io.BufferedSeekableInput
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private const val TrailerRangeWindowRadiusBytes = 2 * 1024 * 1024

private val trailerRangeHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal fun createTrailerMediaData(sourceUrl: String, role: String): MediaData =
    if (sourceUrl.requiresBoundedRanges()) {
        TrailerHttpRangeMediaData(sourceUrl, role)
    } else {
        UriMediaData(
            uri = sourceUrl,
            headers = mapOf(
                "User-Agent" to TrailerExtractionPlatform.defaultHeaders.getValue("user-agent"),
            ),
        )
    }

internal fun String.requiresBoundedRanges(): Boolean =
    toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") == true

@OptIn(ExperimentalMediampApi::class)
internal class TrailerHttpRangeMediaData(
    private val sourceUrl: String,
    private val role: String,
) : SeekableInputMediaData {
    private val sourceSize by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        resolveSourceSize()
    }

    override val uri: String = "trailer-$role-${sourceUrl.hashCode().toUInt().toString(16)}"
    override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY
    override val options: List<String> = emptyList()

    override fun fileLength(): Long = sourceSize

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
        withContext(coroutineContext + Dispatchers.IO) {
            coroutineContext.ensureActive()
            TrailerExtractionPlatform.diagnostic(
                "range source open role=$role size=$sourceSize ${TrailerExtractionPlatform.describeUrl(sourceUrl)}",
            )
            TrailerHttpRangeSeekableInput(sourceUrl, role, sourceSize)
        }

    override fun close() = Unit

    private fun resolveSourceSize(): Long {
        sourceUrl.toHttpUrlOrNull()
            ?.queryParameter("clen")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }

        val request = Request.Builder()
            .url(sourceUrl)
            .applyTrailerHeaders()
            .header("Range", "bytes=0-0")
            .get()
            .build()

        trailerRangeHttpClient.newCall(request).execute().use { response ->
            val contentRange = response.header("Content-Range")
            val size = contentRange
                ?.substringAfterLast('/', missingDelimiterValue = "")
                ?.toLongOrNull()
                ?: response.body?.contentLength()?.takeIf { response.code == 200 }
            if (!response.isSuccessful || size == null || size <= 0L) {
                throw IOException(
                    "Unable to resolve trailer source size: status=${response.code} contentRange=$contentRange",
                )
            }
            return size
        }
    }
}

private class TrailerHttpRangeSeekableInput(
    private val sourceUrl: String,
    private val role: String,
    override val size: Long,
) : BufferedSeekableInput(TrailerRangeWindowRadiusBytes) {
    override fun fillBuffer() {
        val readStart = (position - TrailerRangeWindowRadiusBytes).coerceAtLeast(0L)
        val readEnd = (readStart + TrailerRangeWindowRadiusBytes * 2L).coerceAtMost(size)
        fillBufferRange(readStart, readEnd)
    }

    override fun readFileToBuffer(fileOffset: Long, bufferOffset: Int, length: Int): Int {
        if (length == 0) return 0

        val rangeEnd = fileOffset + length - 1L
        val request = Request.Builder()
            .url(sourceUrl)
            .applyTrailerHeaders()
            .header("Range", "bytes=$fileOffset-$rangeEnd")
            .get()
            .build()

        return try {
            trailerRangeHttpClient.newCall(request).execute().use { response ->
                val contentRange = response.header("Content-Range")
                TrailerExtractionPlatform.diagnostic(
                    "range response role=$role requested=$fileOffset-$rangeEnd status=${response.code} " +
                        "contentRange=${contentRange ?: "none"}",
                )
                if (response.code != 206) {
                    throw IOException(
                        "Trailer range request failed: status=${response.code} requested=$fileOffset-$rangeEnd",
                    )
                }
                val body = response.body ?: throw IOException("Trailer range response has no body")
                var totalRead = 0
                body.byteStream().use { input ->
                    while (totalRead < length) {
                        val read = input.read(buf, bufferOffset + totalRead, length - totalRead)
                        if (read < 0) break
                        totalRead += read
                    }
                }
                totalRead
            }
        } catch (error: Throwable) {
            TrailerExtractionPlatform.diagnostic(
                "range failed role=$role requested=$fileOffset-$rangeEnd " +
                    "error=${error::class.simpleName} detail=$error",
            )
            throw error
        }
    }
}

private fun Request.Builder.applyTrailerHeaders(): Request.Builder {
    TrailerExtractionPlatform.defaultHeaders.forEach { (name, value) ->
        header(name, value)
    }
    header("Accept-Encoding", "identity")
    return this
}
