package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_search_clear
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.compose_settings_page_advanced
import nuvio.composeapp.generated.resources.compose_settings_page_notifications
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import nuvio.composeapp.generated.resources.compose_settings_page_tracking
import nuvio.composeapp.generated.resources.compose_settings_root_notifications_description
import nuvio.composeapp.generated.resources.compose_settings_root_tracking_description
import nuvio.composeapp.generated.resources.settings_desktop_about
import nuvio.composeapp.generated.resources.settings_desktop_about_summary
import nuvio.composeapp.generated.resources.settings_desktop_account_summary
import nuvio.composeapp.generated.resources.settings_desktop_addons
import nuvio.composeapp.generated.resources.settings_desktop_addons_summary
import nuvio.composeapp.generated.resources.settings_desktop_advanced_summary
import nuvio.composeapp.generated.resources.settings_desktop_appearance
import nuvio.composeapp.generated.resources.settings_desktop_appearance_summary
import nuvio.composeapp.generated.resources.settings_desktop_continue_watching
import nuvio.composeapp.generated.resources.settings_desktop_continue_watching_summary
import nuvio.composeapp.generated.resources.settings_desktop_debrid
import nuvio.composeapp.generated.resources.settings_desktop_debrid_summary
import nuvio.composeapp.generated.resources.settings_desktop_details_page
import nuvio.composeapp.generated.resources.settings_desktop_details_page_summary
import nuvio.composeapp.generated.resources.settings_desktop_home_screen
import nuvio.composeapp.generated.resources.settings_desktop_home_screen_summary
import nuvio.composeapp.generated.resources.settings_desktop_hover_previews
import nuvio.composeapp.generated.resources.settings_desktop_hover_previews_summary
import nuvio.composeapp.generated.resources.settings_desktop_mdblist
import nuvio.composeapp.generated.resources.settings_desktop_mdblist_summary
import nuvio.composeapp.generated.resources.settings_desktop_playback_summary
import nuvio.composeapp.generated.resources.settings_desktop_plugins_summary
import nuvio.composeapp.generated.resources.settings_desktop_poster_cards
import nuvio.composeapp.generated.resources.settings_desktop_poster_cards_summary
import nuvio.composeapp.generated.resources.settings_desktop_source_list
import nuvio.composeapp.generated.resources.settings_desktop_source_list_summary
import nuvio.composeapp.generated.resources.settings_desktop_switch_profile
import nuvio.composeapp.generated.resources.settings_desktop_tmdb
import nuvio.composeapp.generated.resources.settings_desktop_tmdb_summary
import nuvio.composeapp.generated.resources.settings_desktop_updates
import nuvio.composeapp.generated.resources.settings_desktop_updates_summary
import nuvio.composeapp.generated.resources.settings_group_integrations
import nuvio.composeapp.generated.resources.settings_group_look_and_feel
import nuvio.composeapp.generated.resources.settings_group_sources
import nuvio.composeapp.generated.resources.settings_group_system
import nuvio.composeapp.generated.resources.settings_group_watching
import nuvio.composeapp.generated.resources.settings_group_you
import nuvio.composeapp.generated.resources.settings_search_placeholder
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One item in the desktop settings sidebar. Most open a [SettingsPage]; Collections opens its own
 * editor, and Updates and About are desktop-only screens with no page of their own.
 */
