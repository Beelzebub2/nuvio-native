package com.nuvio.app.features.library

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.NuvioEmptyBlock
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioSectionHeader
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioGroupSegment
import com.nuvio.app.core.ui.nuvioTypeScale
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewAgenda
import com.nuvio.app.core.ui.DisintegratingContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.nuvioSmoothWheelScroll
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.ScopedDisintegrationTracker
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.posterGridColumnCountForCatalogWidth
import com.nuvio.app.features.home.components.posterGridColumnCountForWidth
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryItemType
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onPosterClick: ((LibraryItem) -> Unit)? = null,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    onSectionViewAllClick: ((LibrarySection, LibrarySortOption) -> Unit)? = null,
    onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    onConnectCloudClick: (() -> Unit)? = null,
    onDownloadsClick: (() -> Unit)? = null,
    disintegrationRequest: DisintegrationRequest<String>? = null,
    headerAccessory: (@Composable () -> Unit)? = null,
) {
    val uiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val cloudUiState by CloudLibraryRepository.uiState.collectAsStateWithLifecycle()
    val cloudSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val displaySettings by remember {
        LibraryDisplaySettingsRepository.ensureLoaded()
        LibraryDisplaySettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    var observedOfflineState by remember { mutableStateOf(false) }
    var sourceModeName by rememberSaveable { mutableStateOf(LibraryViewMode.Saved.name) }
    val sourceMode = remember(sourceModeName) {
        runCatching { LibraryViewMode.valueOf(sourceModeName) }.getOrDefault(LibraryViewMode.Saved)
    }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudSearchQuery by rememberSaveable { mutableStateOf("") }
    val selectedType = remember(selectedTypeName) {
        selectedTypeName?.let { runCatching { CloudLibraryItemType.valueOf(it) }.getOrNull() }
    }
    var selectedCloudItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibrarySectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryType by rememberSaveable { mutableStateOf<String?>(null) }
    var librarySearchQuery by rememberSaveable { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    ScreenActivityEffect(listState) { screenActive ->
        if (!screenActive) listState.stopScroll()
    }
    val isRemoteSource = uiState.sourceMode != LibrarySourceMode.LOCAL
    val effectiveSortOption = effectiveLibrarySortOption(
        selected = displaySettings.sortOption,
        sourceMode = uiState.sourceMode,
    )
    val orderListKeys = if (sourceMode != LibraryViewMode.Saved) emptyList() else {
        if (displaySettings.layoutMode == LibraryLayoutMode.HORIZONTAL) uiState.sections.map { it.type }
        else listOfNotNull(uiState.sections.firstOrNull { it.type == selectedLibrarySectionKey }?.type
            ?: uiState.sections.firstOrNull()?.type)
    }
    val providerOrders = rememberLibraryProviderOrders(uiState.sourceMode, orderListKeys, effectiveSortOption)
    val visibleSortOption = if (providerOrders.failed) LibrarySortOption.DEFAULT else effectiveSortOption
    val sortedSections = remember(uiState.sections, displaySettings, uiState.sourceMode, sourceMode, providerOrders) {
        if (sourceMode == LibraryViewMode.Saved && displaySettings.layoutMode == LibraryLayoutMode.HORIZONTAL) {
            sortLibrarySections(
                sections = uiState.sections,
                selected = visibleSortOption,
                sourceMode = uiState.sourceMode,
                providerOrders = providerOrders.ranks,
            )
        } else {
            emptyList()
        }
    }
    val verticalProjection = remember(
        uiState.sections,
        uiState.sourceMode,
        selectedLibrarySectionKey,
        selectedLibraryType,
        displaySettings,
        sourceMode,
        providerOrders,
    ) {
        if (sourceMode == LibraryViewMode.Saved && displaySettings.layoutMode == LibraryLayoutMode.VERTICAL) {
            buildLibraryVerticalProjection(
                sections = uiState.sections,
                sourceMode = uiState.sourceMode,
                selectedSectionKey = selectedLibrarySectionKey,
                selectedType = selectedLibraryType,
                sortOption = visibleSortOption,
                providerOrders = providerOrders.ranks,
            )
        } else {
            LibraryVerticalProjection(
                availableSections = emptyList(),
                selectedSectionKey = null,
                availableTypes = emptyList(),
                selectedType = null,
                entries = emptyList(),
            )
        }
    }
    // Desktop: a search box and a type filter narrow both layouts. The grid's own projection
    // already filters by type, so only rows need the type applied here.
    val isGridLayout = displaySettings.layoutMode == LibraryLayoutMode.VERTICAL
    val desktopQuery = if (isDesktop) librarySearchQuery.trim() else ""
    val desktopRowTypes = remember(uiState.sections, isGridLayout) {
        if (!isDesktop || isGridLayout) {
            emptyList()
        } else {
            uiState.sections.asSequence()
                .flatMap { section -> section.items.asSequence() }
                .map { item -> item.libraryFilterType() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }
    }
    val desktopRowType = selectedLibraryType?.trim()?.lowercase()?.takeIf { it in desktopRowTypes }
    val visibleSortedSections = remember(sortedSections, desktopQuery, desktopRowType) {
        if (desktopQuery.isEmpty() && desktopRowType == null) {
            sortedSections
        } else {
            sortedSections.mapNotNull { section ->
                section.copy(
                    items = section.items.filter { item ->
                        item.matchesLibrarySearch(desktopQuery) &&
                            (desktopRowType == null || item.libraryFilterType() == desktopRowType)
                    },
                ).takeIf { it.items.isNotEmpty() }
            }
        }
    }
    val visibleVerticalProjection = remember(verticalProjection, desktopQuery) {
        if (desktopQuery.isEmpty()) {
            verticalProjection
        } else {
            verticalProjection.copy(entries = verticalProjection.entries.filter { it.item.matchesLibrarySearch(desktopQuery) })
        }
    }
    val desktopTotalCount = remember(uiState.sections, isGridLayout, verticalProjection.selectedSectionKey) {
        val counted = if (isGridLayout && verticalProjection.selectedSectionKey != null) {
            uiState.sections.filter { it.type == verticalProjection.selectedSectionKey }
        } else {
            uiState.sections
        }
        counted.flatMap { it.items }.distinctBy(::libraryDisplayItemKey).size
    }
    val desktopShownCount = if (isGridLayout) {
        visibleVerticalProjection.entries.size
    } else {
        visibleSortedSections.flatMap { it.items }.distinctBy(::libraryDisplayItemKey).size
    }
    val retryLibraryLoad: () -> Unit = {
        NetworkStatusRepository.requestRefresh(force = true)
        coroutineScope.launch {
            LibraryRepository.pullFromServer(
                profileId = ProfileRepository.activeProfileId,
                refreshIntent = TrackingRefreshIntent.USER_INITIATED,
            )
        }
    }

    ScreenActivityEffect(networkStatusUiState.condition, isRemoteSource) { screenActive ->
        if (!screenActive) return@ScreenActivityEffect
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@ScreenActivityEffect
                observedOfflineState = false
                if (isRemoteSource) {
                    coroutineScope.launch {
                        LibraryRepository.pullFromServer(ProfileRepository.activeProfileId)
                    }
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    ScreenActivityEffect(scrollToTopRequests) { screenActive ->
        if (!screenActive) return@ScreenActivityEffect
        scrollToTopRequests.collect {
            listState.animateScrollToItem(0)
        }
    }

    ScreenActivityEffect(sourceMode, cloudSettings.cloudLibraryEnabled, cloudSettings.providerApiKeys) { screenActive ->
        if (screenActive && sourceMode == LibraryViewMode.Cloud) {
            CloudLibraryRepository.ensureLoaded()
            selectedCloudItemKey = null
        }
    }

    val disintegration = remember { LibraryDisintegrationHolder() }
    val librarySectionsDisplay = if (
        sourceMode != LibraryViewMode.Cloud &&
        displaySettings.layoutMode == LibraryLayoutMode.HORIZONTAL &&
        uiState.isLoaded &&
        visibleSortedSections.isNotEmpty()
    ) {
        disintegration.sync(
            sourceMode = uiState.sourceMode,
            sections = visibleSortedSections,
            previewLimit = LIBRARY_SECTION_PREVIEW_LIMIT,
            request = disintegrationRequest,
        )
    } else {
        disintegration.reset()
        emptyList()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val posterCardStyle = rememberPosterCardStyleUiState()
        val gridColumns = remember(maxWidth, posterCardStyle.widthDp, isDesktop) {
            if (isDesktop) {
                posterGridColumnCountForCatalogWidth(
                    screenWidth = maxWidth,
                    basePosterWidthDp = posterCardStyle.widthDp,
                )
            } else {
                posterGridColumnCountForWidth(maxWidth)
            }
        }

        val pageInset = if (isDesktop) desktopPageHorizontalPaddingForWidth(maxWidth.value) else 16.dp
        CompositionLocalProvider(LocalLibraryPageInset provides pageInset) {
        NuvioScreen(
            // Like Home, the library has only sideways rows inside, so the page owns vertical wheel turns.
            modifier = Modifier.fillMaxSize().nuvioSmoothWheelScroll(listState),
            horizontalPadding = 0.dp,
            topPadding = if (topChromePadding != null) 0.dp else null,
            listState = listState,
        ) {
            stickyHeader {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.nuvio.colors.background)
                            .nuvioConsumePointerEvents(),
                    )
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        NuvioScreenHeader(
                            title = if (sourceMode == LibraryViewMode.Cloud) {
                                stringResource(Res.string.library_title)
                            } else {
                                when (uiState.sourceMode) {
                                    LibrarySourceMode.LOCAL -> stringResource(Res.string.library_title)
                                    LibrarySourceMode.TRAKT -> stringResource(Res.string.library_trakt_title)
                                    LibrarySourceMode.SIMKL -> stringResource(Res.string.library_simkl_title)
                                    LibrarySourceMode.MDBLIST -> stringResource(Res.string.library_mdblist_title)
                                }
                            },
                            modifier = Modifier.padding(horizontal = libraryPageInset()),
                            topPadding = topChromePadding,
                            actions = {
                                if (sourceMode == LibraryViewMode.Saved) {
                                    LibraryListManagementButton()
                                }
                                // Desktop switches Rows/Grid from its toolbar.
                                if (sourceMode == LibraryViewMode.Saved && !isDesktop) {
                                    val targetLayout = if (displaySettings.layoutMode == LibraryLayoutMode.HORIZONTAL) {
                                        LibraryLayoutMode.VERTICAL
                                    } else {
                                        LibraryLayoutMode.HORIZONTAL
                                    }
                                    IconButton(
                                        onClick = {
                                            LibraryDisplaySettingsRepository.setLayoutMode(targetLayout)
                                        },
                                    ) {
                                        Crossfade(
                                            targetState = targetLayout,
                                            animationSpec = tween(durationMillis = 140),
                                            label = "libraryLayoutAction",
                                        ) { animatedTargetLayout ->
                                            Icon(
                                                imageVector = if (animatedTargetLayout == LibraryLayoutMode.VERTICAL) {
                                                    Icons.Rounded.GridView
                                                } else {
                                                    Icons.Rounded.ViewAgenda
                                                },
                                                contentDescription = if (animatedTargetLayout == LibraryLayoutMode.VERTICAL) {
                                                    stringResource(Res.string.library_layout_show_vertical)
                                                } else {
                                                    stringResource(Res.string.library_layout_show_horizontal)
                                                },
                                                tint = MaterialTheme.nuvio.colors.textSecondary,
                                            )
                                        }
                                    }
                                }
                                if (onDownloadsClick != null) {
                                    LibraryDownloadsButton(onClick = onDownloadsClick)
                                }
                            },
                        )
                        headerAccessory?.let { accessory ->
                            Box(modifier = Modifier.padding(start = libraryPageInset(), end = libraryPageInset(), bottom = 12.dp)) { accessory() }
                        }
                        if (isDesktop) {
                            val savedReady = sourceMode == LibraryViewMode.Saved &&
                                uiState.isLoaded && uiState.sections.isNotEmpty()
                            DesktopLibraryToolbar(
                                cloudSelected = sourceMode == LibraryViewMode.Cloud,
                                onCloudSelectedChange = { cloud ->
                                    sourceModeName = if (cloud) LibraryViewMode.Cloud.name else LibraryViewMode.Saved.name
                                },
                                savedControls = if (!savedReady) null else DesktopLibrarySavedControls(
                                    query = librarySearchQuery,
                                    onQueryChange = { librarySearchQuery = it },
                                    types = if (isGridLayout) verticalProjection.availableTypes else desktopRowTypes,
                                    selectedType = if (isGridLayout) verticalProjection.selectedType else desktopRowType,
                                    onTypeSelected = { type -> selectedLibraryType = type },
                                    layoutMode = displaySettings.layoutMode,
                                    onLayoutModeChange = LibraryDisplaySettingsRepository::setLayoutMode,
                                    sortOption = effectiveSortOption,
                                    sortOptions = availableLibrarySortOptions(uiState.sourceMode),
                                    onSortSelected = LibraryDisplaySettingsRepository::setSortOption,
                                    lists = if (isGridLayout) verticalProjection.availableSections else emptyList(),
                                    selectedListKey = verticalProjection.selectedSectionKey,
                                    onListSelected = { sectionKey ->
                                        selectedLibrarySectionKey = sectionKey
                                        selectedLibraryType = null
                                    },
                                ),
                                modifier = Modifier.padding(horizontal = libraryPageInset()).padding(top = 4.dp, bottom = 10.dp),
                            )
                        } else {
                            LibrarySourceSwitch(
                                selectedMode = sourceMode,
                                onModeSelected = { mode ->
                                    sourceModeName = mode.name
                                },
                                modifier = Modifier.padding(horizontal = libraryPageInset()),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            if (sourceMode == LibraryViewMode.Cloud) {
                cloudLibraryContent(
                    uiState = cloudUiState,
                    selectedProviderId = selectedProviderId,
                    selectedType = selectedType,
                    selectedCloudItemKey = selectedCloudItemKey,
                    searchQuery = cloudSearchQuery,
                    onSearchQueryChange = {
                        cloudSearchQuery = it
                        selectedCloudItemKey = null
                    },
                    onProviderSelected = {
                        selectedProviderId = it
                        selectedTypeName = null
                        selectedCloudItemKey = null
                    },
                    onTypeSelected = {
                        selectedTypeName = it?.name
                        selectedCloudItemKey = null
                    },
                    onItemSelected = { item ->
                        val playableFiles = item.playableFiles
                        when {
                            playableFiles.size == 1 -> onCloudFilePlay?.invoke(item, playableFiles.first())
                            playableFiles.size > 1 -> selectedCloudItemKey = item.stableKey
                        }
                    },
                    onFileSelected = { item, file -> onCloudFilePlay?.invoke(item, file) },
                    onBackToItems = { selectedCloudItemKey = null },
                    onRefresh = { CloudLibraryRepository.refresh() },
                    onConnectCloudClick = onConnectCloudClick,
                )
            } else {
                when {
                    !uiState.isLoaded || (uiState.isLoading && uiState.sections.isEmpty()) -> {
                        if (displaySettings.layoutMode == LibraryLayoutMode.VERTICAL) {
                            libraryVerticalSkeletonItems(gridColumns, horizontalPadding = pageInset)
                        } else {
                            items(3) {
                                HomeSkeletonRow(
                                    horizontalPadding = libraryPageInset(),
                                )
                            }
                        }
                    }

                    !uiState.errorMessage.isNullOrBlank() && uiState.sections.isEmpty() -> {
                        item {
                            if (networkStatusUiState.isOfflineLike) {
                                NuvioNetworkOfflineCard(
                                    condition = networkStatusUiState.condition,
                                    modifier = Modifier.padding(horizontal = libraryPageInset()),
                                    onRetry = retryLibraryLoad,
                                )
                            } else {
                                HomeEmptyStateCard(
                                    modifier = Modifier.padding(horizontal = libraryPageInset()),
                                    title = when (uiState.sourceMode) {
                                        LibrarySourceMode.LOCAL -> stringResource(Res.string.library_load_failed)
                                        LibrarySourceMode.TRAKT -> stringResource(Res.string.library_trakt_load_failed)
                                        LibrarySourceMode.SIMKL -> stringResource(Res.string.library_simkl_load_failed)
                                        LibrarySourceMode.MDBLIST -> stringResource(Res.string.library_mdblist_load_failed)
                                    },
                                    message = uiState.errorMessage.orEmpty(),
                                    actionLabel = stringResource(Res.string.action_retry),
                                    onActionClick = retryLibraryLoad,
                                )
                            }
                        }
                    }

                    uiState.sections.isEmpty() -> {
                        item {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = libraryPageInset()),
                                title = when (uiState.sourceMode) {
                                    LibrarySourceMode.LOCAL -> stringResource(Res.string.library_empty_title)
                                    LibrarySourceMode.TRAKT -> stringResource(Res.string.library_trakt_empty_title)
                                    LibrarySourceMode.SIMKL -> stringResource(Res.string.library_simkl_empty_title)
                                    LibrarySourceMode.MDBLIST -> stringResource(Res.string.library_mdblist_empty_title)
                                },
                                message = when (uiState.sourceMode) {
                                    LibrarySourceMode.LOCAL -> stringResource(Res.string.library_empty_message)
                                    LibrarySourceMode.TRAKT -> stringResource(Res.string.library_trakt_empty_message)
                                    LibrarySourceMode.SIMKL -> stringResource(Res.string.library_simkl_empty_message)
                                    LibrarySourceMode.MDBLIST -> stringResource(Res.string.library_mdblist_empty_message)
                                },
                            )
                        }
                    }

                    else -> {
                        // Desktop keeps these controls in the pinned toolbar and shows a count here.
                        if (isDesktop) item(key = "library-results-count") {
                            // An empty search shows its own message instead of "0 / 48 titles".
                            if (desktopShownCount > 0) LibraryResultsCount(
                                shown = desktopShownCount,
                                total = desktopTotalCount,
                                modifier = libraryContentTransitionModifier().padding(horizontal = libraryPageInset()),
                            )
                        } else item(
                            key = "library-saved-controls:${uiState.sourceMode}:" +
                                "${displaySettings.layoutMode}:$effectiveSortOption",
                        ) {
                            LibrarySavedControls(
                                layoutMode = displaySettings.layoutMode,
                                sourceMode = uiState.sourceMode,
                                sortOption = effectiveSortOption,
                                verticalProjection = verticalProjection,
                                onSectionSelected = { sectionKey ->
                                    selectedLibrarySectionKey = sectionKey
                                    selectedLibraryType = null
                                },
                                onTypeSelected = { type -> selectedLibraryType = type },
                                onSortSelected = LibraryDisplaySettingsRepository::setSortOption,
                                modifier = libraryContentTransitionModifier()
                                    .padding(horizontal = libraryPageInset()),
                            )
                        }
                        if (isDesktop && desktopShownCount == 0 && desktopQuery.isNotEmpty()) item(key = "library-no-matches") {
                            LibraryNoMatches(
                                query = desktopQuery,
                                onClear = { librarySearchQuery = "" },
                                modifier = Modifier.padding(horizontal = libraryPageInset()),
                            )
                        } else when (displaySettings.layoutMode) {
                            LibraryLayoutMode.HORIZONTAL -> librarySections(
                                displaySections = librarySectionsDisplay,
                                watchedKeys = watchedUiState.watchedKeys,
                                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                                sortOption = effectiveSortOption,
                                onPosterClick = onPosterClick,
                                onSectionViewAllClick = onSectionViewAllClick,
                                onPosterLongClick = onPosterLongClick,
                                onDisintegrated = disintegration::onExited,
                            )
                            LibraryLayoutMode.VERTICAL -> libraryVerticalContent(
                                projection = visibleVerticalProjection,
                                columns = gridColumns,
                                horizontalPadding = pageInset,
                                watchedKeys = watchedUiState.watchedKeys,
                                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                                onPosterClick = onPosterClick,
                                onPosterLongClick = onPosterLongClick,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private fun LazyListScope.cloudLibraryContent(
    uiState: CloudLibraryUiState,
    selectedProviderId: String?,
    selectedType: CloudLibraryItemType?,
    selectedCloudItemKey: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onProviderSelected: (String?) -> Unit,
    onTypeSelected: (CloudLibraryItemType?) -> Unit,
    onItemSelected: (CloudLibraryItem) -> Unit,
    onFileSelected: (CloudLibraryItem, CloudLibraryFile) -> Unit,
    onBackToItems: () -> Unit,
    onRefresh: () -> Unit,
    onConnectCloudClick: (() -> Unit)?,
) {
    when {
        !uiState.isLoaded -> {
            cloudLibrarySkeletonItems()
        }

        !uiState.isEnabled -> {
            item(key = "cloud-library-disabled") {
                CloudLibraryStateCard(
                    icon = Icons.Rounded.CloudOff,
                    title = stringResource(Res.string.cloud_library_disabled_title),
                    message = stringResource(Res.string.cloud_library_disabled_message),
                    actionLabel = stringResource(Res.string.cloud_library_disabled_action),
                    onAction = onConnectCloudClick,
                )
            }
        }

        !uiState.hasConnectedProvider -> {
            item(key = "cloud-library-connect") {
                CloudLibraryStateCard(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(Res.string.cloud_library_connect_title),
                    message = stringResource(Res.string.cloud_library_connect_message),
                    actionLabel = stringResource(Res.string.cloud_library_connect_action),
                    onAction = onConnectCloudClick,
                )
            }
        }

        else -> {
            val providerItems = uiState.items
                .filter { item -> selectedProviderId == null || item.providerId == selectedProviderId }
            val availableTypes = providerItems
                .map { item -> item.type }
                .distinct()
                .sortedBy { type -> type.ordinal }
            val effectiveSelectedType = selectedType?.takeIf { type -> type in availableTypes }
            val typeFilteredItems = providerItems
                .filter { item -> effectiveSelectedType == null || item.type == effectiveSelectedType }
            // Local filter over the already-loaded library. Matches the item name or any of its
            // file names, since the useful identifier is often in the filename, not the title.
            val trimmedQuery = searchQuery.trim()
            val hasActiveFilter = selectedProviderId != null || effectiveSelectedType != null || trimmedQuery.isNotEmpty()
            val filteredItems = if (trimmedQuery.isEmpty()) {
                typeFilteredItems
            } else {
                typeFilteredItems.filter { item ->
                    item.name.contains(trimmedQuery, ignoreCase = true) ||
                        item.files.any { file -> file.name.contains(trimmedQuery, ignoreCase = true) }
                }
            }
            val selectedItem = filteredItems.firstOrNull { it.stableKey == selectedCloudItemKey }

            if (selectedItem != null) {
                item(key = "cloud-library-files-${selectedItem.stableKey}") {
                    CloudLibraryFilePicker(
                        item = selectedItem,
                        onBack = onBackToItems,
                        onFileSelected = { file -> onFileSelected(selectedItem, file) },
                    )
                }
            } else {
                item(key = "cloud-library-search") {
                    CloudLibrarySearchField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        modifier = Modifier.padding(horizontal = libraryPageInset()).padding(top = 4.dp),
                    )
                }
                // Provider and type pickers only matter when there is more than one of either.
                if (uiState.providers.size > 1 || availableTypes.size > 1 || hasActiveFilter) {
                    item(key = "cloud-library-filters") {
                        CloudLibraryFilters(
                            uiState = uiState,
                            selectedProviderId = selectedProviderId,
                            selectedType = effectiveSelectedType,
                            availableTypes = availableTypes,
                            onProviderSelected = onProviderSelected,
                            onTypeSelected = onTypeSelected,
                            modifier = Modifier.padding(horizontal = libraryPageInset()),
                        )
                    }
                }
                item(key = "cloud-library-section-header") {
                    val providerLabel = uiState.providers
                        .firstOrNull { it.providerId == selectedProviderId }?.providerName
                        ?: uiState.providers.singleOrNull()?.providerName
                        ?: stringResource(Res.string.cloud_library_provider_all)
                    NuvioSectionHeader(
                        title = stringResource(Res.string.cloud_library_provider_file_count, providerLabel, filteredItems.size)
                            .uppercase(),
                        actionLabel = stringResource(Res.string.action_refresh),
                        onAction = onRefresh,
                        modifier = Modifier.padding(horizontal = libraryPageInset()).padding(top = 6.dp),
                    )
                }

                val visibleProviderStates = uiState.providers.filter { providerState ->
                    selectedProviderId == null || providerState.providerId == selectedProviderId
                }
                val failedProviderStates = visibleProviderStates.filter { providerState ->
                    !providerState.errorMessage.isNullOrBlank() && providerState.items.isEmpty()
                }
                failedProviderStates.forEach { providerState ->
                    item(key = "cloud-error-${providerState.providerId}") {
                        CloudLibraryStateCard(
                            icon = Icons.Rounded.ErrorOutline,
                            tone = NuvioIconChipTone.Warning,
                            title = stringResource(Res.string.cloud_library_load_failed, providerState.providerName),
                            message = providerState.errorMessage.orEmpty(),
                            secondaryActionLabel = stringResource(Res.string.action_retry),
                            onSecondaryAction = onRefresh,
                        )
                    }
                }

                if (uiState.isRefreshing && filteredItems.isEmpty()) {
                    cloudLibrarySkeletonItems(includeToolbar = false)
                } else if (filteredItems.isEmpty() && failedProviderStates.isEmpty()) {
                    item(key = "cloud-library-empty") {
                        CloudLibraryStateCard(
                            icon = if (hasActiveFilter) Icons.Rounded.SearchOff else Icons.Rounded.Cloud,
                            tone = NuvioIconChipTone.Neutral,
                            title = stringResource(
                                if (hasActiveFilter) Res.string.cloud_library_no_matches_title else Res.string.cloud_library_empty_title,
                            ),
                            message = stringResource(
                                if (hasActiveFilter) Res.string.cloud_library_no_matches_message else Res.string.cloud_library_empty_message,
                            ),
                            secondaryActionLabel = if (hasActiveFilter) null else stringResource(Res.string.action_retry),
                            onSecondaryAction = if (hasActiveFilter) null else onRefresh,
                        )
                    }
                } else {
                    itemsIndexed(
                        items = filteredItems,
                        key = { _, item -> item.stableKey },
                    ) { index, item ->
                        CloudLibraryRow(
                            item = item,
                            isFirst = index == 0,
                            isLast = index == filteredItems.lastIndex,
                            onClick = { onItemSelected(item) },
                        )
                    }
                }
            }
        }
    }
}

/** A cloud state (off, not connected, failed, empty) as a card with an icon chip and one action. */
@Composable
private fun CloudLibraryStateCard(
    icon: ImageVector,
    title: String,
    message: String,
    tone: NuvioIconChipTone = NuvioIconChipTone.Accent,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    NuvioSurfaceCard(modifier = Modifier.padding(horizontal = libraryPageInset()), outlined = true) {
        NuvioEmptyBlock(
            icon = icon,
            title = title,
            message = message,
            tone = tone,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            if (actionLabel != null && onAction != null) {
                NuvioPrimaryButton(text = actionLabel, onClick = onAction)
            } else if (secondaryActionLabel != null && onSecondaryAction != null) {
                DialogButton(text = secondaryActionLabel, onClick = onSecondaryAction)
            }
        }
    }
}

@Composable
private fun CloudLibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NuvioInputField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(Res.string.cloud_library_search_label),
        modifier = modifier,
        leadingIcon = Icons.Rounded.Search,
        minHeight = 48.dp,
        trailingContent = if (query.isEmpty()) {
            null
        } else {
            {
                NuvioSquareIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.compose_search_clear),
                    onClick = { onQueryChange("") },
                    size = 36.dp,
                    style = NuvioSquareIconButtonStyle.Ghost,
                    iconSize = 18.dp,
                )
            }
        },
    )
}

private fun LazyListScope.cloudLibrarySkeletonItems(includeToolbar: Boolean = true) {
    if (includeToolbar) {
        item(key = "cloud-library-skeleton-toolbar") {
            CloudLibrarySkeletonToolbar(
                modifier = Modifier.padding(horizontal = libraryPageInset()),
            )
        }
    }
    items(3) { index ->
        CloudLibrarySkeletonRow(isFirst = index == 0, isLast = index == 2)
    }
}

@Composable
private fun LibrarySourceSwitch(
    selectedMode: LibraryViewMode,
    onModeSelected: (LibraryViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    NuvioSegmentedControl(
        segments = listOf(
            NuvioSegment(LibraryViewMode.Saved, stringResource(Res.string.library_source_saved)),
            NuvioSegment(LibraryViewMode.Cloud, stringResource(Res.string.library_source_cloud)),
        ),
        selected = selectedMode,
        onSelect = onModeSelected,
        fillWidth = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Provider and type pickers for the cloud list. */
@Composable
private fun CloudLibraryFilters(
    uiState: CloudLibraryUiState,
    selectedProviderId: String?,
    selectedType: CloudLibraryItemType?,
    availableTypes: List<CloudLibraryItemType>,
    onProviderSelected: (String?) -> Unit,
    onTypeSelected: (CloudLibraryItemType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerOptions = buildList {
        add(NuvioDropdownOption(key = "", label = stringResource(Res.string.cloud_library_provider_all)))
        addAll(
            uiState.providers.map { provider ->
                NuvioDropdownOption(
                    key = provider.providerId,
                    label = provider.providerName,
                )
            },
        )
    }
    val typeOptions = buildList {
        add(NuvioDropdownOption(key = "", label = stringResource(Res.string.cloud_library_type_all)))
        addAll(
            availableTypes.map { type ->
                NuvioDropdownOption(
                    key = type.name,
                    label = cloudLibraryTypeLabel(type),
                )
            },
        )
    }
    val selectedProviderName = uiState.providers
        .firstOrNull { provider -> provider.providerId == selectedProviderId }
        ?.providerName
        ?: stringResource(Res.string.cloud_library_provider_all)
    val selectedTypeLabel = selectedType?.let { type -> cloudLibraryTypeLabel(type) }
        ?: stringResource(Res.string.cloud_library_type_all)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioDropdownChip(
            title = stringResource(Res.string.cloud_library_select_provider),
            label = selectedProviderName,
            selectedKey = selectedProviderId.orEmpty(),
            options = providerOptions,
            enabled = providerOptions.size > 2,
            onSelected = { option ->
                onProviderSelected(option.key.ifBlank { null })
            },
        )
        NuvioDropdownChip(
            title = stringResource(Res.string.cloud_library_select_type),
            label = selectedTypeLabel,
            selectedKey = selectedType?.name.orEmpty(),
            options = typeOptions,
            enabled = typeOptions.size > 2 || selectedType != null,
            onSelected = { option ->
                val type = option.key
                    .takeIf { it.isNotBlank() }
                    ?.let(CloudLibraryItemType::valueOf)
                onTypeSelected(type)
            },
        )
    }
}

/** One cloud item in the lazy file group: icon chip, name, size and state, and a chevron. */
@Composable
private fun CloudLibraryRow(
    item: CloudLibraryItem,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val playableCount = item.playableFiles.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = libraryPageInset())
            .nuvioGroupSegment(
                isFirst = isFirst,
                isLast = isLast,
                containerColor = tokens.colors.surface,
                borderColor = tokens.colors.borderSubtle,
                radius = if (isDesktop) NuvioTokens.Radius.xl else NuvioTokens.Radius.lg,
            ),
    ) {
        if (!isFirst) NuvioGroupDivider()
        NuvioGroupRow(
            title = item.name,
            description = cloudLibraryMetaLine(item),
            onClick = onClick,
            enabled = playableCount > 0,
            titleMaxLines = 1,
            descriptionMaxLines = 1,
            leading = {
                NuvioIconChip(
                    icon = if (playableCount > 1) Icons.Rounded.VideoLibrary else Icons.Rounded.Movie,
                    tone = if (playableCount > 0) NuvioIconChipTone.Accent else NuvioIconChipTone.Neutral,
                )
            },
            meta = {
                item.progressFraction?.takeIf { it in 0f..0.999f }?.let { progress ->
                    NuvioProgressBar(
                        progress = progress,
                        modifier = Modifier.padding(top = 6.dp),
                        trackColor = tokens.colors.textPrimary.copy(alpha = tokens.opacity.pressed),
                        fillColor = tokens.colors.accent,
                    )
                }
            },
            trailing = {
                if (playableCount > 0) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(Res.string.action_play),
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun CloudLibraryFilePicker(
    item: CloudLibraryItem,
    onBack: () -> Unit,
    onFileSelected: (CloudLibraryFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    NuvioGroup(
        modifier = modifier.padding(horizontal = libraryPageInset()),
        isTablet = isDesktop,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NuvioSquareIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(Res.string.action_back),
                onClick = onBack,
                size = 36.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.cloud_library_file_picker_title),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                )
            }
        }
        val files = item.playableFiles
        if (files.isEmpty()) {
            NuvioGroupDivider(startInset = 0.dp)
            NuvioEmptyBlock(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(Res.string.cloud_library_no_files_title),
                message = stringResource(Res.string.cloud_library_no_files_message),
                tone = NuvioIconChipTone.Neutral,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            files.forEach { file ->
                NuvioGroupDivider(startInset = 0.dp)
                NuvioGroupRow(
                    title = file.name,
                    description = file.sizeBytes?.let { size -> formatCloudBytes(size) },
                    onClick = { onFileSelected(file) },
                    titleMaxLines = 2,
                    leading = { NuvioIconChip(Icons.AutoMirrored.Rounded.InsertDriveFile) },
                    trailing = {
                        NuvioSquareIconButton(
                            icon = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(Res.string.cloud_library_play_file),
                            onClick = { onFileSelected(file) },
                            style = NuvioSquareIconButtonStyle.AccentSoft,
                            iconSize = 18.dp,
                        )
                    },
                )
            }
        }
    }
}

/** "1.4 GB · Cached" or "3 playable files · Torrents" for the second line of a cloud row. */
@Composable
private fun cloudLibraryMetaLine(item: CloudLibraryItem): String {
    val playableCount = item.playableFiles.size
    val fileLine = when (playableCount) {
        0 -> stringResource(Res.string.cloud_library_no_playable_files)
        1 -> null
        else -> stringResource(Res.string.cloud_library_playable_file_count, playableCount)
    }
    return listOfNotNull(
        item.sizeBytes?.let(::formatCloudBytes),
        item.status?.toDisplayStatus() ?: stringResource(Res.string.cloud_library_status_ready).takeIf { playableCount > 0 },
        fileLine,
        cloudLibraryTypeLabel(item.type),
        item.progressFraction?.takeIf { it in 0f..0.999f }?.let { "${(it * 100f).toInt()}%" },
    ).distinct().joinToString(" · ")
}

@Composable
private fun cloudLibraryTypeLabel(type: CloudLibraryItemType): String =
    when (type) {
        CloudLibraryItemType.Torrent -> stringResource(Res.string.cloud_library_type_torrents)
        CloudLibraryItemType.Usenet -> stringResource(Res.string.cloud_library_type_usenet)
        CloudLibraryItemType.WebDownload -> stringResource(Res.string.cloud_library_type_web)
        CloudLibraryItemType.File -> stringResource(Res.string.cloud_library_type_files)
    }

private fun formatCloudBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}

private fun String.toDisplayStatus(): String =
    replace('_', ' ')
        .lowercase()
        .replaceFirstChar { it.titlecase() }

@Composable
private fun CloudLibrarySkeletonToolbar(
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 48.dp, cornerRadius = 12.dp)
        SkeletonBlock(width = 140.dp, height = 14.dp, cornerRadius = 6.dp)
    }
}

@Composable
private fun CloudLibrarySkeletonRow(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = libraryPageInset())
            .nuvioGroupSegment(
                isFirst = isFirst,
                isLast = isLast,
                containerColor = tokens.colors.surface,
                borderColor = tokens.colors.borderSubtle,
            ),
    ) {
        if (!isFirst) NuvioGroupDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBlock(width = 36.dp, height = 36.dp, cornerRadius = 12.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.74f), height = 16.dp, cornerRadius = 6.dp)
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.5f), height = 12.dp, cornerRadius = 6.dp)
            }
        }
    }
}

private enum class LibraryViewMode {
    Saved,
    Cloud,
}

private fun LazyListScope.librarySections(
    displaySections: List<LibraryDisplaySection>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    sortOption: LibrarySortOption,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onSectionViewAllClick: ((LibrarySection, LibrarySortOption) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
    onDisintegrated: (String) -> Unit,
) {
    items(
        items = displaySections,
        key = { section -> "library-horizontal:${section.type}" },
    ) { section ->
        NuvioShelfSection(
            title = section.displayTitle,
            entries = section.previewEntries,
            modifier = libraryContentTransitionModifier(),
            headerHorizontalPadding = libraryPageInset(),
            rowContentPadding = PaddingValues(horizontal = libraryPageInset()),
            onViewAllClick = section.source
                ?.takeIf { it.items.size > LIBRARY_SECTION_PREVIEW_LIMIT }
                ?.let { source -> onSectionViewAllClick?.let { { it(source, sortOption) } } },
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { entry -> entry.globalKey },
            animatePlacement = true,
        ) { entry ->
            val item = entry.item
            val posterItem = item.toMetaPreview()
            val entrySource = entry.section
            DisintegratingContainer(
                disintegrating = entry.exiting,
                onDisintegrated = { onDisintegrated(entry.globalKey) },
            ) {
                HomePosterCard(
                    item = posterItem,
                    isWatched = WatchingState.isPosterWatched(
                        watchedKeys = watchedKeys,
                        item = posterItem,
                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    ),
                    onClick = if (entry.exiting) null else onPosterClick?.let { { it(item) } },
                    onLongClick = if (entry.exiting || entrySource == null) {
                        null
                    } else {
                        onPosterLongClick?.let { { it(item, entrySource) } }
                    },
                )
            }
        }
    }
}

private const val LIBRARY_SECTION_PREVIEW_LIMIT = 18

private data class LibraryDisplayEntry(
    val globalKey: String,
    val item: LibraryItem,
    val section: LibrarySection?,
    val exiting: Boolean,
)

private data class LibraryDisplaySection(
    val source: LibrarySection?,
    val type: String,
    val displayTitle: String,
    val previewEntries: List<LibraryDisplayEntry>,
)

private class LibraryExitingEntry(
    val item: LibraryItem,
    val sectionType: String,
    val sectionTitle: String,
    val index: Int,
)

private class LibraryDisintegrationHolder {
    private val tracker = ScopedDisintegrationTracker<LibrarySourceMode, String, LibraryExitingEntry> { entry ->
        librarySectionItemKey(entry.sectionType, entry.item)
    }

    fun onExited(globalKey: String) {
        tracker.onDisintegrated(globalKey)
    }

    fun reset() {
        tracker.reset()
    }

    fun sync(
        sourceMode: LibrarySourceMode,
        sections: List<LibrarySection>,
        previewLimit: Int,
        request: DisintegrationRequest<String>?,
    ): List<LibraryDisplaySection> {
        val current = ArrayList<LibraryExitingEntry>()
        sections.forEach { section ->
            section.items.take(previewLimit).forEachIndexed { index, item ->
                current += LibraryExitingEntry(item, section.type, section.displayTitle, index)
            }
        }
        val exitingBySection = tracker.sync(sourceMode, current, request)
            .asSequence()
            .filter { entry -> entry.exiting }
            .map { entry -> entry.item }
            .groupBy { entry -> entry.sectionType }
        val seenTypes = HashSet<String>(sections.size)
        val result = ArrayList<LibraryDisplaySection>(sections.size + 1)

        for (section in sections) {
            seenTypes += section.type
            val entries = ArrayList<LibraryDisplayEntry>(previewLimit + 1)
            section.items.take(previewLimit).forEach { item ->
                entries += LibraryDisplayEntry(
                    globalKey = librarySectionItemKey(section.type, item),
                    item = item,
                    section = section,
                    exiting = false,
                )
            }
            exitingBySection[section.type]?.sortedBy { it.index }?.forEach { ex ->
                val key = librarySectionItemKey(section.type, ex.item)
                if (entries.none { it.globalKey == key }) {
                    entries.add(
                        ex.index.coerceIn(0, entries.size),
                        LibraryDisplayEntry(key, ex.item, section, exiting = true),
                    )
                }
            }
            result += LibraryDisplaySection(section, section.type, section.displayTitle, entries)
        }

        for ((type, list) in exitingBySection) {
            if (type in seenTypes) continue
            val sorted = list.sortedBy { it.index }
            val entries = sorted.map { ex ->
                LibraryDisplayEntry(librarySectionItemKey(type, ex.item), ex.item, section = null, exiting = true)
            }
            result += LibraryDisplaySection(null, type, sorted.first().sectionTitle, entries)
        }

        return result
    }
}

/** Horizontal page inset: matches the desktop top bar, 16 dp elsewhere. */
private val LocalLibraryPageInset = staticCompositionLocalOf { 16.dp }

@Composable
private fun libraryPageInset(): Dp = LocalLibraryPageInset.current

/** Cloud rows line up with the page edges on desktop and keep their slimmer inset elsewhere. */
@Composable
private fun cloudRowInset(): Dp = if (isDesktop) libraryPageInset() else 12.dp
