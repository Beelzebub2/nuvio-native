package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** A single, short-lived request, also available to standalone mobile settings routes. */
internal data class SettingsSearchFocusRequest(
    val id: Long,
    val page: SettingsPage,
    val key: String,
    val title: String,
)

internal object SettingsSearchFocus {
    private var nextId = 0L
    private var claimedId: Long? = null
    var pending by mutableStateOf<SettingsSearchFocusRequest?>(null)
        private set

    fun request(page: SettingsPage, key: String, title: String) {
        claimedId = null
        pending = SettingsSearchFocusRequest(++nextId, page, key, title)
    }

    fun claim(request: SettingsSearchFocusRequest): Boolean {
        if (pending != request || claimedId == request.id) return false
        claimedId = request.id
        return true
    }

    fun isClaimed(request: SettingsSearchFocusRequest): Boolean = claimedId == request.id

    fun complete(request: SettingsSearchFocusRequest) {
        if (pending == request) {
            pending = null
            claimedId = null
        }
    }

    fun clear() {
        pending = null
        claimedId = null
    }
}

/** Resolve a target in an item that LazyColumn has not composed yet. */
@Composable
internal fun SettingsSearchLazyListFocus(page: SettingsPage, listState: LazyListState) {
    val request = SettingsSearchFocus.pending?.takeIf { it.page == page }
    LaunchedEffect(request?.id, listState, page) {
        if (request == null) return@LaunchedEffect

        // A new destination may not have measured its lazy items yet.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        // Let immediately visible controls claim the request without moving the list.
        withFrameNanos { }
        if (SettingsSearchFocus.pending != request || SettingsSearchFocus.isClaimed(request)) return@LaunchedEffect

        // Each setting section is one lazy item, sometimes with many controls within it.
        // Visiting only uncomposed sections allows their row to request a precise bring-into-view.
        var index = 0
        while (index < listState.layoutInfo.totalItemsCount) {
            if (SettingsSearchFocus.pending != request || SettingsSearchFocus.isClaimed(request)) break
            listState.scrollToItem(index)
            withFrameNanos { }
            index++
        }
    }
}

/** Shared mobile settings scaffold with lazy-list search navigation enabled. */
@Composable
internal fun SearchableSettingsNuvioScreen(
    page: SettingsPage,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    SettingsSearchLazyListFocus(page, listState)
    NuvioScreen(modifier = modifier, listState = listState, content = content)
}

private val LocalSettingsSearchPage = compositionLocalOf<SettingsPage?> { null }

@Composable
internal fun SettingsSearchPageScope(page: SettingsPage, content: @Composable () -> Unit) {
    val request = SettingsSearchFocus.pending
    LaunchedEffect(page, request?.id) {
        if (request?.page == page) {
            // A hidden, unavailable, or no-longer-rendered control must not keep stale focus.
            delay(5000)
            SettingsSearchFocus.complete(request)
        }
    }
    CompositionLocalProvider(LocalSettingsSearchPage provides page, content = content)
}

/** Scroll the exact rendered control into view and briefly emphasize it after navigation. */
@Composable
internal fun Modifier.settingsSearchTarget(title: String, key: String? = null): Modifier {
    val request = SettingsSearchFocus.pending ?: return this
    if (request.page != LocalSettingsSearchPage.current || request.title != title) return this
    if (key != null && request.key != key) return this

    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember(request.id) { mutableStateOf(false) }
    var positioned by remember(request.id) { mutableStateOf(false) }
    val accent = MaterialTheme.nuvio.colors.accent
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) accent.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 190),
        label = "settings-search-highlight",
    )

    LaunchedEffect(request.id, positioned) {
        if (!positioned || !SettingsSearchFocus.claim(request)) return@LaunchedEffect
        try {
            requester.bringIntoView()
            highlighted = true
            delay(1800)
        } finally {
            highlighted = false
            SettingsSearchFocus.complete(request)
        }
    }

    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { coordinates ->
            positioned = coordinates.isAttached && coordinates.size.height > 0
        }
        .background(highlightColor, RectangleShape)
        .then(if (highlighted) Modifier.testTag("settings-search-focused") else Modifier)
}
