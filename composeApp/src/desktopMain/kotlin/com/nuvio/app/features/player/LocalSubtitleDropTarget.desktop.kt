package com.nuvio.app.features.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * Accept only OS-provided file lists, never arbitrary text or remote URLs.
 * Both picker and drop use the same canonical-file, extension and size checks.
 */
internal fun validateDroppedLocalSubtitle(transferable: Transferable): LocalSubtitleFileResult? {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    return runCatching {
        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            ?: return@runCatching LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNREADABLE)
        when {
            files.size != 1 -> LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.MULTIPLE_FILES)
            files[0] !is File -> LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNREADABLE)
            else -> validateLocalSubtitleFile(files[0] as File)
        }
    }.getOrElse { LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNREADABLE) }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.localSubtitleDropTarget(
    enabled: Boolean,
    onResult: (LocalSubtitleFileResult) -> Unit,
): Modifier {
    val currentOnResult = rememberUpdatedState(onResult)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val result = validateDroppedLocalSubtitle(event.awtTransferable) ?: return false
                currentOnResult.value(result)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            enabled && runCatching {
                event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }.getOrDefault(false)
        },
        target = target,
    )
}
