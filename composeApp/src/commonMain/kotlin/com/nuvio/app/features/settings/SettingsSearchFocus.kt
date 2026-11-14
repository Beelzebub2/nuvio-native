package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A single, short-lived request, also available to standalone mobile settings routes. */
internal data class SettingsSearchFocusRequest(
    val id: Long,
    val page: SettingsPage,
    val key: String,
    val title: String,
)

internal object SettingsSearchFocus {
    private var nextId = 0L
    private val activeLists = mutableMapOf<SettingsPage, LazyListState>()
    // Observable so an in-flight lazy-list scan stops as soon as the exact
    // rendered control claims the request, before it can scroll the row away.
    private var claimedId by mutableStateOf<Long?>(null)
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

    fun registerList(page: SettingsPage, list: LazyListState) {
        activeLists[page] = list
    }

    fun unregisterList(page: SettingsPage, list: LazyListState) {
        if (activeLists[page] === list) activeLists.remove(page)
    }

    fun listFor(page: SettingsPage): LazyListState? = activeLists[page]

    fun release(request: SettingsSearchFocusRequest) {
        // bringIntoView can be cancelled by a destination transition or a row
        // losing its LazyColumn composition. Allow the next composition to retry
        // instead of permanently consuming a request that was never shown.
        if (pending == request && claimedId == request.id) {
            claimedId = null
        }
    }

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
    DisposableEffect(page, listState) {
        SettingsSearchFocus.registerList(page, listState)
        onDispose { SettingsSearchFocus.unregisterList(page, listState) }
    }
    val request = SettingsSearchFocus.pending?.takeIf { it.page == page }
    val claimed = request != null && SettingsSearchFocus.isClaimed(request)
    LaunchedEffect(request?.id, listState, page, claimed) {
        if (request == null || claimed) return@LaunchedEffect

        // A new destination may not have measured its lazy items yet.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        // A large lazy item can already contain the exact row. First let its
        // bringIntoViewRequester run while navigation/layout is settling; an
        // eager scrollToItem(0) otherwise cancels the target's focus coroutine.
        withFrameNanos { }
        delay(350)
        if (SettingsSearchFocus.pending != request || SettingsSearchFocus.isClaimed(request)) return@LaunchedEffect

        // A section or its controls can arrive after the first scan (e.g. account or
        // tracking data loaded asynchronously). Keep looking while this navigation
        // request is alive rather than leaving the user at the bottom of the page.
        // The enclosing page scope cancels unresolved requests after 15 seconds.
        var scanCount = 0
        while (SettingsSearchFocus.pending == request && !SettingsSearchFocus.isClaimed(request)) {
            // Each section is a lazy item, sometimes containing several controls.
            // Compose each section so the exact control can issue bringIntoView().
            val itemCount = listState.layoutInfo.totalItemsCount
            val indices = if (scanCount % 2 == 0) {
                0 until itemCount
            } else {
                (itemCount - 1) downTo 0
            }
            for (index in indices) {
                if (SettingsSearchFocus.pending != request || SettingsSearchFocus.isClaimed(request)) {
                    return@LaunchedEffect
                }
                listState.scrollToItem(index)
                // Measure/position the newly composed section and allow its
                // focus effect to claim the request before scanning past it.
                withFrameNanos { }
                withFrameNanos { }
                delay(60)
            }
            if (SettingsSearchFocus.pending != request || SettingsSearchFocus.isClaimed(request)) break
            scanCount++

            // Prefer waking immediately when async loading adds/removes a lazy
            // section. Also retry on a timer: controls inside an existing item
            // can change without affecting totalItemsCount.
            val observedCount = listState.layoutInfo.totalItemsCount
            withTimeoutOrNull(800) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it != observedCount }
            }
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
            // Do not clear on disposal: mobile navigation may briefly dispose and
            // recreate a destination while transitioning, before the focused row
            // has ever been composed. Only completion or the timeout clears it.
            delay(15000)
            SettingsSearchFocus.complete(request)
        }
    }
    CompositionLocalProvider(LocalSettingsSearchPage provides page, content = content)
}

/** Scroll the exact rendered control into view and briefly emphasize it after navigation. */
@Composable
internal fun Modifier.settingsSearchTarget(title: String, key: String? = null): Modifier {
    val request = SettingsSearchFocus.pending ?: return this
    if (request.page != LocalSettingsSearchPage.current) return this
    // A stable key takes precedence over the visible label, which can change with
    // catalog state, locale, or a provider's connection state.
    if (key != null) {
        if (request.key != key) return this
    } else if (request.title != title) {
        return this
    }

    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember(request.id) { mutableStateOf(false) }
    var positioned by remember(request.id) { mutableStateOf(false) }
    var targetCoordinates by remember(request.id) { mutableStateOf<LayoutCoordinates?>(null) }
    val accent = MaterialTheme.nuvio.colors.accent
    val preferredTopPx = with(LocalDensity.current) { 240.dp.toPx() }
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) accent.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 190),
        label = "settings-search-highlight",
    )

    LaunchedEffect(request.id, positioned) {
        if (!positioned || !SettingsSearchFocus.claim(request)) return@LaunchedEffect
        var displayed = false
        try {
            // Many settings pages place dozens of controls inside one enormous
            // LazyColumn item. BringIntoViewRequester can suspend indefinitely
            // when such a child is far beyond the visible portion of its item.
            // The row is composed and measured, so scroll by its real position
            // instead of relying on a child visibility request.
            val listState = SettingsSearchFocus.listFor(request.page)
            val coords = targetCoordinates
            if (listState != null && coords != null && coords.isAttached) {
                val delta = coords.positionInRoot().y - preferredTopPx
                if (kotlin.math.abs(delta) > 8f) listState.scrollBy(delta)
            } else {
                requester.bringIntoView()
            }
            highlighted = true
            // Do not consume the request before a highlighted frame was drawn.
            withFrameNanos { }
            displayed = true
            delay(2800)
        } finally {
            highlighted = false
            if (displayed) SettingsSearchFocus.complete(request)
            else SettingsSearchFocus.release(request)
        }
    }

    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { coordinates ->
            targetCoordinates = coordinates
            positioned = coordinates.isAttached && coordinates.size.height > 0
        }
        .background(highlightColor, RectangleShape)
        .then(
            if (highlighted) Modifier.border(2.dp, accent, RoundedCornerShape(8.dp))
            else Modifier
        )
        .then(if (highlighted) Modifier.testTag("settings-search-focused") else Modifier)
}
