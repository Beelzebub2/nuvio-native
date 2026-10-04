package com.nuvio.app.features.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.isDesktop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.TimeSource

/** Refresh only the visible screen, pause while unfocused, and throttle focus/tab changes. */
@Composable
internal fun LiveCatalogRefreshEffect(key: Any?, enabled: Boolean, onRefresh: () -> Unit) {
    if (!isDesktop) return
    val refresh = rememberUpdatedState(onRefresh)
    val lastRefresh = remember(key) { arrayOf(TimeSource.Monotonic.markNow()) }
    ScreenActivityEffect(key, enabled) { active ->
        if (!active || !enabled) return@ScreenActivityEffect
        AppForegroundMonitor.events().collectLatest { visibility ->
            if (visibility != AppVisibility.Foreground) return@collectLatest
            while (true) {
                if (lastRefresh[0].elapsedNow().inWholeSeconds >= 30) {
                    lastRefresh[0] = TimeSource.Monotonic.markNow()
                    refresh.value()
                }
                delay(60_000L)
            }
        }
    }
}