internal enum class DesktopSettingsEntry(
    val labelRes: StringResource,
    val icon: ImageVector,
    val page: SettingsPage? = null,
    val summaryRes: StringResource? = null,
) {
    Account(Res.string.compose_settings_page_account, Icons.Rounded.AccountCircle, SettingsPage.Account, Res.string.settings_desktop_account_summary),
    Tracking(Res.string.compose_settings_page_tracking, Icons.Rounded.Sync, SettingsPage.TraktAuthentication, Res.string.compose_settings_root_tracking_description),
    Playback(Res.string.compose_settings_page_playback, Icons.Rounded.PlayCircle, SettingsPage.Playback, Res.string.settings_desktop_playback_summary),
    ContinueWatching(Res.string.settings_desktop_continue_watching, Icons.Rounded.History, SettingsPage.ContinueWatching, Res.string.settings_desktop_continue_watching_summary),
    Notifications(Res.string.compose_settings_page_notifications, Icons.Rounded.Notifications, SettingsPage.Notifications, Res.string.compose_settings_root_notifications_description),
    Appearance(Res.string.settings_desktop_appearance, Icons.Rounded.Palette, SettingsPage.Appearance, Res.string.settings_desktop_appearance_summary),
    HomeScreen(Res.string.settings_desktop_home_screen, Icons.Rounded.SpaceDashboard, SettingsPage.Homescreen, Res.string.settings_desktop_home_screen_summary),
    Collections(Res.string.collections_header, Icons.Rounded.CollectionsBookmark),
    DetailsPage(Res.string.settings_desktop_details_page, Icons.AutoMirrored.Rounded.Article, SettingsPage.MetaScreen, Res.string.settings_desktop_details_page_summary),
    PosterCards(Res.string.settings_desktop_poster_cards, Icons.Rounded.Style, SettingsPage.PosterCustomization, Res.string.settings_desktop_poster_cards_summary),
    HoverPreviews(Res.string.settings_desktop_hover_previews, Icons.Rounded.Preview, SettingsPage.HoverPreview, Res.string.settings_desktop_hover_previews_summary),
    Addons(Res.string.settings_desktop_addons, Icons.Rounded.Extension, SettingsPage.Addons, Res.string.settings_desktop_addons_summary),
    Plugins(Res.string.compose_settings_page_plugins, Icons.Rounded.Widgets, SettingsPage.Plugins, Res.string.settings_desktop_plugins_summary),
    Debrid(Res.string.settings_desktop_debrid, Icons.Rounded.Cloud, SettingsPage.Debrid, Res.string.settings_desktop_debrid_summary),
    SourceList(Res.string.settings_desktop_source_list, Icons.AutoMirrored.Rounded.ViewList, SettingsPage.Streams, Res.string.settings_desktop_source_list_summary),
    Tmdb(Res.string.settings_desktop_tmdb, Icons.Rounded.Movie, SettingsPage.TmdbEnrichment, Res.string.settings_desktop_tmdb_summary),
    MdbList(Res.string.settings_desktop_mdblist, Icons.Rounded.Stars, SettingsPage.MdbListRatings, Res.string.settings_desktop_mdblist_summary),
    Advanced(Res.string.compose_settings_page_advanced, Icons.Rounded.Tune, SettingsPage.Advanced, Res.string.settings_desktop_advanced_summary),
    Updates(Res.string.settings_desktop_updates, Icons.Rounded.SystemUpdateAlt, summaryRes = Res.string.settings_desktop_updates_summary),
    About(Res.string.settings_desktop_about, Icons.Rounded.Info, summaryRes = Res.string.settings_desktop_about_summary),
}

/** Desktop-only screens shown while the settings page itself is [SettingsPage.Root]. */
internal val DesktopSettingsEntry.isRootScreen: Boolean
    get() = this == DesktopSettingsEntry.Updates || this == DesktopSettingsEntry.About

internal data class DesktopSettingsGroup(
    val labelRes: StringResource,
    val entries: List<DesktopSettingsEntry>,
)

internal fun desktopSettingsGroups(updatesAvailable: Boolean = true): List<DesktopSettingsGroup> = listOf(
    DesktopSettingsGroup(
        Res.string.settings_group_you,
        listOf(DesktopSettingsEntry.Account, DesktopSettingsEntry.Tracking),
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_watching,
        buildList {
            add(DesktopSettingsEntry.Playback)
            add(DesktopSettingsEntry.ContinueWatching)
            if (AppFeaturePolicy.notificationsEnabled) add(DesktopSettingsEntry.Notifications)
        },
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_look_and_feel,
        listOf(
            DesktopSettingsEntry.Appearance,
            DesktopSettingsEntry.HomeScreen,
            DesktopSettingsEntry.Collections,
            DesktopSettingsEntry.DetailsPage,
            DesktopSettingsEntry.PosterCards,
            DesktopSettingsEntry.HoverPreviews,
        ),
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_sources,
        buildList {
            add(DesktopSettingsEntry.Addons)
            if (AppFeaturePolicy.pluginsEnabled) add(DesktopSettingsEntry.Plugins)
            add(DesktopSettingsEntry.Debrid)
            add(DesktopSettingsEntry.SourceList)
        },
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_integrations,
        listOf(DesktopSettingsEntry.Tmdb, DesktopSettingsEntry.MdbList),
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_system,
        buildList {
            add(DesktopSettingsEntry.Advanced)
            if (updatesAvailable) add(DesktopSettingsEntry.Updates)
            add(DesktopSettingsEntry.About)
        },
    ),
)

/** The sidebar entry that shows [this] page, if any. */
internal fun SettingsPage.desktopEntry(): DesktopSettingsEntry? = when (this) {
    // Opened from About, so About stays highlighted.
    SettingsPage.SupportersContributors, SettingsPage.LicensesAttributions -> DesktopSettingsEntry.About
    else -> DesktopSettingsEntry.entries.firstOrNull { it.page == this }
}

/** Name used for [this] page on desktop: the sidebar label when the page has one. */
internal fun SettingsPage.desktopTitleRes(): StringResource =
    DesktopSettingsEntry.entries.firstOrNull { it.page == this }?.labelRes ?: titleRes

