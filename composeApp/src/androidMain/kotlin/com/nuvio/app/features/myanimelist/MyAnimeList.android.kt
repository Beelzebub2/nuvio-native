package com.nuvio.app.features.myanimelist
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.details.MetaDetails
@Composable internal actual fun MyAnimeListControls(meta: MetaDetails) {}
@Composable internal actual fun MyAnimeListSettingsCard() {}
@Composable internal actual fun MyAnimeListTabVisible() = false
@Composable internal actual fun MyAnimeListTabContent(topChromePadding: Dp?) {}
internal actual fun myAnimeListPlaybackUpdateThresholdPercent(): Int = DefaultMalPlaybackUpdateThresholdPercent
internal actual suspend fun markMyAnimeListRewatchOnPlayback(
    meta: MetaDetails, playbackProfileId: Int, positionMs: Long, durationMs: Long,
) {}
internal actual fun clearMyAnimeListState() {}
