package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.middleClick(onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this
    return pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.button == PointerButton.Tertiary &&
                    event.changes.none { it.isConsumed }) {
                    event.changes.forEach { it.consume() }
                    onClick()
                }
            }
        }
    }
}
