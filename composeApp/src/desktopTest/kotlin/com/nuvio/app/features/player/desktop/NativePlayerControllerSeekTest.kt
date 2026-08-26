package com.nuvio.app.features.player.desktop

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val failSeekTestNativeCreate: NativePlayerCreate = { _, _, _, _, _, _, _, _, _ ->
    error("native create must not run in seek unit tests")
}

class NativePlayerControllerSeekTest {
    @Test
    fun rejectsSeekUntilNativeHandleExists() {
        val seekCalls = AtomicInteger()
        val controller = createController { _, _ -> seekCalls.incrementAndGet() }

        assertFalse(controller.trySeekTo(15_000L))
        assertEquals(0, seekCalls.get())
    }

    @Test
    fun acceptsSeekAfterNativeHandleExists() {
        var receivedHandle = 0L
        var receivedPositionMs = 0L
        val controller = createController { handle, positionMs ->
            receivedHandle = handle
            receivedPositionMs = positionMs
        }
        controller.setSeekTestNativeHandle(42L)

        assertTrue(controller.trySeekTo(15_000L))
        assertEquals(42L, receivedHandle)
        assertEquals(15_000L, receivedPositionMs)
    }

    private fun createController(nativeSeekTo: (Long, Long) -> Unit) = NativePlayerController(
        host = NativePlayerHost(),
        nativeCreate = failSeekTestNativeCreate,
        nativeDispose = {},
        nativeSeekTo = nativeSeekTo,
    )
}

private fun NativePlayerController.setSeekTestNativeHandle(value: Long) {
    javaClass.getDeclaredField("handle").also { field ->
        field.isAccessible = true
        field.setLong(this, value)
    }
}
