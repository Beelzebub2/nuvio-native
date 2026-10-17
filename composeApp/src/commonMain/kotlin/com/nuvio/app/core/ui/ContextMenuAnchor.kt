package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Hand-off for desktop context menus whose trigger (a card deep in a list) and host (the
 * screen that owns the actions) are far apart: the card records the pointer position in
 * root coordinates just before it calls its long-press/secondary callback, and the host
 * consumes it to place a [NuvioContextMenu] instead of opening a bottom sheet.
 */
object NuvioContextMenuAnchorHolder {
    private var pending: Offset? by mutableStateOf(null)

    fun set(anchorInRoot: Offset) {
        pending = anchorInRoot
    }

    fun consume(): Offset? = pending.also { pending = null }
}

/** Right-click opens [onOpen]; the pointer position is recorded for the menu first. */
internal fun Modifier.contextMenuTrigger(onOpen: (() -> Unit)?): Modifier =
    if (onOpen == null) this else composed {
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        onGloballyPositioned { coordinates = it }
            .secondaryClickAt { local ->
                coordinates?.takeIf { it.isAttached }?.let { NuvioContextMenuAnchorHolder.set(it.localToRoot(local)) }
                onOpen()
            }
    }

/** Records the bottom-start corner of an element (a ⋯ button) as the menu anchor. */
internal fun LayoutCoordinates.bottomStartInRoot(): Offset = localToRoot(Offset(0f, size.height.toFloat()))
