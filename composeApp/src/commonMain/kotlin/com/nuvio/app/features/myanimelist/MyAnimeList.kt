package com.nuvio.app.features.myanimelist

import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetails

@Composable internal expect fun MyAnimeListControls(meta: MetaDetails)
@Composable internal expect fun MyAnimeListSettingsCard()
internal expect fun clearMyAnimeListState()
