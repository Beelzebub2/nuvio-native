package com.nuvio.app.features.collection

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioEmptyBlock
import com.nuvio.app.core.ui.NuvioFieldLabel
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioLabeledField
import com.nuvio.app.core.ui.NuvioPageHeader
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioSectionHeader
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.nuvioGroupSegment
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.surfaceInset
import com.nuvio.app.features.settings.SettingsSwitchRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.trakt.TraktPublicListSearchResult
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class CollectionEditorPage {
    Root,
    FolderEditor,
    CatalogPicker,
    TmdbSourcePicker,
    TraktSourcePicker,
}

fun disposeCollectionEditorPage(page: CollectionEditorPage) {
    when (page) {
        CollectionEditorPage.Root -> Unit
        CollectionEditorPage.FolderEditor -> CollectionEditorRepository.cancelFolderEdit()
        CollectionEditorPage.CatalogPicker -> CollectionEditorRepository.hideCatalogPicker()
        CollectionEditorPage.TmdbSourcePicker -> CollectionEditorRepository.hideTmdbSourcePicker()
        CollectionEditorPage.TraktSourcePicker -> CollectionEditorRepository.hideTraktSourcePicker()
    }
}

private val autoDismissedPickerPages = setOf(
    CollectionEditorPage.TmdbSourcePicker,
    CollectionEditorPage.TraktSourcePicker,
)

private fun CollectionEditorUiState.activeEditorPage(): CollectionEditorPage = when {
    showCatalogPicker -> CollectionEditorPage.CatalogPicker
    showTmdbSourcePicker -> CollectionEditorPage.TmdbSourcePicker
    showTraktSourcePicker -> CollectionEditorPage.TraktSourcePicker
    showFolderEditor && editingFolder != null -> CollectionEditorPage.FolderEditor
    else -> CollectionEditorPage.Root
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionEditorScreen(
    collectionId: String?,
    onBack: () -> Unit,
    initialPage: CollectionEditorPage? = null,
    initializeRepository: Boolean = true,
    onNavigateToPage: ((page: CollectionEditorPage, title: String) -> Unit)? = null,
) {
    val state by CollectionEditorRepository.uiState.collectAsState()
    val bottomInset = nuvioSafeBottomPadding()

    LaunchedEffect(collectionId, initializeRepository) {
        if (initializeRepository) {
            CollectionEditorRepository.initialize(collectionId)
        }
    }

    val page = initialPage ?: state.activeEditorPage()
    val initialPickerCompletionGeneration = remember(initialPage) {
        state.sourcePickerCompletionGeneration
    }

    LaunchedEffect(initialPage, state.sourcePickerCompletionGeneration) {
        if (
            initialPage in autoDismissedPickerPages &&
            state.sourcePickerCompletionGeneration != initialPickerCompletionGeneration
        ) {
            onBack()
        }
    }

    if (
        initialPage != null &&
        page != CollectionEditorPage.Root &&
        state.editingFolder == null
    ) {
        LaunchedEffect(initialPage) { onBack() }
        return
    }

    fun closePage(pageToClose: CollectionEditorPage, close: () -> Unit) {
        close()
        if (initialPage == pageToClose) {
            onBack()
        }
    }

    val editingFolder = state.editingFolder
    if (page == CollectionEditorPage.FolderEditor && editingFolder != null) {
        val genrePickerIndex = state.genrePickerSourceIndex
        val genrePickerSource = genrePickerIndex?.let { editingFolder.resolvedSources.getOrNull(it) }
        val genrePickerCatalogSource = genrePickerSource?.addonCatalogSource()
        val genrePickerCatalog = genrePickerCatalogSource?.let { source ->
            state.availableCatalogs.findAvailableCatalog(source)
        }

        FolderEditorPage(
            state = state,
            onBack = {
                closePage(CollectionEditorPage.FolderEditor) {
                    CollectionEditorRepository.cancelFolderEdit()
                }
            },
            onNavigateToPage = onNavigateToPage,
            onSave = {
                CollectionEditorRepository.saveFolderEdit()
                if (initialPage == CollectionEditorPage.FolderEditor) {
                    onBack()
                }
            },
        )

        if (
            genrePickerIndex != null &&
            genrePickerCatalogSource != null &&
            genrePickerCatalog != null &&
            genrePickerCatalog.genreOptions.isNotEmpty()
        ) {
            GenrePickerSheet(
                title = genrePickerCatalog.catalogName,
                selectedGenre = genrePickerCatalogSource.genre,
                genreOptions = genrePickerCatalog.genreOptions,
                allowAll = !genrePickerCatalog.genreRequired,
                onSelect = {
                    CollectionEditorRepository.updateCatalogSourceGenre(genrePickerIndex, it)
                    CollectionEditorRepository.hideGenrePicker()
                },
                onDismiss = { CollectionEditorRepository.hideGenrePicker() },
            )
        }
        return
    }

    if (page == CollectionEditorPage.CatalogPicker) {
        CatalogPickerScreen(
            availableCatalogs = state.availableCatalogs,
            selectedSources = state.editingFolder?.resolvedCatalogSources.orEmpty(),
            onToggle = { CollectionEditorRepository.toggleCatalogSource(it) },
            onBack = {
                closePage(CollectionEditorPage.CatalogPicker) {
                    CollectionEditorRepository.hideCatalogPicker()
                }
            },
        )
        return
    }

    if (page == CollectionEditorPage.TmdbSourcePicker) {
        TmdbSourcePickerScreen(
            state = state,
            onBack = {
                closePage(CollectionEditorPage.TmdbSourcePicker) {
                    CollectionEditorRepository.hideTmdbSourcePicker()
                }
            },
        )
        return
    }

    if (page == CollectionEditorPage.TraktSourcePicker) {
        TraktSourcePickerScreen(
            state = state,
            onBack = {
                closePage(CollectionEditorPage.TraktSourcePicker) {
                    CollectionEditorRepository.hideTraktSourcePicker()
                }
            },
        )
        return
    }

    val tokens = MaterialTheme.nuvio
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioPageHeader(
                title = if (state.isNew) {
                    stringResource(Res.string.collections_new)
                } else {
                    stringResource(Res.string.collections_editor_edit_collection)
                },
                eyebrow = stringResource(Res.string.collections_header),
                onBack = onBack,
            ) {
                NuvioPillButton(
                    text = stringResource(Res.string.collections_editor_save),
                    onClick = {
                        if (CollectionEditorRepository.save()) {
                            onBack()
                        }
                    },
                    style = NuvioPillButtonStyle.Primary,
                    enabled = state.title.isNotBlank(),
                )
            }
        }

        item(key = "collection-basics") {
            EditorSection(title = stringResource(Res.string.collections_editor_section_basics)) {
                NuvioGroup {
                    EditorFieldRow {
                        NuvioLabeledField(label = stringResource(Res.string.collections_editor_placeholder_name)) {
                            NuvioInputField(
                                value = state.title,
                                onValueChange = { CollectionEditorRepository.setTitle(it) },
                                placeholder = stringResource(Res.string.collections_editor_placeholder_name),
                                minHeight = 48.dp,
                            )
                        }
                    }
                    EditorFieldRow(topPadding = 0.dp) {
                        NuvioLabeledField(label = stringResource(Res.string.collections_editor_placeholder_backdrop)) {
                            NuvioInputField(
                                value = state.backdropImageUrl,
                                onValueChange = { CollectionEditorRepository.setBackdropImageUrl(it) },
                                placeholder = "https://",
                                minHeight = 48.dp,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                        }
                    }
                    NuvioGroupDivider(startInset = 16.dp, endInset = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_pin_above),
                        description = stringResource(Res.string.collections_editor_pin_above_desc),
                        checked = state.pinToTop,
                        isTablet = false,
                        onCheckedChange = { CollectionEditorRepository.setPinToTop(it) },
                    )
                }
            }
        }

        item(key = "collection-appearance") {
            EditorSection(title = stringResource(Res.string.collections_editor_section_appearance)) {
                NuvioGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.collections_editor_view_mode),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = tokens.colors.textPrimary,
                        )
                        NuvioSegmentedControl(
                            segments = listOf(
                                NuvioSegment(FolderViewMode.ROWS, stringResource(Res.string.collections_editor_view_mode_rows)),
                                NuvioSegment(FolderViewMode.TABBED_GRID, stringResource(Res.string.collections_editor_view_mode_tabs)),
                            ),
                            // An older "follow layout" choice shows as Rows, which is what it renders.
                            selected = if (state.viewMode == FolderViewMode.TABBED_GRID) FolderViewMode.TABBED_GRID else FolderViewMode.ROWS,
                            onSelect = { CollectionEditorRepository.setViewMode(it) },
                            size = NuvioSegmentedSize.Compact,
                            containerColor = tokens.colors.surfaceInset,
                        )
                    }
                    NuvioGroupDivider(startInset = 16.dp, endInset = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_show_all_tab),
                        description = stringResource(Res.string.collections_editor_show_all_tab_desc),
                        checked = state.showAllTab,
                        isTablet = false,
                        onCheckedChange = { CollectionEditorRepository.setShowAllTab(it) },
                    )
                }
            }
        }

        item(key = "collection-folders-header") {
            val newFolderTitle = stringResource(Res.string.collections_editor_new_folder)
            NuvioSectionHeader(
                title = stringResource(Res.string.collections_editor_folders).uppercase(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                EditorAddAction(
                    text = stringResource(Res.string.collections_editor_add_folder),
                    onClick = {
                        CollectionEditorRepository.addFolder(newFolderTitle)
                        onNavigateToPage?.invoke(CollectionEditorPage.FolderEditor, newFolderTitle)
                    },
                )
            }
        }

        if (state.folders.isNotEmpty()) {
            item(key = "collection-folders") {
                val editFolderTitle = stringResource(Res.string.collections_editor_edit_folder)
                FolderReorderableList(
                    folders = state.folders,
                    onEdit = {
                        CollectionEditorRepository.editFolder(it)
                        onNavigateToPage?.invoke(CollectionEditorPage.FolderEditor, editFolderTitle)
                    },
                    onDelete = { CollectionEditorRepository.removeFolder(it) },
                )
            }
        } else {
            item(key = "collection-folders-empty") {
                NuvioSurfaceCard(outlined = true) {
                    NuvioEmptyBlock(
                        icon = Icons.Rounded.Folder,
                        title = stringResource(Res.string.collections_editor_folder_empty_title),
                        message = stringResource(Res.string.collections_editor_folder_empty_subtitle),
                        tone = NuvioIconChipTone.Neutral,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        item(key = "collection-bottom-space") {
            Spacer(modifier = Modifier.height(24.dp + bottomInset))
        }
    }
}

/** A section label above a group. Actions such as "Add" sit on the label's line. */
@Composable
private fun EditorSection(
    title: String,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionHeader(title = title.uppercase(), trailing = actions)
        content()
    }
}

/** Padding for a labelled field inside a group. */
@Composable
private fun EditorFieldRow(
    topPadding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** "+ Add Folder" style inline action. */
@Composable
private fun EditorAddAction(text: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.compactCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tokens.colors.accent, maxLines = 1)
    }
}

@Composable
private fun FolderReorderableList(
    folders: List<CollectionFolder>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
    ) { from, to ->
        CollectionEditorRepository.moveFolderByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp),
        state = lazyListState,
    ) {
        itemsIndexed(folders, key = { _, folder -> folder.id }) { index, folder ->
            ReorderableItem(reorderableLazyListState, key = folder.id) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp)
                Column(
                    Modifier
                        .shadow(elevation, tokens.shapes.compactCard)
                        .nuvioGroupSegment(
                            // A dragged row draws as its own card so it reads as lifted.
                            isFirst = index == 0 || isDragging,
                            isLast = index == folders.lastIndex || isDragging,
                            containerColor = if (isDragging) tokens.colors.surfaceCard else tokens.colors.surface,
                            borderColor = tokens.colors.borderSubtle,
                        ),
                ) {
                    if (index > 0 && !isDragging) NuvioGroupDivider()
                    FolderListItem(
                        folder = folder,
                        onEdit = { onEdit(folder.id) },
                        onDelete = { onDelete(folder.id) },
                        dragHandleScope = this@ReorderableItem,
                    )
                }
            }
        }
    }
}

