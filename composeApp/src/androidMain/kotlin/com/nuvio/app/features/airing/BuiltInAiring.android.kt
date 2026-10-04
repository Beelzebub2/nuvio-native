package com.nuvio.app.features.airing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview

@Composable
internal actual fun BuiltInAiringSection(modifier: Modifier, onPosterClick: ((MetaPreview) -> Unit)?) = Unit
internal actual suspend fun fetchBuiltInAiringDetails(id: String): MetaDetails? = null
