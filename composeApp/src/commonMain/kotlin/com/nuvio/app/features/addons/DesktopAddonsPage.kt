package com.nuvio.app.features.addons

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioDesktopTextField
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Below this width add-on rows move their actions under the description. */
private val CompactRowWidth = 560.dp

/**
 * The desktop layout of Settings → Add-ons: counts, an install bar, and one row per add-on in
 * the order Starlit asks them. Phone and tablet keep the cards in AddonsScreen.
 */
@Composable
internal fun DesktopAddonsContent(
    addons: List<ManagedAddon>,
    addonUrl: String,
    formMessage: String?,
    usePersonalMediaCopy: Boolean,
    onAddonUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRefresh: (ManagedAddon) -> Unit,
    onEnabledChange: (ManagedAddon, Boolean) -> Unit,
    configureAction: (ManagedAddon) -> (() -> Unit)?,
    onDelete: (ManagedAddon) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = remember(addons) { addons.toOverview() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(overview.totalAddons, stringResource(Res.string.addons_desktop_stat_installed), Modifier.weight(1f))
            StatTile(overview.activeAddons, stringResource(Res.string.addons_overview_active), Modifier.weight(1f))
            StatTile(overview.totalCatalogs, stringResource(Res.string.addons_overview_catalogs), Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        NuvioSectionLabel(stringResource(Res.string.addons_desktop_install_title).uppercase())
        Group {
            InstallBar(
                addonUrl = addonUrl,
                formMessage = formMessage,
                usePersonalMediaCopy = usePersonalMediaCopy,
                onAddonUrlChange = onAddonUrlChange,
                onInstall = onInstall,
            )
        }

        Spacer(Modifier.height(10.dp))
        AddonBackupCard()

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NuvioSectionLabel(stringResource(Res.string.addons_desktop_installed_title).uppercase())
            Spacer(Modifier.weight(1f))
            if (addons.size > 1) {
                Text(
                    text = stringResource(Res.string.addons_desktop_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        }
        Group {
            if (addons.isEmpty()) {
                EmptyAddons(usePersonalMediaCopy)
            } else {
                addons.forEachIndexed { index, addon ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                            thickness = NuvioTokens.Border.hairline,
                            color = MaterialTheme.nuvio.colors.borderSubtle,
                        )
                    }
                    AddonRow(
                        addon = addon,
                        onMoveUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
                        onMoveDown = if (index < addons.lastIndex) ({ onMove(index, index + 1) }) else null,
                        onRefresh = { onRefresh(addon) },
                        onEnabledChange = { onEnabledChange(addon, it) },
                        onConfigure = configureAction(addon),
                        onDelete = { onDelete(addon) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surface)
            .border(NuvioTokens.Border.hairline, tokens.colors.borderSubtle, shape),
        content = content,
    )
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier = modifier
            .clip(shape)
            .background(tokens.colors.surface)
            .border(NuvioTokens.Border.hairline, tokens.colors.borderSubtle, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun InstallBar(
    addonUrl: String,
    formMessage: String?,
    usePersonalMediaCopy: Boolean,
    onAddonUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NuvioDesktopTextField(
                value = addonUrl,
                onValueChange = onAddonUrlChange,
                placeholder = stringResource(
                    if (usePersonalMediaCopy) Res.string.addons_appstore_input_placeholder else Res.string.addons_desktop_install_placeholder,
                ),
                modifier = Modifier.weight(1f),
                leadingIcon = Icons.Rounded.Link,
                height = 44.dp,
                isError = formMessage != null,
                onSubmit = onInstall,
            )
            NuvioCompactActionButton(
                label = stringResource(
                    if (usePersonalMediaCopy) Res.string.addons_appstore_install_button else Res.string.addons_desktop_install_action,
                ),
                icon = Icons.Rounded.Add,
                onClick = onInstall,
                enabled = addonUrl.isNotBlank(),
                tone = NuvioCompactActionTone.Primary,
                size = NuvioActionSize.Medium,
            )
        }
        Text(
            text = formMessage ?: stringResource(
                if (usePersonalMediaCopy) Res.string.addons_appstore_add_description else Res.string.addons_desktop_install_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (formMessage != null) tokens.colors.danger else tokens.colors.textMuted,
        )
    }
}

@Composable
private fun AddonRow(
    addon: ManagedAddon,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onConfigure: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val manifest = addon.manifest
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) tokens.colors.overlayHover else Color.Transparent,
        tween(NuvioTokens.Motion.fastMillis),
        label = "addon_row_background",
    )
    val dimmed = if (addon.enabled) 1f else 0.55f
    val title = addon.desktopTitle()
    BoxWithConstraints(Modifier.fillMaxWidth().background(background).hoverable(interaction)) {
        val compact = maxWidth < CompactRowWidth
        val actions: @Composable () -> Unit = {
            AddonActions(
                title = title,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRefresh = onRefresh,
                refreshing = addon.isRefreshing,
                onConfigure = onConfigure,
                onDelete = onDelete,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AddonLogo(manifest?.logoUrl, Modifier.alpha(dimmed))
            Column(Modifier.weight(1f).alpha(dimmed), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    manifest?.version?.takeIf { it.isNotBlank() }?.let { version ->
                        Text("v$version", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, maxLines = 1)
                    }
                    AddonStatus(addon)
                }
                when {
                    addon.isRefreshing && manifest == null -> Text(
                        text = stringResource(Res.string.addons_loading_manifest_details),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                    manifest == null -> Text(
                        text = addon.errorMessage ?: addon.manifestUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (addon.errorMessage != null) tokens.colors.danger else tokens.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> {
                        manifest.description.trim().takeIf { it.isNotEmpty() }?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = capabilityLine(manifest),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        addon.errorMessage?.let { staleError ->
                            Text(staleError, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger, maxLines = 2)
                        }
                    }
                }
                if (compact) {
                    Spacer(Modifier.height(4.dp))
                    actions()
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = addon.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = title },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = tokens.colors.onAccent,
                        checkedTrackColor = tokens.colors.accent,
                        uncheckedThumbColor = tokens.colors.textMuted,
                        uncheckedTrackColor = tokens.colors.borderDefault,
                    ),
                )
                if (!compact) actions()
            }
        }
    }
}

@Composable
private fun AddonActions(
    title: String,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onConfigure: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        RowAction(Icons.Rounded.ArrowUpward, stringResource(Res.string.addons_move_up), title, onMoveUp ?: {}, enabled = onMoveUp != null)
        RowAction(Icons.Rounded.ArrowDownward, stringResource(Res.string.addons_move_down), title, onMoveDown ?: {}, enabled = onMoveDown != null)
        RowAction(Icons.Rounded.Refresh, stringResource(Res.string.addons_refresh), title, onRefresh, enabled = !refreshing)
        if (onConfigure != null) RowAction(Icons.Rounded.Settings, stringResource(Res.string.addons_configure), title, onConfigure)
        RowAction(Icons.Rounded.Delete, stringResource(Res.string.addons_delete), title, onDelete, danger = true)
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    addonTitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        !enabled -> tokens.colors.textDisabled
        danger && hovered -> tokens.colors.danger
        hovered -> tokens.colors.textPrimary
        else -> tokens.colors.textMuted
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.size(32.dp).hoverable(interaction),
    ) {
        Icon(icon, contentDescription = "$label: $addonTitle", tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AddonStatus(addon: ManagedAddon) {
    val tokens = MaterialTheme.nuvio
    val (label, color) = when {
        !addon.enabled -> stringResource(Res.string.addons_badge_disabled) to tokens.colors.textMuted
        addon.isRefreshing -> stringResource(Res.string.addons_badge_refreshing) to tokens.colors.info
        addon.manifest == null -> stringResource(Res.string.addons_badge_unavailable) to tokens.colors.danger
        // Working add-ons need no label; only states that need attention get one.
        else -> return
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun AddonLogo(logoUrl: String?, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(shape)
            .background(tokens.colors.textPrimary.copy(alpha = 0.06f))
            .border(NuvioTokens.Border.hairline, tokens.colors.borderSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!logoUrl.isNullOrBlank()) {
            NuvioAsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.Extension, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EmptyAddons(usePersonalMediaCopy: Boolean) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(tokens.colors.textPrimary.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Extension, null, tint = tokens.colors.textMuted, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(if (usePersonalMediaCopy) Res.string.addons_appstore_empty_title else Res.string.addons_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(if (usePersonalMediaCopy) Res.string.addons_appstore_empty_subtitle else Res.string.addons_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

/** "Movies, Series  ·  12 catalogs  ·  Metadata, Streams  ·  P2P" */
@Composable
private fun capabilityLine(manifest: AddonManifest): String {
    val types = manifest.types.distinct().map { localizedMediaTypeLabel(it) }.joinToString(", ")
    val catalogs = manifest.catalogs.size.takeIf { it > 0 }?.let {
        pluralStringResource(Res.plurals.addons_desktop_catalog_count, it, it)
    }
    val resources = manifest.resources.map { it.name }.distinct()
        .filterNot { it.equals("catalog", ignoreCase = true) && catalogs != null }
        .map { resourceLabel(it) }
        .joinToString(", ")
    return listOfNotNull(
        types.takeIf { it.isNotBlank() },
        catalogs,
        resources.takeIf { it.isNotBlank() },
        "P2P".takeIf { manifest.behaviorHints.p2p },
    ).joinToString("  ·  ")
}

@Composable
private fun resourceLabel(name: String): String = when (name.lowercase()) {
    "catalog" -> stringResource(Res.string.addons_desktop_resource_catalog)
    "meta" -> stringResource(Res.string.addons_desktop_resource_meta)
    "stream" -> stringResource(Res.string.addons_desktop_resource_stream)
    "subtitles" -> stringResource(Res.string.addons_desktop_resource_subtitles)
    else -> name.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/** The add-on's name, or its server's when the manifest has not loaded ("manifest.json" says nothing). */
private fun ManagedAddon.desktopTitle(): String {
    if (manifest != null || !userSetName.isNullOrBlank()) return displayTitle
    return manifestUrl.substringAfter("://").substringBefore("/").substringBefore("?")
        .removePrefix("www.")
        .ifBlank { displayTitle }
}