/** The folder's cover: its image, its emoji, or a folder glyph. */
@Composable
private fun FolderCoverThumb(folder: CollectionFolder) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        val image = folder.coverImageUrl?.takeIf { it.isNotBlank() }
        when {
            image != null -> NuvioAsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            !folder.coverEmoji.isNullOrBlank() -> Text(folder.coverEmoji, style = MaterialTheme.typography.titleMedium)
            else -> Icon(Icons.Rounded.Folder, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FolderListItem(
    folder: CollectionFolder,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandleScope: ReorderableCollectionItemScope,
) {
    val tokens = MaterialTheme.nuvio
    val hapticFeedback = LocalHapticFeedback.current
    NuvioGroupRow(
        title = folder.title,
        description = stringResource(
            Res.string.collections_editor_source_count,
            folder.resolvedSources.size,
            posterShapeLabel(folder.posterShape),
        ),
        onClick = onEdit,
        titleMaxLines = 1,
        descriptionMaxLines = 1,
        verticalPadding = 12.dp,
        endPadding = 4.dp,
        spacing = 12.dp,
        leading = { FolderCoverThumb(folder) },
        trailing = {
            NuvioSquareIconButton(
                icon = Icons.Rounded.Edit,
                contentDescription = stringResource(Res.string.action_edit),
                onClick = onEdit,
                style = NuvioSquareIconButtonStyle.Ghost,
                iconSize = 18.dp,
            )
            NuvioSquareIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.action_delete),
                onClick = onDelete,
                style = NuvioSquareIconButtonStyle.Ghost,
                iconSize = 18.dp,
                tint = tokens.colors.danger,
            )
            Box(
                modifier = with(dragHandleScope) {
                    Modifier
                        .size(width = 32.dp, height = 40.dp)
                        .clip(tokens.shapes.compactCard)
                        .draggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                        )
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = stringResource(Res.string.action_reorder),
                    modifier = Modifier.size(18.dp),
                    tint = tokens.colors.textMuted,
                )
            }
        },
    )
}

