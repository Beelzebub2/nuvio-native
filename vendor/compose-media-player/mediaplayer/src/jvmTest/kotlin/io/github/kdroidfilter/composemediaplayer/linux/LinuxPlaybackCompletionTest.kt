package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import kotlin.concurrent.thread

class LinuxPlaybackCompletionTest {
    @Test
    fun ordinaryPlayDoesNotSeek() {
        val completion = LinuxPlaybackCompletion()
        val commands = mutableListOf<String>()

        executePlaybackResume(
            replayFromEnd = completion.resumeFromEnd(),
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
        executePlaybackResume(
            replayFromEnd = completion.resumeFromEnd(),
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("seek:0", "play"), commands)
    }

    @Test
    fun explicitResetPreventsStaleEndFromRewindingLaterPlay() {
        val completion = LinuxPlaybackCompletion()

        completion.markEnded(completion.captureGeneration())
        completion.reset()
        assertEquals(false, completion.resumeFromEnd())
    }

    @Test
    fun endMarkerIsConsumedByOnlyOnePlay() {
        val completion = LinuxPlaybackCompletion()

        completion.markEnded(completion.captureGeneration())
        assertEquals(listOf(true, false), List(2) { completion.resumeFromEnd() })
    }

    @Test
    fun staleEndFromPreviousGenerationCannotRewindCurrentPlayback() {
        val completion = LinuxPlaybackCompletion()
        val staleGeneration = completion.captureGeneration()

        completion.reset()
        val accepted = completion.markEnded(staleGeneration)

        assertEquals(false, accepted)
        assertEquals(false, completion.resumeFromEnd())
    }

    @Test
    fun staleGenerationCannotConsumeNativeEndSignal() {
        val completion = LinuxPlaybackCompletion()
        val staleGeneration = completion.captureGeneration()
        var nativeConsumeCalls = 0

        completion.reset()
        val ended =
            completion.markEndedIfConsumed(staleGeneration) {
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
        completion.resumeFromEnd()
        val accepted =
            completion.runIfCurrent(endedGeneration) {
                finalizerCalls += 1
            }

        assertEquals(false, accepted)
        assertEquals(0, finalizerCalls)
    }

    @Test
    fun nativeEndConsumptionAndMarkerPublicationAreAtomicWithReplay() {
        val completion = LinuxPlaybackCompletion()
        val generation = completion.captureGeneration()
        val consumeEntered = CountDownLatch(1)
        val allowConsume = CountDownLatch(1)
        val replayReturned = CountDownLatch(1)
        var endAccepted = false
        var replayFromEnd = false

        val endThread = thread(isDaemon = true) {
            endAccepted = completion.markEndedIfConsumed(generation) {
                consumeEntered.countDown()
                allowConsume.await()
                true
            }
        }
        assertTrue(consumeEntered.await(2, TimeUnit.SECONDS))

        val replayThread = thread(isDaemon = true) {
            replayFromEnd = completion.resumeFromEnd()
            replayReturned.countDown()
        }
        assertFalse(replayReturned.await(100, TimeUnit.MILLISECONDS))

        allowConsume.countDown()
        endThread.join(2_000)
        replayThread.join(2_000)

        assertFalse(endThread.isAlive)
        assertFalse(replayThread.isAlive)
        assertTrue(endAccepted)
        assertTrue(replayFromEnd)
    }

    @Test
    fun concurrentPlayCannotPassReplaySeek() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val seekEntered = CountDownLatch(1)
        val allowSeek = CountDownLatch(1)
        val secondPlayReturned = CountDownLatch(1)

        completion.markEnded(completion.captureGeneration())
        val replayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = {
                    commands += "seek:0"
                    seekEntered.countDown()
                    allowSeek.await()
                },
                play = { commands += "play:replay" },
            )
        }
        assertTrue(seekEntered.await(2, TimeUnit.SECONDS))

        val ordinaryPlayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = { commands += "unexpected-seek" },
                play = { commands += "play:ordinary" },
            )
            secondPlayReturned.countDown()
        }
        assertFalse(secondPlayReturned.await(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf("seek:0"), commands.toList())

        allowSeek.countDown()
        replayThread.join(2_000)
        ordinaryPlayThread.join(2_000)

        assertFalse(replayThread.isAlive)
        assertFalse(ordinaryPlayThread.isAlive)
        assertEquals(listOf("seek:0", "play:replay", "play:ordinary"), commands.toList())
    }

    @Test
    fun explicitResetAndSeekCannotSplitReplayCommands() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val replaySeekEntered = CountDownLatch(1)
        val allowReplaySeek = CountDownLatch(1)
        val explicitSeekReturned = CountDownLatch(1)

        completion.markEnded(completion.captureGeneration())
        val replayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = {
                    commands += "seek:0"
                    replaySeekEntered.countDown()
                    allowReplaySeek.await()
                },
                play = { commands += "play:replay" },
            )
        }
        assertTrue(replaySeekEntered.await(2, TimeUnit.SECONDS))

        val explicitSeekThread = thread(isDaemon = true) {
            coordinator.resetAndRun { commands += "seek:explicit" }
            explicitSeekReturned.countDown()
        }
        assertFalse(explicitSeekReturned.await(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf("seek:0"), commands.toList())

        allowReplaySeek.countDown()
        replayThread.join(2_000)
        explicitSeekThread.join(2_000)

        assertFalse(replayThread.isAlive)
        assertFalse(explicitSeekThread.isAlive)
        assertEquals(listOf("seek:0", "play:replay", "seek:explicit"), commands.toList())
        assertFalse(completion.resumeFromEnd())
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
