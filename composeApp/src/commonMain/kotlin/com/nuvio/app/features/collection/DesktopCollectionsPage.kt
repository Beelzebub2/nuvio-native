package com.nuvio.app.features.collection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.PosterShape
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

/** Widest the desktop Collections content grows; wider windows centre it. */
private val DesktopCollectionsMaxWidth = 1120.dp

/** Below this width cards drop their folder covers so names and actions keep room. */
private val CompactCardWidth = 640.dp

internal fun collectionLazyKey(key: Any): String = "collection:$key"

/**
 * The desktop layout of Collections: a short intro with counts and actions, then one card per
 * collection with its folder covers. Cards reorder by dragging their handle within this page's
 * own list, so the page has a single scroll. Mobile keeps the list in CollectionManagementScreen.
 */
internal fun LazyListScope.desktopCollectionsContent(
    collections: List<KeyedCollection>,
    reorderState: ReorderableLazyListState,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onCopyJson: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    item(key = "collections-intro") {
        CollectionsPageWidth {
            CollectionsIntro(
                collections = collections.map { it.collection },
                onNew = onNew,
                onImport = onImport,
                onCopyJson = onCopyJson,
            )
        }
    }
    if (collections.isEmpty()) {
        item(key = "collections-empty") {
            CollectionsPageWidth { CollectionsEmptyState(onNew = onNew, onImport = onImport) }
        }
        return
    }
    items(collections, key = { collectionLazyKey(it.key) }) { keyed ->
        ReorderableItem(reorderState, key = collectionLazyKey(keyed.key)) { isDragging ->
            CollectionsPageWidth {
                CollectionCard(
                    collection = keyed.collection,
                    dragging = isDragging,
                    dragScope = this@ReorderableItem,
                    onEdit = { onEdit(keyed.collection.id) },
                    onDelete = { onDelete(keyed.collection.id) },
                )
            }
        }
    }
}

/** A collection with the list key it keeps while being dragged. */
internal data class KeyedCollection(val key: Any, val collection: Collection)

/** Centres desktop Collections content, and its header, at [DesktopCollectionsMaxWidth]. */
@Composable
internal fun CollectionsPageWidth(content: @Composable () -> Unit) {
    // The page has no Surface of its own, so give text and icons the theme's colour here.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.nuvio.colors.textPrimary) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = DesktopCollectionsMaxWidth).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun CollectionsIntro(
    collections: List<Collection>,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onCopyJson: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        val stacked = maxWidth < 760.dp
        val text: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.collections_desktop_intro),
                    modifier = Modifier.widthIn(max = 620.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textSecondary,
                )
                if (collections.isNotEmpty()) {
                    val pinned = collections.count { it.pinToTop }
                    Text(
                        text = listOfNotNull(
                            pluralStringResource(Res.plurals.collections_desktop_collection_count, collections.size, collections.size),
                            collections.sumOf { it.folders.size }.let { folders ->
                                pluralStringResource(Res.plurals.collections_desktop_folder_count, folders, folders)
                            },
                            pinned.takeIf { it > 0 }?.let { stringResource(Res.string.collections_desktop_pinned_count, it) },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
        // An empty page offers these in its own card instead.
        val actions: @Composable () -> Unit = {
            if (collections.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NuvioCompactActionButton(
                    label = stringResource(Res.string.collections_import),
                    icon = Icons.Rounded.ContentPaste,
                    onClick = onImport,
                    tone = NuvioCompactActionTone.Ghost,
                    size = NuvioActionSize.Small,
                )
                NuvioCompactActionButton(
                    label = stringResource(Res.string.collections_copy_json),
                    icon = Icons.Rounded.ContentCopy,
                    onClick = onCopyJson,
                    tone = NuvioCompactActionTone.Ghost,
                    size = NuvioActionSize.Small,
                )
                NuvioCompactActionButton(
                    label = stringResource(Res.string.collections_new),
                    icon = Icons.Rounded.Add,
                    onClick = onNew,
                    tone = NuvioCompactActionTone.Primary,
                    size = NuvioActionSize.Small,
                )
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { text(); actions() }
        } else {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f)) { text() }
                actions()
            }
        }
    }
}