/** Three tile shapes as selectable cards, each with a small outline of its proportions. */
@Composable
private fun TileShapePicker(
    selected: PosterShape,
    onSelect: (PosterShape) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PosterShape.entries.forEach { shape ->
            val isSelected = shape == selected
            val cardShape = tokens.shapes.compactCard
            val contentColor = if (isSelected) tokens.colors.textPrimary else tokens.colors.textMuted
            val (glyphWidth, glyphHeight) = when (shape) {
                PosterShape.Poster -> 16.dp to 24.dp
                PosterShape.Landscape -> 28.dp to 16.dp
                PosterShape.Square -> 20.dp to 20.dp
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clip(cardShape)
                    .background(if (isSelected) tokens.colors.overlaySelected else androidx.compose.ui.graphics.Color.Transparent)
                    .border(
                        tokens.borders.thin,
                        if (isSelected) tokens.colors.borderSelected else tokens.colors.borderStrong,
                        cardShape,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(shape) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier
                        .size(width = glyphWidth, height = glyphHeight)
                        .border(1.5.dp, contentColor, RoundedCornerShape(4.dp)),
                )
                Text(
                    text = posterShapeLabel(shape),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderEditorPage(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
    onNavigateToPage: ((page: CollectionEditorPage, title: String) -> Unit)?,
    onSave: () -> Unit,
) {
    val folder = state.editingFolder ?: return
    val tokens = MaterialTheme.nuvio
    val bottomInset = nuvioSafeBottomPadding()
    val catalogPickerTitle = stringResource(Res.string.collections_editor_select_catalogs)
    val tmdbSourcePickerTitle = stringResource(Res.string.collections_editor_tmdb_sources)
    val traktSourcePickerTitle = stringResource(Res.string.collections_editor_trakt_sources)
    val editTraktSourcePickerTitle = stringResource(Res.string.collections_editor_edit_trakt_source)

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    NuvioScreen(modifier = Modifier.fillMaxSize()) {
        stickyHeader {
            NuvioPageHeader(
                title = if (state.folders.any { it.id == folder.id }) {
                    stringResource(Res.string.collections_editor_edit_folder)
                } else {
                    stringResource(Res.string.collections_editor_new_folder)
                },
                eyebrow = state.title.takeIf { it.isNotBlank() } ?: stringResource(Res.string.collections_header),
                onBack = onBack,
            ) {
                NuvioPillButton(
                    text = stringResource(Res.string.collections_editor_save),
                    onClick = onSave,
                    style = NuvioPillButtonStyle.Primary,
                    enabled = folder.title.isNotBlank(),
                )
            }
        }

        item(key = "folder-help") {
            Text(
                text = stringResource(Res.string.collections_editor_folder_editor_help),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }

        item(key = "folder-basics") {
            EditorSection(title = stringResource(Res.string.collections_editor_section_basics)) {
                NuvioGroup {
                    EditorFieldRow {
                        NuvioLabeledField(label = stringResource(Res.string.collections_editor_placeholder_folder)) {
                            NuvioInputField(
                                value = folder.title,
                                onValueChange = { CollectionEditorRepository.updateFolderTitle(it) },
                                placeholder = stringResource(Res.string.collections_editor_placeholder_folder),
                                minHeight = 48.dp,
                            )
                        }
                    }
                }
            }
        }

        item(key = "folder-appearance") {
            EditorSection(title = stringResource(Res.string.collections_editor_section_appearance)) {
                NuvioGroup {
                    EditorFieldRow {
                        Text(
                            text = stringResource(Res.string.collections_editor_cover),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = tokens.colors.textPrimary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Chip(
                                label = stringResource(Res.string.collections_editor_cover_none),
                                selected = folder.coverEmoji == null && folder.coverImageUrl == null,
                                onClick = { CollectionEditorRepository.clearFolderCover() },
                                showSelectedCheck = true,
                            )
                            Chip(
                                label = stringResource(Res.string.collections_editor_cover_emoji),
                                selected = folder.coverEmoji != null,
                                onClick = {
                                    if (folder.coverEmoji == null) {
                                        CollectionEditorRepository.updateFolderCoverEmoji("📁")
                                    }
                                },
                                showSelectedCheck = true,
                            )
                            Chip(
                                label = stringResource(Res.string.collections_editor_cover_image_url),
                                selected = folder.coverImageUrl != null,
                                onClick = {
                                    if (folder.coverImageUrl == null) {
                                        CollectionEditorRepository.updateFolderCoverImage("")
                                    }
                                },
                                showSelectedCheck = true,
                            )
                        }
                        if (folder.coverEmoji != null) {
                            NuvioInputField(
                                value = folder.coverEmoji,
                                onValueChange = { CollectionEditorRepository.updateFolderCoverEmoji(it) },
                                placeholder = stringResource(Res.string.collections_editor_cover_emoji),
                                modifier = Modifier.width(120.dp),
                                minHeight = 48.dp,
                            )
                        }
                        if (folder.coverImageUrl != null) {
                            NuvioInputField(
                                value = folder.coverImageUrl,
                                onValueChange = { CollectionEditorRepository.updateFolderCoverImage(it) },
                                placeholder = stringResource(Res.string.collections_editor_cover_image_url),
                                minHeight = 48.dp,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                        }
                    }
                    EditorFieldRow(topPadding = 0.dp) {
                        NuvioLabeledField(label = stringResource(Res.string.collections_editor_placeholder_gif)) {
                            NuvioInputField(
                                value = folder.focusGifUrl.orEmpty(),
                                onValueChange = { CollectionEditorRepository.updateFolderFocusGifUrl(it) },
                                placeholder = "https://",
                                minHeight = 48.dp,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                        }
                    }
                    NuvioGroupDivider(startInset = 16.dp, endInset = 16.dp)
                    EditorFieldRow(topPadding = 12.dp) {
                        Text(
                            text = stringResource(Res.string.collections_editor_tile_shape),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = tokens.colors.textPrimary,
                        )
                        TileShapePicker(
                            selected = folder.posterShape,
                            onSelect = { CollectionEditorRepository.updateFolderTileShape(it) },
                        )
                    }
                    NuvioGroupDivider(startInset = 16.dp, endInset = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_hide_title),
                        description = stringResource(Res.string.collections_editor_hide_title_desc),
                        checked = folder.hideTitle,
                        isTablet = false,
                        onCheckedChange = { CollectionEditorRepository.updateFolderHideTitle(it) },
                    )
                    NuvioGroupDivider(startInset = 16.dp, endInset = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_show_gif_when_configured),
                        description = stringResource(Res.string.collections_editor_show_gif_when_configured_desc),
                        checked = folder.mobileFocusGifEnabled,
                        isTablet = false,
                        onCheckedChange = { CollectionEditorRepository.updateFolderMobileFocusGifEnabled(it) },
                    )
                }
            }
        }

        item(key = "folder-sources") {
            EditorSection(title = stringResource(Res.string.collections_editor_section_catalog_sources)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NuvioPillButton(
                        text = stringResource(Res.string.source_tmdb),
                        icon = Icons.Rounded.Add,
                        onClick = {
                            CollectionEditorRepository.showTmdbSourcePicker()
                            onNavigateToPage?.invoke(CollectionEditorPage.TmdbSourcePicker, tmdbSourcePickerTitle)
                        },
                    )
                    NuvioPillButton(
                        text = stringResource(Res.string.collections_editor_add_trakt_source),
                        icon = Icons.Rounded.Add,
                        onClick = {
                            CollectionEditorRepository.showTraktSourcePicker()
                            onNavigateToPage?.invoke(CollectionEditorPage.TraktSourcePicker, traktSourcePickerTitle)
                        },
                    )
                    NuvioPillButton(
                        text = stringResource(Res.string.collections_editor_add_catalog),
                        icon = Icons.Rounded.Add,
                        onClick = {
                            CollectionEditorRepository.showCatalogPicker()
                            onNavigateToPage?.invoke(CollectionEditorPage.CatalogPicker, catalogPickerTitle)
                        },
                    )
                }
                val sources = folder.resolvedSources
                if (sources.isEmpty()) {
                    NuvioSurfaceCard(outlined = true) {
                        NuvioEmptyBlock(
                            icon = Icons.Rounded.Layers,
                            title = stringResource(Res.string.collections_editor_catalog_sources_empty_title),
                            message = stringResource(Res.string.collections_editor_catalog_sources_empty_subtitle),
                            tone = NuvioIconChipTone.Neutral,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    NuvioGroup {
                        sources.forEachIndexed { index, source ->
                            if (index > 0) NuvioGroupDivider()
                            val addonSource = source.addonCatalogSource()
                            if (source.isTmdb) {
                                FolderTmdbSourceCard(
                                    source = source,
                                    onRemove = { CollectionEditorRepository.removeCatalogSource(index) },
                                )
                            } else if (source.isTrakt) {
                                FolderTraktSourceCard(
                                    source = source,
                                    onEdit = {
                                        CollectionEditorRepository.editTraktSource(index)
                                        onNavigateToPage?.invoke(
                                            CollectionEditorPage.TraktSourcePicker,
                                            editTraktSourcePickerTitle,
                                        )
                                    },
                                    onRemove = { CollectionEditorRepository.removeCatalogSource(index) },
                                )
                            } else if (addonSource != null) {
                                FolderCatalogSourceCard(
                                    source = addonSource,
                                    matchingCatalog = state.availableCatalogs.findAvailableCatalog(addonSource),
                                    onRemove = { CollectionEditorRepository.removeCatalogSource(index) },
                                    onOpenGenrePicker = { CollectionEditorRepository.showGenrePicker(index) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "folder-bottom-space") {
            Spacer(modifier = Modifier.height(24.dp + bottomInset))
        }
    }
}

@Composable
private fun CatalogPickerScreen(
    availableCatalogs: List<AvailableCatalog>,
    selectedSources: List<CollectionCatalogSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onBack: () -> Unit,
) {
    PlatformBackHandler(enabled = true) {
        onBack()
    }

    NuvioScreen(modifier = Modifier.fillMaxSize()) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.collections_editor_select_catalogs),
                onBack = onBack,
            )
        }

        item {
            NuvioSurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(Res.string.collections_editor_select_catalogs_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                    Text(
                        text = stringResource(Res.string.collections_editor_selected_count, selectedSources.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.nuvio.colors.accent,
                    )
                }
            }
        }

        val grouped = availableCatalogs.groupBy { it.addonName }
        grouped.forEach { (addonName, catalogs) ->
            item {
                val selectedCount = catalogs.count { catalog ->
                    selectedSources.any {
                        it.addonId == catalog.addonId &&
                            it.type == catalog.type &&
                            it.catalogId == catalog.catalogId
                    }
                }
                PickerPanel(
                    title = addonName,
                    subtitle = if (selectedCount > 0) {
                        stringResource(Res.string.collections_editor_catalog_selected_count, selectedCount)
                    } else {
                        stringResource(Res.string.collections_editor_catalog_count, catalogs.size)
                    },
                ) {
                    catalogs.forEachIndexed { index, catalog ->
                        val isSelected = selectedSources.any {
                            it.addonId == catalog.addonId &&
                                it.type == catalog.type &&
                                it.catalogId == catalog.catalogId
                        }
                        PickerOptionRow(
                            title = catalog.catalogName,
                            subtitle = catalog.type.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase() else it.toString()
                            },
                            selected = isSelected,
                            onClick = { onToggle(catalog) },
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp + nuvioSafeBottomPadding()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TmdbSourcePickerScreen(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
) {
    val bottomInset = nuvioSafeBottomPadding()
    val sourceType = when (state.tmdbBuilderMode) {
        TmdbBuilderMode.PRESETS -> TmdbCollectionSourceType.DISCOVER
        TmdbBuilderMode.LIST -> TmdbCollectionSourceType.LIST
        TmdbBuilderMode.COLLECTION -> TmdbCollectionSourceType.COLLECTION
        TmdbBuilderMode.PRODUCTION -> TmdbCollectionSourceType.COMPANY
        TmdbBuilderMode.NETWORK -> TmdbCollectionSourceType.NETWORK
        TmdbBuilderMode.PERSON -> TmdbCollectionSourceType.PERSON
        TmdbBuilderMode.DIRECTOR -> TmdbCollectionSourceType.DIRECTOR
        TmdbBuilderMode.DISCOVER -> TmdbCollectionSourceType.DISCOVER
    }
    val requiresId = sourceType != TmdbCollectionSourceType.DISCOVER
    val showMediaControls = state.tmdbBuilderMode == TmdbBuilderMode.PRODUCTION ||
        state.tmdbBuilderMode == TmdbBuilderMode.PERSON ||
        state.tmdbBuilderMode == TmdbBuilderMode.DIRECTOR ||
        state.tmdbBuilderMode == TmdbBuilderMode.DISCOVER
    val showSortControls = state.tmdbBuilderMode == TmdbBuilderMode.PRODUCTION ||
        state.tmdbBuilderMode == TmdbBuilderMode.NETWORK ||
        state.tmdbBuilderMode == TmdbBuilderMode.PERSON ||
        state.tmdbBuilderMode == TmdbBuilderMode.DIRECTOR ||
        state.tmdbBuilderMode == TmdbBuilderMode.DISCOVER
    val showFilterControls = state.tmdbBuilderMode == TmdbBuilderMode.DISCOVER

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NuvioScreen(modifier = Modifier.fillMaxSize()) {
            stickyHeader {
                NuvioScreenHeader(
                    title = stringResource(Res.string.collections_editor_tmdb_sources),
                    onBack = onBack,
                )
            }

            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TmdbBuilderMode.entries.forEach { mode ->
                        Chip(
                            label = tmdbBuilderModeLabel(mode),
                            selected = state.tmdbBuilderMode == mode,
                            onClick = { CollectionEditorRepository.setTmdbBuilderMode(mode) },
                            showSelectedCheck = true,
                        )
                    }
                }
            }

            item {
                NuvioSurfaceCard {
                    Text(
                        text = tmdbModeHelpText(state.tmdbBuilderMode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
            }

            if (state.tmdbBuilderMode != TmdbBuilderMode.PRESETS) item {
                NuvioSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (requiresId) {
                            TmdbLabeledField(
                                label = tmdbInputLabel(state.tmdbBuilderMode),
                                value = state.tmdbInput,
                                onValueChange = { CollectionEditorRepository.setTmdbInput(it) },
                                placeholder = tmdbInputPlaceholder(state.tmdbBuilderMode),
                                helper = tmdbInputHelper(state.tmdbBuilderMode),
                            )
                        }
                        TmdbLabeledField(
                            label = stringResource(Res.string.collections_editor_tmdb_display_title),
                            value = state.tmdbTitleInput,
                            onValueChange = { CollectionEditorRepository.setTmdbTitleInput(it) },
                            placeholder = tmdbTitlePlaceholder(state.tmdbBuilderMode),
                            helper = stringResource(Res.string.collections_editor_tmdb_title_helper),
                        )
                        if (state.tmdbSearchError != null) {
                            Text(
                                text = state.tmdbSearchError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.nuvio.colors.danger,
                            )
                        }
                    }
                }
            }

            if (state.tmdbBuilderMode == TmdbBuilderMode.PRODUCTION && state.tmdbCompanyResults.isNotEmpty()) {
                item {
                    PickerSectionLabel(stringResource(Res.string.collections_editor_tmdb_search_results))
                }
                itemsIndexed(state.tmdbCompanyResults) { _, result ->
                    val title = result.name ?: stringResource(Res.string.collections_editor_tmdb_company_fallback, result.id)
                    val movieSuffix = stringResource(Res.string.collections_editor_tmdb_movies)
                    val seriesSuffix = stringResource(Res.string.collections_editor_tmdb_series)
                    PickerOptionRow(
                        title = title,
                        subtitle = listOfNotNull(
                            stringResource(Res.string.collections_editor_tmdb_subtitle_production),
                            result.originCountry,
                        ).joinToString(" • "),
                        selected = false,
                        onClick = {
                            val sources = tmdbSelectedMediaTypes(state).map { mediaType ->
                                CollectionSource(
                                    provider = "tmdb",
                                    tmdbSourceType = TmdbCollectionSourceType.COMPANY.name,
                                    title = tmdbTitleForMedia(title, mediaType, state.tmdbMediaBoth, movieSuffix, seriesSuffix),
                                    tmdbId = result.id,
                                    mediaType = mediaType.name,
                                    sortBy = state.tmdbSortBy,
                                    filters = state.tmdbFilters,
                                )
                            }
                            CollectionEditorRepository.addTmdbSourcesFromPicker(sources)
                        },
                    )
                }
            }

            if (state.tmdbBuilderMode == TmdbBuilderMode.COLLECTION && state.tmdbCollectionResults.isNotEmpty()) {
                item {
                    PickerSectionLabel(stringResource(Res.string.collections_editor_tmdb_search_results))
                }
                itemsIndexed(state.tmdbCollectionResults) { _, result ->
                    val title = result.name ?: stringResource(Res.string.collections_editor_tmdb_collection_fallback, result.id)
                    PickerOptionRow(
                        title = title,
                        subtitle = stringResource(Res.string.collections_editor_tmdb_collection),
                        selected = false,
                        onClick = {
                            CollectionEditorRepository.addTmdbSource(
                                CollectionSource(
                                    provider = "tmdb",
                                    tmdbSourceType = TmdbCollectionSourceType.COLLECTION.name,
                                    title = title,
                                    tmdbId = result.id,
                                    mediaType = TmdbCollectionMediaType.MOVIE.name,
                                    sortBy = state.tmdbSortBy,
                                ),
                            )
                        },
                    )
                }
            }

            if (showMediaControls) {
                item {
                    PickerPanel(
                        title = stringResource(Res.string.collections_editor_tmdb_type),
                    ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Chip(
                                    label = stringResource(Res.string.collections_editor_tmdb_movies),
                                    selected = state.tmdbMediaType == TmdbCollectionMediaType.MOVIE && !state.tmdbMediaBoth,
                                    onClick = {
                                        CollectionEditorRepository.setTmdbMediaBoth(false)
                                        CollectionEditorRepository.setTmdbMediaType(TmdbCollectionMediaType.MOVIE)
                                    },
                                    showSelectedCheck = true,
                                )
                                Chip(
                                    label = stringResource(Res.string.collections_editor_tmdb_series),
                                    selected = state.tmdbMediaType == TmdbCollectionMediaType.TV && !state.tmdbMediaBoth,
                                    onClick = {
                                        CollectionEditorRepository.setTmdbMediaBoth(false)
                                        CollectionEditorRepository.setTmdbMediaType(TmdbCollectionMediaType.TV)
                                    },
                                    showSelectedCheck = true,
                                )
                                Chip(
                                    label = stringResource(Res.string.collections_editor_tmdb_both),
                                    selected = state.tmdbMediaBoth,
                                    onClick = { CollectionEditorRepository.setTmdbMediaBoth(true) },
                                    showSelectedCheck = true,
                                )
                            }
                    }
                }
            }

            if (showSortControls) {
                item {
                    PickerPanel(
                        title = stringResource(Res.string.collections_editor_tmdb_sort),
                    ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val sorts = listOf(
                                    TmdbCollectionSort.POPULAR_DESC,
                                    TmdbCollectionSort.VOTE_AVERAGE_DESC,
                                    TmdbCollectionSort.VOTE_COUNT_DESC,
                                    if (state.tmdbMediaType == TmdbCollectionMediaType.TV && !state.tmdbMediaBoth) {
                                        TmdbCollectionSort.FIRST_AIR_DATE_DESC
                                    } else {
                                        TmdbCollectionSort.RELEASE_DATE_DESC
                                    },
                                )
                                sorts.forEach { sort ->
                                    Chip(
                                        label = tmdbSortLabel(sort),
                                        selected = state.tmdbSortBy == sort.value,
                                        onClick = { CollectionEditorRepository.setTmdbSortBy(sort.value) },
                                        showSelectedCheck = true,
                                    )
                                }
                            }
                    }
                }
            }

            if (showFilterControls) {
                item {
                    PickerPanel(
                        title = stringResource(Res.string.collections_editor_tmdb_filters),
                        subtitle = stringResource(Res.string.collections_editor_tmdb_filters_helper),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_genres),
                                chips = tmdbGenreQuickChips(state.tmdbMediaType),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withGenres = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_genres),
                                helper = stringResource(Res.string.collections_editor_tmdb_genres_helper),
                                value = state.tmdbFilters.withGenres.orEmpty(),
                                placeholder = if (state.tmdbMediaType == TmdbCollectionMediaType.MOVIE) {
                                    stringResource(Res.string.collections_editor_tmdb_genres_movie_placeholder)
                                } else {
                                    stringResource(Res.string.collections_editor_tmdb_genres_series_placeholder)
                                },
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withGenres = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_without_genres),
                                helper = stringResource(Res.string.collections_editor_tmdb_without_genres_helper),
                                value = state.tmdbFilters.withoutGenres.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_without_genres_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withoutGenres = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_date_from),
                                helper = stringResource(Res.string.collections_editor_tmdb_date_helper),
                                value = state.tmdbFilters.releaseDateGte.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_date_from_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(releaseDateGte = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_date_to),
                                helper = stringResource(Res.string.collections_editor_tmdb_date_helper),
                                value = state.tmdbFilters.releaseDateLte.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_date_to_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(releaseDateLte = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_rating_min),
                                helper = stringResource(Res.string.collections_editor_tmdb_rating_helper),
                                value = state.tmdbFilters.voteAverageGte?.toString().orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_rating_min_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(voteAverageGte = value.toDoubleOrNull())
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_rating_max),
                                helper = stringResource(Res.string.collections_editor_tmdb_rating_helper),
                                value = state.tmdbFilters.voteAverageLte?.toString().orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_rating_max_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(voteAverageLte = value.toDoubleOrNull())
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_votes_min),
                                helper = stringResource(Res.string.collections_editor_tmdb_votes_helper),
                                value = state.tmdbFilters.voteCountGte?.toString().orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_votes_min_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(voteCountGte = value.toIntOrNull())
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_languages),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_language_english) to "en",
                                    stringResource(Res.string.collections_editor_tmdb_language_korean) to "ko",
                                    stringResource(Res.string.collections_editor_tmdb_language_japanese) to "ja",
                                    stringResource(Res.string.collections_editor_tmdb_language_hindi) to "hi",
                                    stringResource(Res.string.collections_editor_tmdb_language_spanish) to "es",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withOriginalLanguage = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_language),
                                helper = stringResource(Res.string.collections_editor_tmdb_language_helper),
                                value = state.tmdbFilters.withOriginalLanguage.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_language_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withOriginalLanguage = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_countries),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_country_us) to "US",
                                    stringResource(Res.string.collections_editor_tmdb_country_korea) to "KR",
                                    stringResource(Res.string.collections_editor_tmdb_country_japan) to "JP",
                                    stringResource(Res.string.collections_editor_tmdb_country_india) to "IN",
                                    stringResource(Res.string.collections_editor_tmdb_country_uk) to "GB",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withOriginCountry = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_country),
                                helper = stringResource(Res.string.collections_editor_tmdb_country_helper),
                                value = state.tmdbFilters.withOriginCountry.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_country_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withOriginCountry = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_keywords),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_keyword_superhero) to "9715",
                                    stringResource(Res.string.collections_editor_tmdb_keyword_based_on_novel) to "818",
                                    stringResource(Res.string.collections_editor_tmdb_keyword_time_travel) to "4379",
                                    stringResource(Res.string.collections_editor_tmdb_keyword_space) to "9882",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withKeywords = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_keywords),
                                helper = stringResource(Res.string.collections_editor_tmdb_keywords_helper),
                                value = state.tmdbFilters.withKeywords.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_keywords_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withKeywords = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_without_keywords),
                                helper = stringResource(Res.string.collections_editor_tmdb_without_keywords_helper),
                                value = state.tmdbFilters.withoutKeywords.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_without_keywords_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withoutKeywords = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_studios),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_studio_marvel) to "420",
                                    stringResource(Res.string.collections_editor_tmdb_studio_disney) to "2",
                                    stringResource(Res.string.collections_editor_tmdb_studio_pixar) to "3",
                                    stringResource(Res.string.collections_editor_tmdb_studio_lucasfilm) to "1",
                                    stringResource(Res.string.collections_editor_tmdb_studio_warner) to "174",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withCompanies = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_companies),
                                helper = stringResource(Res.string.collections_editor_tmdb_companies_helper),
                                value = state.tmdbFilters.withCompanies.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_companies_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withCompanies = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_without_companies),
                                helper = stringResource(Res.string.collections_editor_tmdb_without_companies_helper),
                                value = state.tmdbFilters.withoutCompanies.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_without_companies_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withoutCompanies = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_networks),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_network_netflix) to "213",
                                    stringResource(Res.string.collections_editor_tmdb_network_hbo) to "49",
                                    stringResource(Res.string.collections_editor_tmdb_network_disney_plus) to "2739",
                                    stringResource(Res.string.collections_editor_tmdb_network_prime_video) to "1024",
                                    stringResource(Res.string.collections_editor_tmdb_network_hulu) to "453",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withNetworks = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_networks),
                                helper = stringResource(Res.string.collections_editor_tmdb_networks_helper),
                                value = state.tmdbFilters.withNetworks.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_networks_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withNetworks = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_year),
                                helper = stringResource(Res.string.collections_editor_tmdb_year_helper),
                                value = state.tmdbFilters.year?.toString().orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_year_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(year = value.toIntOrNull())
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_watch_providers),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_watch_provider_netflix) to "8",
                                    stringResource(Res.string.collections_editor_tmdb_watch_provider_prime) to "119",
                                    stringResource(Res.string.collections_editor_tmdb_watch_provider_disney) to "337",
                                    stringResource(Res.string.collections_editor_tmdb_watch_provider_apple) to "350",
                                    stringResource(Res.string.collections_editor_tmdb_watch_provider_hulu) to "15",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(withWatchProviders = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_watch_providers),
                                helper = stringResource(Res.string.collections_editor_tmdb_watch_providers_helper),
                                value = state.tmdbFilters.withWatchProviders.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_watch_providers_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withWatchProviders = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_without_watch_providers),
                                helper = stringResource(Res.string.collections_editor_tmdb_without_watch_providers_helper),
                                value = state.tmdbFilters.withoutWatchProviders.orEmpty(),
                                placeholder = stringResource(Res.string.collections_editor_tmdb_without_watch_providers_placeholder),
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(withoutWatchProviders = value.ifBlank { null })
                                    }
                                },
                            )
                            TmdbQuickChips(
                                label = stringResource(Res.string.collections_editor_tmdb_quick_watch_regions),
                                chips = listOf(
                                    stringResource(Res.string.collections_editor_tmdb_country_us) to "US",
                                    stringResource(Res.string.collections_editor_tmdb_country_uk) to "GB",
                                    stringResource(Res.string.collections_editor_tmdb_country_ca) to "CA",
                                    stringResource(Res.string.collections_editor_tmdb_country_au) to "AU",
                                    stringResource(Res.string.collections_editor_tmdb_country_de) to "DE",
                                ),
                                onSelect = { value ->
                                    CollectionEditorRepository.updateTmdbFilters { it.copy(watchRegion = value) }
                                },
                            )
                            TmdbFilterField(
                                label = stringResource(Res.string.collections_editor_tmdb_watch_region),
                                helper = stringResource(Res.string.collections_editor_tmdb_watch_region_helper),
                                value = state.tmdbFilters.watchRegion.orEmpty(),
                                placeholder = "US",
                                onValueChange = { value ->
                                    CollectionEditorRepository.updateTmdbFilters {
                                        it.copy(watchRegion = value.ifBlank { null })
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (state.tmdbBuilderMode == TmdbBuilderMode.PRESETS) item {
                PickerSectionLabel(stringResource(Res.string.collections_editor_tmdb_presets))
            }
            if (state.tmdbBuilderMode == TmdbBuilderMode.PRESETS) {
                itemsIndexed(TmdbCollectionSourceResolver.presets()) { _, preset ->
                    PickerOptionRow(
                        title = preset.label,
                        subtitle = tmdbSourceSubtitle(preset.source),
                        selected = false,
                        onClick = { CollectionEditorRepository.addTmdbPreset(preset.source) },
                    )
                }
            }

            item {
                val spacerHeight = if (state.tmdbBuilderMode == TmdbBuilderMode.PRESETS) {
                    24.dp + bottomInset
                } else {
                    96.dp + bottomInset
                }
                Spacer(modifier = Modifier.height(spacerHeight))
            }
        }

        if (state.tmdbBuilderMode != TmdbBuilderMode.PRESETS) {
            EditorBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                PickerActionBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(bottom = bottomInset),
                ) {
                    if (sourceType == TmdbCollectionSourceType.COMPANY || sourceType == TmdbCollectionSourceType.COLLECTION) {
                        DialogButton(
                            text = stringResource(Res.string.collections_editor_tmdb_search),
                            onClick = {
                                if (sourceType == TmdbCollectionSourceType.COMPANY) {
                                    CollectionEditorRepository.searchTmdbCompanies()
                                } else {
                                    CollectionEditorRepository.searchTmdbCollections()
                                }
                            },
                        )
                    }
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.collections_editor_add_source),
                        modifier = Modifier.weight(1f),
                        enabled = !requiresId || state.tmdbInput.isNotBlank(),
                        onClick = { CollectionEditorRepository.addTmdbSourceFromInput() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraktSourcePickerScreen(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
) {
    val bottomInset = nuvioSafeBottomPadding()
    val searchResultsTitle = stringResource(Res.string.collections_editor_trakt_search_results)
    val trendingTitle = stringResource(Res.string.collections_editor_trakt_trending)
    val popularTitle = stringResource(Res.string.collections_editor_trakt_popular)

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NuvioScreen(modifier = Modifier.fillMaxSize()) {
            stickyHeader {
                NuvioScreenHeader(
                    title = if (state.editingTraktSourceIndex != null) {
                        stringResource(Res.string.collections_editor_edit_trakt_source)
                    } else {
                        stringResource(Res.string.collections_editor_trakt_sources)
                    },
                    onBack = onBack,
                )
            }

            item {
                NuvioSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TmdbLabeledField(
                            label = stringResource(Res.string.collections_editor_trakt_list),
                            value = state.traktInput,
                            onValueChange = { CollectionEditorRepository.setTraktInput(it) },
                            placeholder = stringResource(Res.string.collections_editor_trakt_input_placeholder),
                            helper = stringResource(Res.string.collections_editor_trakt_input_helper),
                        )
                        TmdbLabeledField(
                            label = stringResource(Res.string.collections_editor_tmdb_display_title),
                            value = state.traktTitleInput,
                            onValueChange = { CollectionEditorRepository.setTraktTitleInput(it) },
                            placeholder = stringResource(Res.string.collections_editor_trakt_title_placeholder),
                            helper = stringResource(Res.string.collections_editor_tmdb_title_helper),
                        )
                        if (state.traktSearchError != null) {
                            Text(
                                text = state.traktSearchError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.nuvio.colors.danger,
                            )
                        }
                    }
                }
            }

            item {
                PickerPanel(title = stringResource(Res.string.collections_editor_tmdb_type)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip(
                            label = stringResource(Res.string.collections_editor_tmdb_movies),
                            selected = state.traktMediaType == TmdbCollectionMediaType.MOVIE && !state.traktMediaBoth,
                            onClick = {
                                CollectionEditorRepository.setTraktMediaBoth(false)
                                CollectionEditorRepository.setTraktMediaType(TmdbCollectionMediaType.MOVIE)
                            },
                            showSelectedCheck = true,
                        )
                        Chip(
                            label = stringResource(Res.string.collections_editor_tmdb_series),
                            selected = state.traktMediaType == TmdbCollectionMediaType.TV && !state.traktMediaBoth,
                            onClick = {
                                CollectionEditorRepository.setTraktMediaBoth(false)
                                CollectionEditorRepository.setTraktMediaType(TmdbCollectionMediaType.TV)
                            },
                            showSelectedCheck = true,
                        )
                        Chip(
                            label = stringResource(Res.string.collections_editor_tmdb_both),
                            selected = state.traktMediaBoth,
                            onClick = { CollectionEditorRepository.setTraktMediaBoth(true) },
                            showSelectedCheck = true,
                        )
                    }
                }
            }

            item {
                PickerPanel(title = stringResource(Res.string.collections_editor_tmdb_sort)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        traktSortOptions().forEach { (value, label) ->
                            Chip(
                                label = label,
                                selected = state.traktSortBy == value,
                                onClick = { CollectionEditorRepository.setTraktSortBy(value) },
                                showSelectedCheck = true,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(Res.string.collections_editor_trakt_direction),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Chip(
                                label = stringResource(Res.string.collections_editor_trakt_ascending),
                                selected = state.traktSortHow == TraktSortHow.ASC.value,
                                onClick = { CollectionEditorRepository.setTraktSortHow(TraktSortHow.ASC.value) },
                                showSelectedCheck = true,
                            )
                            Chip(
                                label = stringResource(Res.string.collections_editor_trakt_descending),
                                selected = state.traktSortHow == TraktSortHow.DESC.value,
                                onClick = { CollectionEditorRepository.setTraktSortHow(TraktSortHow.DESC.value) },
                                showSelectedCheck = true,
                            )
                        }
                    }
                }
            }

            TraktResultSection(
                title = searchResultsTitle,
                results = state.traktSearchResults,
            )
            TraktResultSection(
                title = trendingTitle,
                results = state.traktTrendingResults,
            )
            TraktResultSection(
                title = popularTitle,
                results = state.traktPopularResults,
            )

            item {
                Spacer(modifier = Modifier.height(96.dp + bottomInset))
            }
        }

        EditorBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            PickerActionBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = bottomInset),
            ) {
                DialogButton(
                    text = stringResource(Res.string.collections_editor_tmdb_search),
                    onClick = { CollectionEditorRepository.searchTraktLists() },
                )
                NuvioPrimaryButton(
                    text = if (state.editingTraktSourceIndex != null) {
                        stringResource(Res.string.collections_editor_save)
                    } else {
                        stringResource(Res.string.collections_editor_add_source)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state.traktInput.isNotBlank(),
                    onClick = { CollectionEditorRepository.addTraktSourceFromInput() },
                )
            }
        }
    }
}

