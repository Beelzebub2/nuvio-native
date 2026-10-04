package com.nuvio.app.features.airing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection

@Composable
internal actual fun BuiltInAiringSection(modifier: Modifier, onPosterClick: ((MetaPreview) -> Unit)?, onCatalogClick: ((HomeCatalogSection) -> Unit)?, onPosterLongClick: ((MetaPreview) -> Unit)?) = Unit
@Composable
internal actual fun BuiltInAiringCatalog(window: String, onBack: () -> Unit, onPosterClick: (MetaPreview) -> Unit, onPosterLongClick: (MetaPreview) -> Unit) = Unit
internal actual suspend fun fetchBuiltInAiringDetails(id: String): MetaDetails? = null
