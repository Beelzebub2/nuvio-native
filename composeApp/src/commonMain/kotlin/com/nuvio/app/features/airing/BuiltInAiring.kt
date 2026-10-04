package com.nuvio.app.features.airing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection

internal const val NATIVE_AIRING_PREFIX = "nativeairing:"

@Composable
internal expect fun BuiltInAiringSection(
    modifier: Modifier,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
)

@Composable
internal expect fun BuiltInAiringCatalog(
    window: String,
    onBack: () -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: (MetaPreview) -> Unit,
)

internal expect suspend fun fetchBuiltInAiringDetails(id: String): MetaDetails?
