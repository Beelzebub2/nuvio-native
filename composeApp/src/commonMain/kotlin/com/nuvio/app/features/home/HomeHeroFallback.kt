package com.nuvio.app.features.home

/** Mobile can display its built-in schedule when TMDB and all addon catalogs are unavailable. */
internal expect suspend fun platformHomeHeroFallbackItems(includeUpcoming: Boolean): List<MetaPreview>