/** Sidebar group of [entry], used as the page breadcrumb. */
internal fun desktopGroupLabelFor(entry: DesktopSettingsEntry): StringResource? =
    desktopSettingsGroups().firstOrNull { entry in it.entries }?.labelRes

/** Group for pages reached from a hub rather than listed in the sidebar themselves. */
internal fun SettingsPage.desktopHubGroupLabel(): StringResource? = when (this) {
    SettingsPage.Integrations -> Res.string.settings_group_integrations
    SettingsPage.ContentDiscovery -> Res.string.settings_group_sources
    else -> null
}

@Composable
internal fun DesktopSettingsSidebar(
    groups: List<DesktopSettingsGroup>,
    selectedEntry: DesktopSettingsEntry?,
    query: String,
    onQueryChange: (String) -> Unit,
    onEntryClick: (DesktopSettingsEntry) -> Unit,
    topPadding: Dp,
    startPadding: Dp,
    onSwitchProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding - 10.dp, end = 14.dp, top = topPadding),
    ) {
        if (onSwitchProfile != null) {
            SidebarProfileCard(onSwitchProfile = onSwitchProfile)
            Spacer(Modifier.height(12.dp))
        }
        SettingsSearchField(query = query, onQueryChange = onQueryChange)
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = 6.dp, bottom = 28.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val addonCount by AddonRepository.uiState.collectAsStateWithLifecycle()
                groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(group.labelRes),
                            modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                        group.entries.forEach { entry ->
                            SidebarLink(
                                entry = entry,
                                selected = entry == selectedEntry,
                                badge = if (entry == DesktopSettingsEntry.Addons) {
                                    addonCount.addons.size.takeIf { it > 0 }?.toString()
                                } else {
                                    null
                                },
                                onClick = { onEntryClick(entry) },
                            )
                        }
                    }
                }
            }
            NuvioDesktopVerticalScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SidebarProfileCard(onSwitchProfile: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profile = profileState.activeProfile
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val background by animateColorAsState(
        if (hovered || focused) tokens.colors.overlayHover else tokens.colors.surface,
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_profile_card",
    )
    val email = (authState as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }?.email
    val switchLabel = stringResource(Res.string.settings_desktop_switch_profile)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(NuvioTokens.Border.thin, if (focused) tokens.colors.focusRing else tokens.colors.borderSubtle, shape)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onSwitchProfile)
            .semantics { contentDescription = switchLabel }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActiveProfileMiniAvatar(profile = profile, avatars = avatars, selected = false, size = 34)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = profile?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.compose_nav_profile),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = email ?: switchLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.SwapHoriz,
            contentDescription = null,
            tint = if (hovered) tokens.colors.textPrimary else tokens.colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val placeholder = stringResource(Res.string.settings_search_placeholder)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val border by animateColorAsState(
        if (focused) tokens.colors.borderFocus else tokens.colors.borderSubtle,
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_search_border",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(NuvioTokens.Border.thin, border, shape)
            .padding(start = 12.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                            onQueryChange("")
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.textPrimary),
            )
        }
        if (query.isNotEmpty()) {
            val clearLabel = stringResource(Res.string.compose_search_clear)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SidebarLink(
    entry: DesktopSettingsEntry,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    // Keyboard focus deepens the fill rather than drawing a ring, which also showed after mouse clicks.
    val background by animateColorAsState(
        when {
            selected && focused -> tokens.colors.textPrimary.copy(alpha = 0.15f)
            selected -> tokens.colors.textPrimary.copy(alpha = 0.09f)
            focused -> tokens.colors.textPrimary.copy(alpha = 0.10f)
            hovered -> tokens.colors.overlayHover
            else -> Color.Transparent
        },
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_sidebar_link",
    )
    val contentColor = if (selected || hovered) tokens.colors.textPrimary else tokens.colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = when {
                selected -> tokens.colors.accent
                hovered -> tokens.colors.textSecondary
                else -> tokens.colors.textMuted
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(entry.labelRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            badge != null -> Text(
                text = badge,
                modifier = Modifier.widthIn(min = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            // Collections opens its own editor rather than a page here.
            entry == DesktopSettingsEntry.Collections -> Icon(
                imageVector = Icons.Rounded.ArrowOutward,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Breadcrumb, title and one-line summary at the top of a desktop settings page. */
@Composable
internal fun DesktopSettingsPageHeader(
    title: String,
    eyebrow: String?,
    summary: String?,
    onEyebrowClick: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (eyebrow != null) {
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            Text(
                text = if (onEyebrowClick != null) "‹  $eyebrow" else eyebrow,
                modifier = if (onEyebrowClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onEyebrowClick)
                } else {
                    Modifier
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (hovered) tokens.colors.textPrimary else tokens.colors.textMuted,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                modifier = Modifier.widthIn(max = 680.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
    }
}
