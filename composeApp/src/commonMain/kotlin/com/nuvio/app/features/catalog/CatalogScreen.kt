package com.nuvio.app.features.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.ScreenActivityEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterWatchedOverlay
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.catalogPosterBaseWidthDp
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.desktopPosterHoverScale
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.posterGridColumnCountForViewport
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.SkeletonPoster
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.isDesktop
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterHoverPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.navigation.LocalUseNativeNavigation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CatalogScreen(
    title: String,
    subtitle: String,
    target: CatalogTarget,
    onBack: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by CatalogRepository.uiState.collectAsStateWithLifecycle()
    val catalogSortSettings by remember {
        CatalogSortSettingsRepository.ensureLoaded()
        CatalogSortSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val homeCatalogSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val posterCardStyle = rememberPosterCardStyleUiState()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val initialScrollPosition = remember(
        target,
        homeCatalogSettingsUiState.hideUnreleasedContent,
    ) {
        CatalogRepository.scrollPosition(
            target = target,
        )
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScrollPosition.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialScrollPosition.firstVisibleItemScrollOffset,
    )
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var observedOfflineState by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val sortKey = catalogSortKey(target)
    val savedSort = sortKey?.let { catalogSortSettings.selections[it] } ?: CatalogSortOption.PROVIDER_ORDER
    val sortOptions = remember(uiState.items, sortKey) {
        if (sortKey == null) emptyList() else availableCatalogSortOptions(uiState.items)
    }
    val activeSort = effectiveCatalogSortOption(savedSort, sortOptions)
    val displayedItems = remember(uiState.items, activeSort) {
        sortCatalogItems(uiState.items, activeSort)
    }
    val loadMoreGate = remember(target, activeSort) { CatalogLoadMoreGate() }

    ScreenActivityEffect(target) { active ->
        if (active && target is CatalogTarget.WatchLater) CatalogRepository.load(target)
    }

    LiveCatalogRefreshEffect(
        key = target,
        enabled = networkStatusUiState.condition == NetworkCondition.Online && target is CatalogTarget.Addon,
    ) {
        if (!CatalogRepository.uiState.value.isLoading && gridState.firstVisibleItemIndex == 0) {
            CatalogRepository.load(target, force = true)
        }
    }

    LaunchedEffect(target, homeCatalogSettingsUiState.hideUnreleasedContent) {
        CatalogRepository.load(
            target = target,
        )
    }

    LaunchedEffect(gridState, target, homeCatalogSettingsUiState.hideUnreleasedContent) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                CatalogRepository.saveScrollPosition(
                    target = target,
                    firstVisibleItemIndex = index,
                    firstVisibleItemScrollOffset = offset,
                )
            }
    }

    LaunchedEffect(gridState, loadMoreGate, uiState.canLoadMore, uiState.isLoading) {
        snapshotFlow {
                val layoutInfo = gridState.layoutInfo
                CatalogPaginationViewport(
                    scrolling = gridState.isScrollInProgress,
                    firstIndex = gridState.firstVisibleItemIndex,
                    firstOffset = gridState.firstVisibleItemScrollOffset,
                    lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    totalItems = layoutInfo.totalItemsCount,
                    canScrollForward = gridState.canScrollForward,
                )
            }
            .distinctUntilChanged()
            .collect { viewport ->
                loadMoreGate.observeScroll(viewport.scrolling, viewport.firstIndex, viewport.firstOffset)
                val nearEnd = viewport.totalItems > 0 &&
                    viewport.lastVisibleIndex >= viewport.totalItems - 6
                val underfilled = uiState.items.isNotEmpty() &&
                    viewport.firstIndex == 0 && viewport.firstOffset == 0 &&
                    !viewport.canScrollForward
                if (loadMoreGate.shouldLoad(
                        nearEnd = nearEnd,
                        canLoadMore = uiState.canLoadMore,
                        isLoading = uiState.isLoading,
                        initialViewportUnderfilled = underfilled,
                    )
                ) CatalogRepository.loadMore()
            }
    }

    LaunchedEffect(networkStatusUiState.condition, target) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@LaunchedEffect
                observedOfflineState = false
                CatalogRepository.load(
                    target = target,
                    force = true,
                )
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val pageHorizontalPadding = if (isDesktop) {
            desktopPageHorizontalPaddingForWidth(maxWidth.value)
        } else {
            16.dp
        }
        val columns = remember(maxWidth, maxHeight, posterCardStyle.widthDp, isDesktop) {
            if (isDesktop) {
                posterGridColumnCountForViewport(maxWidth, maxHeight, posterCardStyle.widthDp)
            } else {
                catalogGridColumnsForWidth(maxWidth)
            }
        }
        val basePosterWidthDp = catalogPosterBaseWidthDp(posterCardStyle.widthDp)
        val gridCells = if (isDesktop) {
            GridCells.FixedSize(
                if (posterCardStyle.catalogLandscapeModeEnabled) {
                    landscapePosterWidth(basePosterWidthDp)
                } else {
                    basePosterWidthDp.dp
                },
            )
        } else {
            GridCells.Fixed(columns)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = gridCells,
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = pageHorizontalPadding,
                    top = with(androidx.compose.ui.platform.LocalDensity.current) { headerHeightPx.toDp() } + 12.dp,
                    end = pageHorizontalPadding,
                    bottom = nuvioSafeBottomPadding(28.dp),
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (uiState.items.isEmpty() && uiState.isLoading) {
                    items(columns * 3) {
                        if (isDesktop) {
                            CatalogSkeletonTile(
                                cornerRadiusDp = posterCardStyle.cornerRadiusDp,
                                aspectRatio = if (posterCardStyle.catalogLandscapeModeEnabled) {
                                    PosterLandscapeAspectRatio
                                } else {
                                    0.68f
                                },
                            )
                        } else {
                            SkeletonPoster(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = posterCardStyle.cornerRadiusDp.dp,
                                showLabels = !posterCardStyle.hideLabelsEnabled,
                            )
                        }
                    }
                } else if (uiState.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (target is CatalogTarget.WatchLater) {
                            Text("Nothing saved yet. Middle-click a poster in Airing Now or another catalog to add it to Watch Later.",
                                modifier = Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
                        } else {
                            CatalogEmptyState(
                                errorMessage = uiState.errorMessage,
                                networkCondition = networkStatusUiState.condition,
                                onRetry = {
                                    NetworkStatusRepository.requestRefresh(force = true)
                                    CatalogRepository.load(
                                        target = target,
                                        force = true,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    items(
                        items = displayedItems.withDuplicateSafeLazyKeys { item -> item.stableKey() },
                        key = { item -> item.lazyKey },
                    ) { keyedItem ->
                        val item = keyedItem.value
                        val isWatched = WatchingState.isPosterWatched(
                            watchedKeys = watchedUiState.watchedKeys,
                            item = item,
                            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                        )
                        if (isDesktop) {
                            HomePosterCard(
                                item = item,
                                useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled,
                                isWatched = isWatched,
                                onClick = onPosterClick?.let { { it(item) } },
                                onLongClick = onPosterLongClick?.let { { it(item) } },
                            )
                        } else {
                            CatalogPosterTile(
                                item = item,
                                cornerRadiusDp = posterCardStyle.cornerRadiusDp,
                                hideLabels = posterCardStyle.hideLabelsEnabled,
                                isWatched = isWatched,
                                onClick = onPosterClick?.let { { it(item) } },
                                onLongClick = onPosterLongClick?.let { { it(item) } },
                            )
                        }
                    }
                    if (uiState.isLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CatalogLoadingFooter()
                        }
                    }
                }
            }
            NuvioDesktopVerticalScrollbar(
                state = gridState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            )

            CatalogHeader(
                title = title,
                subtitle = subtitle,
                pageHorizontalPadding = pageHorizontalPadding,
                modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
                onBack = onBack,
                sortOptions = sortOptions.takeIf { sortKey != null },
                selectedSort = activeSort,
                showUnavailablePreference = savedSort != activeSort && uiState.items.isNotEmpty(),
                showMissingMetadata = uiState.items.isNotEmpty() && sortOptions.size <= 3,
                showPartialSort = uiState.canLoadMore && activeSort != CatalogSortOption.PROVIDER_ORDER,
                onSortSelected = { selected ->
                    CatalogSortSettingsRepository.setSort(target, selected)
                    CatalogRepository.saveScrollPosition(target, 0, 0)
                    coroutineScope.launch { gridState.scrollToItem(0) }
                },
            )
        }
    }
}

@Composable
private fun CatalogHeader(
    title: String,
    subtitle: String,
    pageHorizontalPadding: Dp,
    onBack: (() -> Unit)?,
    sortOptions: List<CatalogSortOption>?,
    selectedSort: CatalogSortOption,
    showUnavailablePreference: Boolean,
    showMissingMetadata: Boolean,
    showPartialSort: Boolean,
    onSortSelected: (CatalogSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalUseNativeNavigation.current) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = pageHorizontalPadding),
        ) {
            Spacer(Modifier.height(44.dp))
            if (sortOptions != null) {
                CatalogSortControls(
                    sortOptions = sortOptions,
                    selectedSort = selectedSort,
                    showUnavailablePreference = showUnavailablePreference,
                    showMissingMetadata = showMissingMetadata,
                    showPartialSort = showPartialSort,
                    onSortSelected = onSortSelected,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .then(if (isDesktop) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(horizontal = pageHorizontalPadding)
            .padding(top = if (isDesktop) 32.dp else 52.dp, bottom = 12.dp),
    ) {
        if (onBack != null) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier.size(if (isDesktop) 48.dp else 40.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                iconSize = 24.dp,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sortOptions != null) {
            Spacer(modifier = Modifier.height(14.dp))
            CatalogSortControls(
                sortOptions = sortOptions,
                selectedSort = selectedSort,
                showUnavailablePreference = showUnavailablePreference,
                showMissingMetadata = showMissingMetadata,
                showPartialSort = showPartialSort,
                onSortSelected = onSortSelected,
            )
        }
    }
}

@Composable
private fun CatalogSortControls(
    sortOptions: List<CatalogSortOption>,
    selectedSort: CatalogSortOption,
    showUnavailablePreference: Boolean,
    showMissingMetadata: Boolean,
    showPartialSort: Boolean,
    onSortSelected: (CatalogSortOption) -> Unit,
) {
    NuvioDropdownChip(
        title = stringResource(Res.string.catalog_sort_title),
        label = catalogSortLabel(selectedSort),
        selectedKey = selectedSort.name,
        options = sortOptions.map { option ->
            NuvioDropdownOption(key = option.name, label = catalogSortLabel(option))
        },
        enabled = sortOptions.size > 1,
        onSelected = { option ->
            CatalogSortOption.entries.firstOrNull { it.name == option.key }?.let(onSortSelected)
        },
    )
    if (showUnavailablePreference || showMissingMetadata) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (showUnavailablePreference) {
                    Res.string.catalog_sort_preference_unavailable
                } else {
                    Res.string.catalog_sort_metadata_unavailable
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showPartialSort) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.catalog_sort_partial_results),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun catalogSortLabel(option: CatalogSortOption): String = when (option) {
    CatalogSortOption.PROVIDER_ORDER -> stringResource(Res.string.catalog_sort_provider_order)
    CatalogSortOption.TITLE_ASC -> stringResource(Res.string.catalog_sort_title_asc)
    CatalogSortOption.TITLE_DESC -> stringResource(Res.string.catalog_sort_title_desc)
    CatalogSortOption.RATING_DESC -> stringResource(Res.string.catalog_sort_rating_desc)
    CatalogSortOption.RATING_ASC -> stringResource(Res.string.catalog_sort_rating_asc)
    CatalogSortOption.RELEASE_DESC -> stringResource(Res.string.catalog_sort_release_desc)
    CatalogSortOption.RELEASE_ASC -> stringResource(Res.string.catalog_sort_release_asc)
    CatalogSortOption.ADDED_DESC -> stringResource(Res.string.catalog_sort_added_desc)
    CatalogSortOption.ADDED_ASC -> stringResource(Res.string.catalog_sort_added_asc)
}

@Composable
private fun CatalogPosterTile(
    item: MetaPreview,
    cornerRadiusDp: Int,
    hideLabels: Boolean,
    isWatched: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    HomePosterHoverPreview(
        item = item,
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .desktopPosterHoverScale()
                .then(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(item.posterShape.catalogAspectRatio())
                    .clip(RoundedCornerShape(cornerRadiusDp.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .nuvioCardDepth(
                        shape = RoundedCornerShape(cornerRadiusDp.dp),
                        surface = NuvioCardDepthSurface.Posters,
                    )
                    .posterCardClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                        zoomImageUrl = item.poster,
                        zoomCornerRadius = cornerRadiusDp.dp,
                        hoverScaleEnabled = false,
                    ),
            ) {
                if (item.poster != null) {
                    val platformContext = coil3.compose.LocalPlatformContext.current
                    val hasFallback = !item.rawPosterUrl.isNullOrBlank() && item.rawPosterUrl != item.poster
                    val imageModel = remember(item.poster, item.rawPosterUrl, platformContext) {
                        if (hasFallback) {
                            coil3.request.ImageRequest.Builder(platformContext)
                                .data(item.poster)
                                .memoryCacheKeyExtras(
                                    mapOf(com.nuvio.app.core.poster.CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to item.rawPosterUrl!!)
                                )
                                .build()
                        } else {
                            item.poster
                        }
                    }
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                NuvioPosterWatchedOverlay(isWatched = isWatched)
            }
            if (!hideLabels) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = item.releaseInfo?.let { formatReleaseDateForDisplay(it) }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogSkeletonTile(cornerRadiusDp: Int) {
    CatalogSkeletonTile(cornerRadiusDp = cornerRadiusDp, aspectRatio = 0.68f)
}

@Composable
private fun CatalogSkeletonTile(
    cornerRadiusDp: Int,
    aspectRatio: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(MaterialTheme.colorScheme.surface),
    )
}

private fun PosterShape.catalogAspectRatio(): Float =
    when (this) {
        PosterShape.Poster -> 0.68f
        PosterShape.Square -> 1f
        PosterShape.Landscape -> 1.78f
    }

private fun catalogGridColumnsForWidth(screenWidth: Dp): Int =
    when {
        screenWidth >= 1400.dp -> 7
        screenWidth >= 1200.dp -> 6
        screenWidth >= 1000.dp -> 5
        screenWidth >= 840.dp -> 4
        else -> 3
    }

@Composable
private fun CatalogEmptyState(
    errorMessage: String?,
    networkCondition: NetworkCondition,
    onRetry: (() -> Unit)? = null,
) {
    if (
        !errorMessage.isNullOrBlank() &&
        (networkCondition == NetworkCondition.NoInternet || networkCondition == NetworkCondition.ServersUnreachable)
    ) {
        NuvioNetworkOfflineCard(
            condition = networkCondition,
            onRetry = onRetry,
        )
        return
    }

    val loadFailed = !errorMessage.isNullOrBlank()
    HomeEmptyStateCard(
        title = stringResource(
            if (loadFailed) Res.string.catalog_load_failed_title else Res.string.catalog_empty_title,
        ),
        message = errorMessage ?: stringResource(Res.string.catalog_empty_message),
        actionLabel = if (loadFailed) stringResource(Res.string.action_retry) else null,
        onActionClick = if (loadFailed) onRetry else null,
    )
}

@Composable
private fun CatalogLoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        NuvioLoadingIndicator(
            modifier = Modifier.size(22.dp),
        )
    }
}
