package com.nuvio.app.features.addons

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddonResponseLimitTest {
    @Test
    fun acceptsAResponseAtTheMaximumSize() {
        val content = "12345678".toByteArray()
        assertContentEquals(content, readAddonTextBytes(ByteArrayInputStream(content), maxBytes = 8))
    }

    @Test
    fun rejectsAnOversizedResponseWithoutReadingTheRest() {
        val bytes = "12345678901234567890".toByteArray()
        val stream = ByteArrayInputStream(bytes)
        assertFailsWith<IllegalStateException> {
            readAddonTextBytes(stream, maxBytes = 8)
        }
        assertEquals(bytes.size - 9, stream.available())
    }

    @Test
    fun rejectsAnUnboundedChunkedStream() {
        var readCount = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                readCount++
                return 'x'.code
            }
        }
        assertFailsWith<IllegalStateException> {
            readAddonTextBytes(stream, maxBytes = 32)
        }
        assertEquals(33, readCount)
    }
}
