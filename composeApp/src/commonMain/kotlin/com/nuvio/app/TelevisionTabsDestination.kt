package com.nuvio.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.myanimelist.MyAnimeListTabVisible
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.settings.AppBrandWordmark
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val TvNavigationWidth = 212.dp
private val TvPagePadding = 48.dp
private val TvPosterWidth = 168.dp

/**
 * Leanback-style navigation with obvious D-pad focus. The player, catalog and account
 * implementations stay shared with the rest of Starlit; only the root UI is different.
 */
@Composable
internal fun TelevisionTabsDestination(
    selectedTab: AppScreenTab,
    initialHomeReady: Boolean,
    rootRouteActive: Boolean,
    requests: AppTabRequests,
    state: AppTabState,
    actions: AppTabActions,
    onTabSelected: (AppScreenTab) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val initialFocus = remember { FocusRequester() }
    val tabs = buildList {
        add(AppScreenTab.Home)
        add(AppScreenTab.Search)
        add(AppScreenTab.Library)
        if (MyAnimeListTabVisible()) add(AppScreenTab.MyAnimeList)
        add(AppScreenTab.Settings)
    }
    LaunchedEffect(rootRouteActive) {
        if (rootRouteActive) initialFocus.requestFocus()
    }

    Row(Modifier.fillMaxSize().background(tokens.colors.background)) {
        Column(
            modifier = Modifier
                .width(TvNavigationWidth)
                .fillMaxHeight()
                .background(tokens.colors.background)
                .padding(start = 26.dp, end = 12.dp, top = 48.dp, bottom = 28.dp),
        ) {
            AppBrandWordmark(
                contentDescription = "Starlit TV",
                modifier = Modifier.fillMaxWidth().height(42.dp),
            )
            Spacer(Modifier.height(58.dp))
            tabs.forEachIndexed { index, tab ->
                TelevisionNavigationItem(
                    title = when (tab) {
                        AppScreenTab.Home -> stringResource(Res.string.compose_nav_home)
                        AppScreenTab.Search -> stringResource(Res.string.compose_nav_search)
                        AppScreenTab.Library -> stringResource(Res.string.compose_nav_library)
                        AppScreenTab.MyAnimeList -> "MyAnimeList"
                        else -> stringResource(Res.string.compose_nav_settings)
                    },
                    selected = selectedTab == tab || (tab == AppScreenTab.Library && selectedTab == AppScreenTab.WatchLater),
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                AppScreenTab.Home -> Icons.Default.Home
                                AppScreenTab.Search -> Icons.Default.Search
                                AppScreenTab.Library -> Icons.Default.VideoLibrary
                                AppScreenTab.MyAnimeList -> Icons.AutoMirrored.Filled.PlaylistAddCheck
                                else -> Icons.Default.Settings
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                    onClick = { onTabSelected(tab) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Starlit TV",
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (selectedTab) {
                AppScreenTab.Home -> TelevisionHome(
                    actions = actions,
                    loading = !initialHomeReady,
                )
                AppScreenTab.Search -> TelevisionSearch(actions.onPosterClick)
                AppScreenTab.Library -> TelevisionLibrary(actions.onLibraryPosterClick)
                AppScreenTab.Settings -> {
                    var showAllSettings by rememberSaveable { mutableStateOf(false) }
                    if (showAllSettings) {
                        Column(Modifier.fillMaxSize()) {
                            TelevisionSettingsAction(
                                "← TV quick settings",
                                "Return to television shortcuts",
                                onClick = { showAllSettings = false },
                            )
                            AppTabHost(
                                selectedTab = AppScreenTab.Settings,
                                requests = requests,
                                state = state.copy(topChromePadding = 24.dp),
                                actions = actions,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        TelevisionSettings(actions, onAllSettings = { showAllSettings = true })
                    }
                }
                else -> AppTabHost(
                    selectedTab = selectedTab,
                    requests = requests,
                    state = state.copy(topChromePadding = TvPagePadding),
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun TelevisionNavigationItem(
    title: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(58.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = when {
            focused -> colors.accent
            selected -> colors.accent.copy(alpha = .18f)
            else -> Color.Transparent
        },
        border = if (selected && !focused) BorderStroke(1.dp, colors.accent.copy(alpha = .6f)) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                color = if (focused) colors.onAccent else colors.textPrimary,
            )
        }
    }
}

@Composable
private fun TelevisionHome(actions: AppTabActions, loading: Boolean) {
    val state by HomeRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isLoading, state.sections, state.errorMessage) {
        if (!state.isLoading) actions.onInitialHomeContentRendered()
    }
    val hero = state.heroItems.firstOrNull() ?: state.sections.firstOrNull()?.items?.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 38.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        item(key = "home-heading") {
            Text(
                text = stringResource(Res.string.compose_nav_home),
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (hero != null) {
            item(key = "featured") { TelevisionHero(hero, onOpen = { actions.onPosterClick?.invoke(hero) }) }
        }
        if (state.sections.isEmpty()) {
            item(key = "home-status") {
                TelevisionStatus(
                    text = when {
                        state.isLoading || loading -> "Loading your catalogs…"
                        state.errorMessage != null -> "Could not load catalogs. Check your network or add-ons in Settings."
                        else -> "No catalogs yet. Add a source in Settings to start browsing."
                    },
                )
            }
        }
        items(state.sections, key = { "catalog-${it.key}" }) { section ->
            TelevisionMediaRow(section.title, section.items, onOpen = { actions.onPosterClick?.invoke(it) })
        }
    }
}

@Composable
private fun TelevisionHero(item: MetaPreview, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().height(278.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(18.dp),
        color = colors.background,
        border = if (focused) BorderStroke(3.dp, colors.accent) else null,
    ) {
        Box(Modifier.fillMaxSize()) {
            val backdrop = item.banner ?: item.landscapePoster ?: item.poster
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(colors.background, colors.background.copy(alpha = .83f), Color.Transparent),
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.CenterStart).padding(28.dp).fillMaxWidth(.68f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.name,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(item.releaseInfo, item.imdbRating?.let { "IMDb $it" }).joinToString("  •  ")
                if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = .82f), fontSize = 15.sp)
                item.description?.let { description ->
                    Text(
                        text = description,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = .86f),
                        fontSize = 16.sp,
                    )
                }
                Text(
                    "View details  ›",
                    color = if (focused) colors.accent else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TelevisionMediaRow(title: String, items: List<MetaPreview>, onOpen: (MetaPreview) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontSize = 23.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(end = 32.dp, bottom = 6.dp),
        ) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                TelevisionPoster(item = item, onOpen = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun TelevisionPoster(item: MetaPreview, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        modifier = Modifier.width(TvPosterWidth).height(274.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = colors.background,
        border = if (focused) BorderStroke(3.dp, colors.accent) else BorderStroke(1.dp, colors.textPrimary.copy(alpha = .12f)),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp))) {
                if (item.poster != null) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(colors.accent.copy(alpha = .10f)))
                }
            }
            Text(
                text = item.name,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TelevisionLibrary(onOpen: ((com.nuvio.app.features.library.LibraryItem) -> Unit)?) {
    val state by LibraryRepository.uiState.collectAsStateWithLifecycle()
    val sections = state.sections.ifEmpty {
        if (state.items.isEmpty()) emptyList() else listOf(LibrarySection("all", "My Library", state.items))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 38.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        item {
            Text(stringResource(Res.string.compose_nav_library), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.textPrimary)
        }
        if (sections.isEmpty()) {
            item { TelevisionStatus(if (state.isLoading) "Loading your library…" else "Your library is empty. Browse Home to find something to watch.") }
        }
        items(sections, key = { "library-${it.type}" }) { section ->
            val metadata = section.items.map { it.toMetaPreview() }
            val libraryByKey = section.items.associateBy { "${it.type}:${it.id}" }
            TelevisionMediaRow(section.displayTitle, metadata) { preview ->
                libraryByKey["${preview.type}:${preview.id}"]?.let { onOpen?.invoke(it) }
            }
        }
    }
}

@Composable
private fun TelevisionSearch(onOpen: ((MetaPreview) -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    val addons by AddonRepository.uiState.collectAsStateWithLifecycle()
    val results by SearchRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(query, addons.addons) {
        if (query.isBlank()) {
            SearchRepository.clear()
        } else {
            delay(350)
            SearchRepository.search(query, addons.addons)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 38.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item { Text(stringResource(Res.string.compose_nav_search), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.textPrimary) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search films, series and anime") },
                modifier = Modifier.fillMaxWidth(.92f),
            )
        }
        if (query.isBlank()) {
            item { TelevisionStatus("Select the search field and use your TV's on-screen keyboard.") }
        } else if (results.sections.isEmpty()) {
            item { TelevisionStatus(if (results.isLoading) "Searching…" else "No results. Try a different title or check your add-ons.") }
        }
        items(results.sections, key = { "search-${it.key}" }) { section ->
            TelevisionMediaRow(section.title, section.items, onOpen = { onOpen?.invoke(it) })
        }
    }
}

@Composable
private fun TelevisionSettings(actions: AppTabActions, onAllSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = TvPagePadding, end = TvPagePadding, top = 38.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(Res.string.compose_nav_settings), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        TelevisionSettingsAction("Account and sync", "Manage your Starlit account", actions.onAccountSettingsClick)
        TelevisionSettingsAction("Add-ons", "Configure content catalogs and sources", actions.onAddonsSettingsClick)
        TelevisionSettingsAction("Switch profile", "Choose who is watching", { actions.onSwitchProfile?.invoke() })
        TelevisionSettingsAction("Details display", "Metadata and episode display options", actions.onMetaScreenSettingsClick)
        TelevisionSettingsAction("Continue watching", "Resume playback preferences", actions.onContinueWatchingSettingsClick)
        TelevisionSettingsAction("Downloads", "Manage downloaded media", actions.onDownloadsClick)
        TelevisionSettingsAction("All settings", "Playback, privacy, tracking and appearance", onAllSettings)
        TelevisionSettingsAction("Licenses and credits", "Open-source acknowledgements", actions.onLicensesAttributionsSettingsClick)
    }
}

@Composable
private fun TelevisionSettingsAction(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = if (focused) colors.accent.copy(alpha = .30f) else colors.textPrimary.copy(alpha = .06f),
        border = if (focused) BorderStroke(2.dp, colors.accent) else null,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(subtitle, fontSize = 15.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun TelevisionStatus(text: String) {
    Text(text, fontSize = 18.sp, color = MaterialTheme.nuvio.colors.textSecondary, modifier = Modifier.padding(vertical = 18.dp))
}