private fun LazyListScope.TraktResultSection(
    title: String,
    results: List<TraktPublicListSearchResult>,
) {
    if (results.isEmpty()) return
    item {
        PickerSectionLabel(title)
    }
    itemsIndexed(results) { _, result ->
        PickerOptionRow(
            title = result.title,
            subtitle = result.subtitle,
            selected = false,
            onClick = { CollectionEditorRepository.addTraktSourceFromResult(result) },
        )
    }
}

/** A titled group in the source pickers. */
@Composable
private fun PickerPanel(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioGroup {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
            content()
        }
    }
}

/** A selectable row in a picker group: a soft highlight and a check when selected. */
@Composable
private fun PickerOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(if (selected) tokens.colors.accent.copy(alpha = tokens.opacity.pressed) else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(Res.string.cd_selected),
                tint = tokens.colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PickerSectionLabel(text: String) {
    NuvioSectionLabel(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun PickerActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The bar pinned under a picker: a hairline on top and the page colour behind the buttons. */
@Composable
private fun EditorBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .background(tokens.colors.background.copy(alpha = 0.96f))
            .nuvioConsumePointerEvents(),
    ) {
        NuvioGroupDivider(startInset = 0.dp)
        content()
    }
}

@Composable
private fun TmdbLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    helper: String,
) {
    NuvioLabeledField(label = label, helper = helper.takeIf { it.isNotBlank() }) {
        NuvioInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            minHeight = 48.dp,
        )
    }
}

