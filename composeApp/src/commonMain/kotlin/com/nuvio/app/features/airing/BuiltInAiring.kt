package com.nuvio.app.features.airing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview

internal const val NATIVE_AIRING_PREFIX = "nativeairing:"

@Composable
internal expect fun BuiltInAiringSection(modifier: Modifier, onPosterClick: ((MetaPreview) -> Unit)?)

internal expect suspend fun fetchBuiltInAiringDetails(id: String): MetaDetails?
