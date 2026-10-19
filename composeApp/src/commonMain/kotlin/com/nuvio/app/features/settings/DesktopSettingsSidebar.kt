package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.compose_settings_category_about
import nuvio.composeapp.generated.resources.compose_settings_root_switch_profile_title
import nuvio.composeapp.generated.resources.settings_group_integrations
import nuvio.composeapp.generated.resources.settings_group_look_and_feel
import nuvio.composeapp.generated.resources.settings_group_sources
import nuvio.composeapp.generated.resources.settings_group_system
import nuvio.composeapp.generated.resources.settings_group_watching
import nuvio.composeapp.generated.resources.settings_group_you
import nuvio.composeapp.generated.resources.settings_search_placeholder
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One sidebar group: every desktop settings page is listed directly, one click away. */
internal data class DesktopSettingsGroup(
    val labelRes: StringResource,
    val pages: List<SettingsPage>,
)

internal fun desktopSettingsGroups(): List<DesktopSettingsGroup> = listOf(
    DesktopSettingsGroup(Res.string.settings_group_you, listOf(SettingsPage.Account, SettingsPage.TraktAuthentication)),
    DesktopSettingsGroup(
        Res.string.settings_group_watching,
        buildList {
            add(SettingsPage.Playback)
            add(SettingsPage.Streams)
            add(SettingsPage.ContinueWatching)
            if (AppFeaturePolicy.notificationsEnabled) add(SettingsPage.Notifications)
        },
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_look_and_feel,
        listOf(
            SettingsPage.Appearance,
            SettingsPage.Homescreen,
            SettingsPage.MetaScreen,
            SettingsPage.PosterCustomization,
            SettingsPage.HoverPreview,
        ),
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_sources,
        buildList {
            add(SettingsPage.Addons)
            if (AppFeaturePolicy.pluginsEnabled) add(SettingsPage.Plugins)
            add(SettingsPage.Debrid)
        },
    ),
    DesktopSettingsGroup(Res.string.settings_group_integrations, listOf(SettingsPage.TmdbEnrichment, SettingsPage.MdbListRatings)),
    DesktopSettingsGroup(
        Res.string.settings_group_system,
        listOf(SettingsPage.Advanced),
    ),
)

/** Group a page belongs to in the desktop sidebar, used as its breadcrumb. */
internal fun SettingsPage.desktopGroupLabel(): StringResource? {
    val groups = desktopSettingsGroups()
    groups.firstOrNull { this in it.pages }?.let { return it.labelRes }
    // Pages reached from a hub or from About rather than listed themselves.
    return when (this) {
        SettingsPage.Integrations -> Res.string.settings_group_integrations
        SettingsPage.ContentDiscovery -> Res.string.settings_group_sources
        SettingsPage.SupportersContributors, SettingsPage.LicensesAttributions -> Res.string.settings_group_system
        else -> null
    }
}

@Composable
internal fun DesktopSettingsSidebar(
    currentPage: SettingsPage,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    topPadding: Dp,
    startPadding: Dp,
    aboutSelected: Boolean,
    onOpenAbout: () -> Unit,
    onCollections: () -> Unit,
    onSwitchProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = startPadding - 10.dp, end = 12.dp, top = topPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingsSearchField(query = query, onQueryChange = onQueryChange)
        desktopSettingsGroups().forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(group.labelRes).uppercase(),
                    modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textMuted,
                )
                group.pages.forEach { page ->
                    SidebarLink(
                        label = stringResource(page.titleRes),
                        selected = currentPage == page && query.isBlank() && !aboutSelected,
                        onClick = { onOpenPage(page) },
                    )
                    if (page == SettingsPage.Homescreen) {
                        SidebarLink(
                            label = stringResource(Res.string.collections_header),
                            selected = false,
                            onClick = onCollections,
                        )
                    }
                }
                if (group.labelRes == Res.string.settings_group_you && onSwitchProfile != null) {
                    SidebarLink(
                        label = stringResource(Res.string.compose_settings_root_switch_profile_title),
                        selected = false,
                        onClick = onSwitchProfile,
                    )
                }
                if (group.labelRes == Res.string.settings_group_system) {
                    SidebarLink(
                        label = stringResource(Res.string.compose_settings_category_about),
                        selected = aboutSelected && query.isBlank(),
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.md + 2.dp)
    val placeholder = stringResource(Res.string.settings_search_placeholder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.surface)
            .border(NuvioTokens.Border.thin, colors.borderSubtle, shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
            )
        }
    }
}

@Composable
private fun SidebarLink(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(
                when {
                    selected -> colors.textPrimary.copy(alpha = 0.08f)
                    hovered -> colors.overlayHover
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
