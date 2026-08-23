package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxPlaybackCompletionTest {
    @Test
    fun ordinaryPlayDoesNotSeek() {
        val completion = LinuxPlaybackCompletion()
        val commands = mutableListOf<String>()

        completion.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("play"), commands)
    }

    @Test
    fun playAfterEndSeeksToStartBeforePlaying() {
        val completion = LinuxPlaybackCompletion()
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        completion.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("seek:0", "play"), commands)
    }

    @Test
    fun explicitResetPreventsStaleEndFromRewindingLaterPlay() {
        val completion = LinuxPlaybackCompletion()
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        completion.reset()
        completion.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("play"), commands)
    }

    @Test
    fun endMarkerIsConsumedByOnlyOnePlay() {
        val completion = LinuxPlaybackCompletion()
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        repeat(2) {
            completion.resume(
                seekToStart = { commands += "seek:0" },
                play = { commands += "play" },
            )
        }

        assertEquals(listOf("seek:0", "play", "play"), commands)
    }

    @Test
    fun staleEndFromPreviousGenerationCannotRewindCurrentPlayback() {
        val completion = LinuxPlaybackCompletion()
        val staleGeneration = completion.captureGeneration()
        val commands = mutableListOf<String>()

        completion.reset()
        val accepted = completion.markEnded(staleGeneration)
        completion.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(false, accepted)
        assertEquals(listOf("play"), commands)
    }

    @Test
    fun staleGenerationCannotConsumeNativeEndSignal() {
        val completion = LinuxPlaybackCompletion()
        val staleGeneration = completion.captureGeneration()
        var nativeConsumeCalls = 0

        completion.reset()
        val ended =
            completion.consumeEndIfCurrent(staleGeneration) {
                nativeConsumeCalls += 1
                true
            }

        assertEquals(false, ended)
        assertEquals(0, nativeConsumeCalls)
    }

    @Test
    fun replaySupersedesInFlightEndFinalizer() {
        val completion = LinuxPlaybackCompletion()
        val endedGeneration = completion.captureGeneration()
        var finalizerCalls = 0

        completion.markEnded(endedGeneration)
        completion.resume(seekToStart = {}, play = {})
        val accepted =
            completion.runIfCurrent(endedGeneration) {
                finalizerCalls += 1
            }

        assertEquals(false, accepted)
        assertEquals(0, finalizerCalls)
    }

    @Test
    fun staleGenerationCannotRunCompletionSideEffects() {
        val completion = LinuxPlaybackCompletion()
        val staleGeneration = completion.captureGeneration()
        var sideEffectCalls = 0

        completion.reset()
        val accepted =
            completion.runIfCurrent(staleGeneration) {
                sideEffectCalls += 1
            }

        assertEquals(false, accepted)
        assertEquals(0, sideEffectCalls)
    }
}