@Composable
private fun TmdbFilterField(
    label: String,
    helper: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    TmdbLabeledField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        helper = helper,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TmdbQuickChips(
    label: String,
    chips: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NuvioFieldLabel(label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { (chipLabel, value) ->
                Chip(label = chipLabel, selected = false, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
private fun tmdbGenreQuickChips(mediaType: TmdbCollectionMediaType): List<Pair<String, String>> =
    when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> listOf(
            stringResource(Res.string.collections_editor_tmdb_genre_action) to "28",
            stringResource(Res.string.collections_editor_tmdb_genre_adventure) to "12",
            stringResource(Res.string.collections_editor_tmdb_genre_animation) to "16",
            stringResource(Res.string.collections_editor_tmdb_genre_comedy) to "35",
            stringResource(Res.string.collections_editor_tmdb_genre_horror) to "27",
            stringResource(Res.string.collections_editor_tmdb_genre_scifi) to "878",
        )
        TmdbCollectionMediaType.TV -> listOf(
            stringResource(Res.string.collections_editor_tmdb_genre_drama) to "18",
            stringResource(Res.string.collections_editor_tmdb_genre_comedy) to "35",
            stringResource(Res.string.collections_editor_tmdb_genre_animation) to "16",
            stringResource(Res.string.collections_editor_tmdb_genre_crime) to "80",
            stringResource(Res.string.collections_editor_tmdb_genre_scifi) to "10765",
            stringResource(Res.string.collections_editor_tmdb_genre_reality) to "10764",
        )
    }

private fun tmdbSelectedMediaTypes(state: CollectionEditorUiState): List<TmdbCollectionMediaType> =
    if (state.tmdbMediaBoth) {
        listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV)
    } else {
        listOf(state.tmdbMediaType)
    }

private fun tmdbTitleForMedia(
    title: String,
    mediaType: TmdbCollectionMediaType,
    addSuffix: Boolean,
    movieSuffix: String,
    seriesSuffix: String,
): String {
    if (!addSuffix) return title
    val suffix = when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> movieSuffix
        TmdbCollectionMediaType.TV -> seriesSuffix
    }
    return "$title $suffix"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenrePickerSheet(
    title: String,
    selectedGenre: String?,
    genreOptions: List<String>,
    allowAll: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.nuvio.colors.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.collections_editor_genre_filter),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
            }

            if (allowAll) {
                item {
                    GenrePickerOptionRow(
                        title = stringResource(Res.string.collections_editor_all_genres),
                        selected = selectedGenre == null,
                        onClick = { onSelect(null) },
                    )
                }
            }

            itemsIndexed(genreOptions) { _, genre ->
                GenrePickerOptionRow(
                    title = genre,
                    selected = selectedGenre == genre,
                    onClick = { onSelect(genre) },
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** A source row inside the folder's source group: icon chip, name, kind and actions. */
@Composable
private fun FolderSourceRow(
    icon: ImageVector,
    title: String,
    kind: String,
    detail: String?,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    NuvioGroupRow(
        title = title,
        description = kind,
        titleMaxLines = 1,
        descriptionMaxLines = 1,
        verticalPadding = 12.dp,
        endPadding = 8.dp,
        leading = { NuvioIconChip(icon) },
        meta = {
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            footer?.invoke(this)
        },
        trailing = {
            if (onEdit != null) {
                NuvioSquareIconButton(
                    icon = Icons.Rounded.Edit,
                    contentDescription = stringResource(Res.string.action_edit),
                    onClick = onEdit,
                    style = NuvioSquareIconButtonStyle.Ghost,
                    iconSize = 18.dp,
                )
            }
            NuvioSquareIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_remove),
                onClick = onRemove,
                style = NuvioSquareIconButtonStyle.Ghost,
                iconSize = 18.dp,
                tint = tokens.colors.danger,
            )
        },
    )
}

@Composable
private fun FolderTmdbSourceCard(
    source: CollectionSource,
    onRemove: () -> Unit,
) {
    FolderSourceRow(
        icon = Icons.Rounded.Movie,
        title = source.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.source_tmdb),
        kind = stringResource(Res.string.source_tmdb),
        detail = tmdbSourceSubtitle(source),
        onRemove = onRemove,
    )
}

@Composable
private fun FolderTraktSourceCard(
    source: CollectionSource,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    FolderSourceRow(
        icon = Icons.AutoMirrored.Rounded.List,
        title = source.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.source_trakt),
        kind = stringResource(Res.string.source_trakt),
        detail = traktSourceSubtitle(source),
        onRemove = onRemove,
        onEdit = onEdit,
    )
}