@Composable
private fun CollectionCard(
    collection: Collection,
    dragging: Boolean,
    dragScope: ReorderableCollectionItemScope,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            dragging -> tokens.colors.surfaceElevated
            hovered -> tokens.colors.surfaceCard
            else -> tokens.colors.surface
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "collection_card_background",
    )
    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "collection_card_elevation")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < CompactCardWidth
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation, shape)
                .clip(shape)
                .background(background)
                .border(NuvioTokens.Border.hairline, if (dragging) tokens.colors.borderStrong else tokens.colors.borderSubtle, shape)
                .hoverable(interaction)
                .clickable(onClick = onEdit)
                .padding(start = 6.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = with(dragScope) { Modifier.draggableHandle() }
                    .size(width = 28.dp, height = 56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = stringResource(Res.string.action_reorder),
                    tint = if (hovered || dragging) tokens.colors.textSecondary else tokens.colors.textMuted.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (!compact) FolderCovers(collection.folders)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = collection.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (collection.pinToTop) PinnedBadge()
                }
                Text(
                    text = pluralStringResource(Res.plurals.collections_desktop_folder_count, collection.folders.size, collection.folders.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
                Text(
                    text = collection.folders.map { it.title.trim() }.filter { it.isNotEmpty() }
                        .joinToString("  ·  ")
                        .ifEmpty { stringResource(Res.string.collections_desktop_no_folders) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    NuvioIconActionButton(
                        icon = Icons.Rounded.Edit,
                        contentDescription = stringResource(Res.string.action_edit),
                        onClick = onEdit,
                        tone = NuvioCompactActionTone.Secondary,
                        size = NuvioActionSize.Small,
                    )
                } else {
                    NuvioCompactActionButton(
                        label = stringResource(Res.string.action_edit),
                        icon = Icons.Rounded.Edit,
                        onClick = onEdit,
                        tone = NuvioCompactActionTone.Secondary,
                        size = NuvioActionSize.Small,
                    )
                }
                NuvioIconActionButton(
                    icon = Icons.Rounded.Delete,
                    contentDescription = stringResource(Res.string.action_delete),
                    onClick = onDelete,
                    tone = NuvioCompactActionTone.Ghost,
                    size = NuvioActionSize.Small,
                )
            }
        }
    }
}

@Composable
private fun PinnedBadge() {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tokens.colors.accent.copy(alpha = 0.14f))
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PushPin, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(12.dp))
        Text(
            text = stringResource(Res.string.collections_pinned),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The first folders' covers, sized by the collection's tile shape, with "+N" for the rest. */
@Composable
private fun FolderCovers(folders: List<CollectionFolder>) {
    val shape = folders.firstOrNull()?.posterShape ?: PosterShape.Poster
    // Every shape fills the same footprint, so titles line up from card to card.
    val (count, tileHeight) = when (shape) {
        PosterShape.Poster -> 4 to 78.dp
        PosterShape.Square -> 4 to 52.dp
        PosterShape.Landscape -> 2 to 62.dp
    }
    val tileWidth = (FolderCoversWidth - FolderCoverGap * (count - 1)) / count
    val shown = folders.take(count)
    val hidden = folders.size - shown.size
    Row(
        modifier = Modifier.width(FolderCoversWidth),
        horizontalArrangement = Arrangement.spacedBy(FolderCoverGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shown.isEmpty()) {
            FolderCoverTile(folder = null, width = tileWidth, height = tileHeight)
        }
        shown.forEachIndexed { index, folder ->
            val last = index == shown.lastIndex && hidden > 0
            FolderCoverTile(folder = folder, width = tileWidth, height = tileHeight, moreCount = if (last) hidden else 0)
        }
    }
}

private val FolderCoversWidth = 226.dp
private val FolderCoverGap = 6.dp

@Composable
private fun FolderCoverTile(folder: CollectionFolder?, width: Dp, height: Dp, moreCount: Int = 0) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.06f))
            .border(NuvioTokens.Border.hairline, tokens.colors.borderSubtle, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val cover = folder?.coverImageUrl?.takeIf { it.isNotBlank() }
        val emoji = folder?.coverEmoji?.takeIf { it.isNotBlank() }
        when {
            cover != null -> NuvioAsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            emoji != null -> Text(emoji, fontSize = 22.sp)
            folder != null -> Text(
                text = folder.title.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
            else -> Icon(Icons.Rounded.CollectionsBookmark, null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        }
        if (moreCount > 0) {
            Box(
                Modifier.fillMaxSize().background(tokens.colors.background.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.collections_desktop_more_folders, moreCount),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun CollectionsEmptyState(onNew: () -> Unit, onImport: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.xl))
            .border(NuvioTokens.Border.hairline, tokens.colors.borderSubtle, RoundedCornerShape(NuvioTokens.Radius.xl))
            .background(tokens.colors.surface)
            .padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(tokens.colors.textPrimary.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CollectionsBookmark, null, tint = tokens.colors.textMuted, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.collections_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = stringResource(Res.string.collections_desktop_empty_message),
            modifier = Modifier.widthIn(max = 460.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NuvioCompactActionButton(
                label = stringResource(Res.string.collections_import),
                icon = Icons.Rounded.ContentPaste,
                onClick = onImport,
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
            )
            NuvioCompactActionButton(
                label = stringResource(Res.string.collections_new),
                icon = Icons.Rounded.Add,
                onClick = onNew,
                tone = NuvioCompactActionTone.Primary,
                size = NuvioActionSize.Small,
            )
        }
    }
}
