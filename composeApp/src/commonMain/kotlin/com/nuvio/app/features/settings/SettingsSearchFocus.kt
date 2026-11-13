package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay

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
    val accent = MaterialTheme.nuvio.colors.accent
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) accent.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 190),
        label = "settings-search-highlight",
    )

    LaunchedEffect(request.id) {
        if (!SettingsSearchFocus.claim(request)) return@LaunchedEffect
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
        .background(highlightColor, RectangleShape)
        .then(if (highlighted) Modifier.testTag("settings-search-focused") else Modifier)
}