@Composable
private fun FolderCatalogSourceCard(
    source: CollectionCatalogSource,
    matchingCatalog: AvailableCatalog?,
    onRemove: () -> Unit,
    onOpenGenrePicker: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val typeLabel = source.type.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }
    val genreOptions = matchingCatalog?.genreOptions.orEmpty()
    val selectedGenreLabel = source.genre ?: if (matchingCatalog?.genreRequired == true) {
        stringResource(Res.string.collections_editor_select_genre)
    } else {
        stringResource(Res.string.collections_editor_all_genres)
    }
    FolderSourceRow(
        icon = Icons.Rounded.Layers,
        title = matchingCatalog?.catalogName ?: source.catalogId,
        kind = matchingCatalog?.addonName ?: source.addonId,
        detail = "$typeLabel · ${source.catalogId}",
        onRemove = onRemove,
        footer = if (genreOptions.isEmpty()) {
            null
        } else {
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(Res.string.collections_editor_genre_filter),
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            fontWeight = FontWeight.Medium,
                            color = tokens.colors.textSecondary,
                        )
                        Text(
                            text = selectedGenreLabel,
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NuvioPillButton(
                        text = stringResource(Res.string.collections_editor_choose_genre),
                        onClick = onOpenGenrePicker,
                        height = 32.dp,
                    )
                }
            }
        },
    )
}

