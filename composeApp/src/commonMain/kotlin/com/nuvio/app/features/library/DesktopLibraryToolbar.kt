package com.nuvio.app.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioDesktopTextField
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Below this width the toolbar puts its filters on a second line. */
private val SingleLineToolbarWidth = 960.dp

/**
 * The desktop Library toolbar, pinned under the title: Saved/Cloud, a type filter, search, the
 * list and sort pickers, and Rows/Grid. Mobile keeps the chips in LibraryScreen.
 */
@Composable
internal fun DesktopLibraryToolbar(
    cloudSelected: Boolean,
    onCloudSelectedChange: (Boolean) -> Unit,
    savedControls: DesktopLibrarySavedControls?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val singleLine = maxWidth >= SingleLineToolbarWidth
        val source: @Composable () -> Unit = {
            NuvioSegmentedControl(
                segments = listOf(
                    NuvioSegment(false, stringResource(Res.string.library_source_saved)),
                    NuvioSegment(true, stringResource(Res.string.library_source_cloud)),
                ),
                selected = cloudSelected,
                onSelect = onCloudSelectedChange,
            )
        }
        val types: @Composable () -> Unit = {
            savedControls?.takeIf { it.types.size > 1 }?.let { controls ->
                NuvioSegmentedControl(
                    segments = listOf(NuvioSegment<String?>(null, stringResource(Res.string.library_desktop_all))) +
                        controls.types.sortedWith(libraryTypeOrder).map { type -> NuvioSegment<String?>(type, localizedMediaTypeLabel(type)) },
                    selected = controls.selectedType,
                    onSelect = controls.onTypeSelected,
                )
            }
        }
        val search: @Composable (Modifier) -> Unit = { fieldModifier ->
            savedControls?.let { controls ->
                NuvioDesktopTextField(
                    value = controls.query,
                    onValueChange = controls.onQueryChange,
                    placeholder = stringResource(Res.string.library_desktop_search_placeholder),
                    modifier = fieldModifier,
                    leadingIcon = Icons.Rounded.Search,
                    height = 42.dp,
                )
            }
        }
        val pickers: @Composable () -> Unit = {
            savedControls?.let { controls -> SavedPickers(controls) }
        }

        if (singleLine) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                source()
                types()
                Spacer(Modifier.weight(1f))
                search(Modifier.width(260.dp))
                pickers()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    source()
                    types()
                }
                if (savedControls != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        search(Modifier.weight(1f).widthIn(min = 160.dp))
                        pickers()
                    }
                }
            }
        }
    }
}

/** What the toolbar shows and changes while Saved is open. */
internal class DesktopLibrarySavedControls(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val types: List<String>,
    val selectedType: String?,
    val onTypeSelected: (String?) -> Unit,
    val layoutMode: LibraryLayoutMode,
    val onLayoutModeChange: (LibraryLayoutMode) -> Unit,
    val sortOption: LibrarySortOption,
    val sortOptions: List<LibrarySortOption>,
    val onSortSelected: (LibrarySortOption) -> Unit,
    /** Remote lists to pick from in the grid; empty when the grid shows everything at once. */
    val lists: List<LibrarySection>,
    val selectedListKey: String?,
    val onListSelected: (String) -> Unit,
)

@Composable
private fun SavedPickers(controls: DesktopLibrarySavedControls) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (controls.lists.isNotEmpty()) {
            ToolbarDropdown(
                icon = Icons.AutoMirrored.Rounded.ListAlt,
                title = stringResource(Res.string.library_filter_list),
                label = controls.lists.firstOrNull { it.type == controls.selectedListKey }?.displayTitle.orEmpty(),
                selectedKey = controls.selectedListKey,
                options = controls.lists.map { NuvioDropdownOption(key = it.type, label = it.displayTitle) },
                onSelected = { option -> controls.onListSelected(option.key) },
            )
        }
        ToolbarDropdown(
            icon = Icons.AutoMirrored.Rounded.Sort,
            title = stringResource(Res.string.library_filter_sort),
            label = librarySortOptionLabel(controls.sortOption),
            selectedKey = controls.sortOption.name,
            options = controls.sortOptions.map { NuvioDropdownOption(key = it.name, label = librarySortOptionLabel(it)) },
            onSelected = { option ->
                LibrarySortOption.entries.firstOrNull { it.name == option.key }?.let(controls.onSortSelected)
            },
        )
        NuvioSegmentedControl(
            segments = listOf(
                NuvioSegment(
                    LibraryLayoutMode.HORIZONTAL,
                    stringResource(Res.string.library_desktop_layout_rows),
                    contentDescription = stringResource(Res.string.library_layout_show_horizontal),
                ),
                NuvioSegment(
                    LibraryLayoutMode.VERTICAL,
                    stringResource(Res.string.library_desktop_layout_grid),
                    contentDescription = stringResource(Res.string.library_layout_show_vertical),
                ),
            ),
            selected = controls.layoutMode,
            onSelect = controls.onLayoutModeChange,
        )
    }
}

@Composable
private fun ToolbarDropdown(
    icon: ImageVector,
    title: String,
    label: String,
    selectedKey: String?,
    options: List<NuvioDropdownOption>,
    onSelected: (NuvioDropdownOption) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val enabled = options.size > 1
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered && enabled) lerp(tokens.colors.surface, tokens.colors.textPrimary, 0.05f) else tokens.colors.surface,
        tween(NuvioTokens.Motion.fastMillis),
        label = "library_toolbar_dropdown",
    )
    Box {
        Row(
            modifier = Modifier
                .height(42.dp)
                .clip(shape)
                .background(background)
                .border(NuvioTokens.Border.thin, if (expanded) tokens.colors.borderFocus else tokens.colors.borderSubtle, shape)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.DropdownList) { expanded = true }
                .semantics { stateDescription = title }
                .padding(start = 12.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 200.dp, max = 360.dp),
            containerColor = tokens.colors.surfacePopover,
            shape = tokens.shapes.compactCard,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        if (option.key == selectedKey) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

/** Movies, then series, then anime; anything else after them alphabetically. */
private val libraryTypeOrder: Comparator<String> = compareBy<String> { type ->
    when (type) {
        "movie" -> 0
        "series" -> 1
        "anime" -> 2
        else -> 3
    }
}.thenBy { it }

/** Shown when a search finds nothing, with a way straight back to everything. */
@Composable
internal fun LibraryNoMatches(query: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(tokens.colors.textPrimary.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.library_desktop_no_matches_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = stringResource(Res.string.library_desktop_no_matches_message, query),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        NuvioCompactActionButton(
            label = stringResource(Res.string.compose_search_clear),
            icon = Icons.Rounded.Close,
            onClick = onClear,
            tone = NuvioCompactActionTone.Ghost,
            size = NuvioActionSize.Small,
        )
    }
}

/** "48 titles", or "3 / 48 titles" while a search or type filter narrows the library. */
@Composable
internal fun LibraryResultsCount(shown: Int, total: Int, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Row(modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (shown == total) {
                pluralStringResource(Res.plurals.library_desktop_title_count, total, total)
            } else {
                "$shown / ${pluralStringResource(Res.plurals.library_desktop_title_count, total, total)}"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textMuted,
        )
    }
}

/** Normalised type key used by the type filter, matching the grid's own type filter. */
internal fun LibraryItem.libraryFilterType(): String = (mediaCategory ?: type).trim().lowercase()

internal fun LibraryItem.matchesLibrarySearch(query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.isEmpty() || name.contains(trimmed, ignoreCase = true)
}
