package com.nuvio.app.core.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.event.MouseWheelEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal actual fun Modifier.nuvioSmoothWheelScroll(state: ScrollableState): Modifier =
    pointerInput(state) {
        coroutineScope {
            val glide = SmoothWheelGlide(state, this)
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Scroll || !event.isEasedWheelTurn()) continue
                    val notches = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                    val viewport = size.height.toFloat()
                    if (notches == 0f || viewport <= 0f) continue
                    event.changes.forEach { it.consume() }
                    glide.add(
                        distancePx = notches * wheelNotchPx(event, viewport),
                        maxAheadPx = viewport * MaxAheadViewports,
                    )
                }
            }
        }
    }

/**
 * Plays wheel input as one eased glide. Each turn adds to the distance still to travel, and every
 * frame covers a share of what remains, so motion starts at once and settles softly; turning
 * the wheel again mid-glide keeps it moving instead of restarting it.
 */
internal class SmoothWheelGlide(
    private val state: ScrollableState,
    private val scope: CoroutineScope,
) {
    private var remaining = 0f
    private var job: Job? = null

    fun add(distancePx: Float, maxAheadPx: Float) {
        remaining = if (remaining == 0f || (remaining > 0f) == (distancePx > 0f)) {
            (remaining + distancePx).coerceIn(-maxAheadPx, maxAheadPx)
        } else {
            // Reversing drops what was left of the old direction.
            distancePx
        }
        if (job?.isActive != true) job = scope.launch { glide() }
    }

    private suspend fun glide() {
        while (abs(remaining) >= SettledPx) {
            try {
                state.scroll(MutatePriority.UserInput) {
                    var lastFrameNanos = 0L
                    while (abs(remaining) >= SettledPx) {
                        val frameNanos = withFrameNanos { it }
                        val elapsedMs = if (lastFrameNanos == 0L) {
                            FirstFrameMs
                        } else {
                            ((frameNanos - lastFrameNanos) / 1_000_000f).coerceIn(1f, LongestFrameMs)
                        }
                        lastFrameNanos = frameNanos
                        val step = if (abs(remaining) <= FinishPx) {
                            remaining
                        } else {
                            remaining * (1f - exp(-elapsedMs / GlideTimeConstantMs))
                        }
                        val moved = scrollBy(step)
                        remaining -= step
                        // The list ran out of room at the top or bottom.
                        if (abs(moved) < abs(step) - 0.5f) remaining = 0f
                    }
                }
            } catch (interrupted: CancellationException) {
                // Another scroll (a drag, the scrollbar, scroll-to-top) took over: drop the rest.
                remaining = 0f
                currentCoroutineContext().ensureActive()
            }
        }
        remaining = 0f
    }
}

/** Mouse wheels only: touchpads, page-scroll wheels, Shift (sideways) and Ctrl keep their usual handling. */
private fun PointerEvent.isEasedWheelTurn(): Boolean {
    if (changes.any { it.isConsumed }) return false
    if (keyboardModifiers.isShiftPressed || keyboardModifiers.isCtrlPressed || keyboardModifiers.isMetaPressed) return false
    val delta = changes.fold(0f to 0f) { (x, y), change -> (x + change.scrollDelta.x) to (y + change.scrollDelta.y) }
    if (abs(delta.second) <= abs(delta.first)) return false
    val wheel = awtEventOrNull as? MouseWheelEvent ?: return true
    if (wheel.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) return false
    // Touchpads report fractions of a notch; they already scroll smoothly.
    return abs(wheel.preciseWheelRotation - wheel.wheelRotation) < 0.001
}

/** Distance one notch moves, matching Compose's own wheel handling on each system. */
private fun Density.wheelNotchPx(event: PointerEvent, viewportPx: Float): Float {
    val lines = ((event.awtEventOrNull as? MouseWheelEvent)?.scrollAmount ?: 1).coerceAtLeast(1).toFloat()
    return when {
        osName.contains("win") -> viewportPx / 20f
        osName.contains("mac") -> 10.dp.toPx() * lines
        else -> sqrt(viewportPx) * lines
    }
}

private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

/** Each frame covers 1 - e^(-t/τ) of what is left: about 95% of a notch within 3τ. */
private const val GlideTimeConstantMs = 70f
private const val FirstFrameMs = 16f
private const val LongestFrameMs = 48f
private const val FinishPx = 1f
private const val SettledPx = 0.5f
/** Fast spins can run at most this many screens ahead of the list. */
private const val MaxAheadViewports = 3f