@Composable
private fun tmdbBuilderModeLabel(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.PRESETS -> stringResource(Res.string.collections_editor_tmdb_presets)
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_public_list_mode)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_production_mode)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_mode)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_mode)
        TmdbBuilderMode.PERSON -> stringResource(Res.string.collections_editor_tmdb_person_mode)
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_director_mode)
        TmdbBuilderMode.DISCOVER -> stringResource(Res.string.collections_editor_tmdb_custom_mode)
    }

@Composable
private fun tmdbModeHelpText(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.PRESETS -> stringResource(Res.string.collections_editor_tmdb_help_presets)
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_help_list)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_help_production)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_help_network)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_help_collection)
        TmdbBuilderMode.PERSON -> stringResource(Res.string.collections_editor_tmdb_help_person)
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_help_director)
        TmdbBuilderMode.DISCOVER -> stringResource(Res.string.collections_editor_tmdb_help_discover)
    }

@Composable
private fun tmdbInputLabel(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_public_list)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_id)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_id)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_company_search)
        TmdbBuilderMode.PERSON,
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_person_id)
        else -> stringResource(Res.string.collections_editor_tmdb_id_or_url)
    }

@Composable
private fun tmdbInputPlaceholder(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_list_placeholder)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_placeholder)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_placeholder)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_company_placeholder)
        TmdbBuilderMode.PERSON,
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_person_placeholder)
        else -> stringResource(Res.string.collections_editor_tmdb_id_or_url)
    }

@Composable
private fun tmdbInputHelper(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_search_helper)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_helper)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_helper)
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_list_helper)
        TmdbBuilderMode.PERSON,
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_person_helper)
        else -> ""
    }

@Composable
private fun tmdbTitlePlaceholder(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.DISCOVER -> stringResource(Res.string.collections_editor_tmdb_discover_title_placeholder)
        TmdbBuilderMode.PERSON -> stringResource(Res.string.collections_editor_tmdb_person_title_placeholder)
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_director_title_placeholder)
        else -> stringResource(Res.string.collections_editor_tmdb_title_placeholder)
    }

@Composable
private fun tmdbSortLabel(sort: TmdbCollectionSort): String =
    when (sort) {
        TmdbCollectionSort.ORIGINAL -> stringResource(Res.string.collections_editor_tmdb_sort_original)
        TmdbCollectionSort.POPULAR_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_popular)
        TmdbCollectionSort.VOTE_AVERAGE_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_top_rated)
        TmdbCollectionSort.VOTE_COUNT_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_vote_count)
        TmdbCollectionSort.RELEASE_DATE_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_recent)
        TmdbCollectionSort.FIRST_AIR_DATE_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_recent)
    }

@Composable
private fun traktSortOptions(): List<Pair<String, String>> =
    listOf(
        TraktListSort.RANK.value to stringResource(Res.string.collections_editor_trakt_sort_list_order),
        TraktListSort.ADDED.value to stringResource(Res.string.collections_editor_trakt_sort_recently_added),
        TraktListSort.TITLE.value to stringResource(Res.string.collections_editor_trakt_sort_title),
        TraktListSort.RELEASED.value to stringResource(Res.string.collections_editor_trakt_sort_released),
        TraktListSort.RUNTIME.value to stringResource(Res.string.collections_editor_trakt_sort_runtime),
        TraktListSort.POPULARITY.value to stringResource(Res.string.collections_editor_trakt_sort_popular),
        TraktListSort.PERCENTAGE.value to stringResource(Res.string.collections_editor_trakt_sort_percentage),
        TraktListSort.VOTES.value to stringResource(Res.string.collections_editor_trakt_sort_votes),
    )

@Composable
private fun traktSortLabel(value: String?): String =
    when (TraktListSort.normalize(value)) {
        TraktListSort.ADDED.value -> stringResource(Res.string.collections_editor_trakt_sort_recently_added)
        TraktListSort.TITLE.value -> stringResource(Res.string.collections_editor_trakt_sort_title)
        TraktListSort.RELEASED.value -> stringResource(Res.string.collections_editor_trakt_sort_released)
        TraktListSort.RUNTIME.value -> stringResource(Res.string.collections_editor_trakt_sort_runtime)
        TraktListSort.POPULARITY.value -> stringResource(Res.string.collections_editor_trakt_sort_popular)
        TraktListSort.PERCENTAGE.value -> stringResource(Res.string.collections_editor_trakt_sort_percentage)
        TraktListSort.VOTES.value -> stringResource(Res.string.collections_editor_trakt_sort_votes)
        else -> stringResource(Res.string.collections_editor_trakt_sort_list_order)
    }

@Composable
private fun traktDirectionLabel(value: String?): String =
    when (TraktSortHow.normalize(value)) {
        TraktSortHow.DESC.value -> stringResource(Res.string.collections_editor_trakt_descending)
        else -> stringResource(Res.string.collections_editor_trakt_ascending)
    }

@Composable
private fun traktSourceSubtitle(source: CollectionSource): String {
    val media = when (TmdbCollectionMediaType.fromString(source.mediaType)) {
        TmdbCollectionMediaType.MOVIE -> stringResource(Res.string.collections_editor_tmdb_movies)
        TmdbCollectionMediaType.TV -> stringResource(Res.string.collections_editor_tmdb_series)
    }
    return listOf(
        media,
        traktSortLabel(source.sortBy),
        traktDirectionLabel(source.sortHow),
        stringResource(Res.string.collections_editor_trakt_list_id_format, source.traktListId ?: ""),
    ).joinToString(" • ")
}

@Composable
private fun tmdbSourceSubtitle(source: CollectionSource): String {
    val media = when (TmdbCollectionMediaType.fromString(source.mediaType)) {
        TmdbCollectionMediaType.MOVIE -> stringResource(Res.string.collections_editor_tmdb_movies)
        TmdbCollectionMediaType.TV -> stringResource(Res.string.collections_editor_tmdb_series)
    }
    val sort = source.sortBy?.let { value ->
        TmdbCollectionSort.entries.firstOrNull { it.value == value }?.let { sort ->
            tmdbSortLabel(sort)
        }
    } ?: stringResource(Res.string.collections_editor_tmdb_sort_popular)
    val sourceType = runCatching {
        TmdbCollectionSourceType.valueOf(source.tmdbSourceType.orEmpty())
    }.getOrDefault(TmdbCollectionSourceType.DISCOVER)
    return when (sourceType) {
        TmdbCollectionSourceType.LIST -> stringResource(Res.string.collections_editor_tmdb_subtitle_list)
        TmdbCollectionSourceType.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_subtitle_movie_collection)
        TmdbCollectionSourceType.COMPANY -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_production),
            media,
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.NETWORK -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_network),
            stringResource(Res.string.collections_editor_tmdb_series),
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.PERSON -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_person),
            media,
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.DIRECTOR -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_director),
            media,
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.DISCOVER -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_discover),
            media,
            sort,
        ).joinToString(" • ")
    }
}

@Composable
private fun posterShapeLabel(shape: PosterShape): String =
    when (shape) {
        PosterShape.Poster -> stringResource(Res.string.collections_editor_shape_poster)
        PosterShape.Square -> stringResource(Res.string.collections_editor_shape_square)
        PosterShape.Landscape -> stringResource(Res.string.collections_editor_shape_wide)
    }

@Composable
private fun GenrePickerOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PickerOptionRow(title = title, selected = selected, onClick = onClick)
}
