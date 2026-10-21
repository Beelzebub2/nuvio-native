package com.nuvio.app.features.updater

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal suspend fun readBundledReleaseNotes(): String = withContext(Dispatchers.Default) {
    Res.readBytes("files/RELEASE-NOTES.md").decodeToString()
}

private data class BundledNotesState(val notes: String = "", val loading: Boolean = true, val failed: Boolean = false)

@Composable
internal fun BundledChangelogDialog(onDismiss: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val state by produceState(BundledNotesState(), retry) {
        value = BundledNotesState()
        value = try {
            BundledNotesState(notes = readBundledReleaseNotes(), loading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BundledNotesState(loading = false, failed = true)
        }
    }
    ReleaseNotesDialog(
        notes = state.notes,
        title = stringResource(Res.string.changelog_title),
        fallbackTitle = AppVersionPolicy.displayVersionName,
        highlightedVersion = AppVersionPolicy.displayVersionName,
        highlightedLabel = stringResource(Res.string.changelog_installed),
        onDismiss = onDismiss,
        loading = state.loading,
        loadFailed = state.failed,
        onRetry = { retry++ },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReleaseNotesDialog(
    notes: String,
    title: String,
    fallbackTitle: String,
    highlightedVersion: String?,
    highlightedLabel: String,
    onDismiss: () -> Unit,
    releaseUrl: String? = null,
    loading: Boolean = false,
    loadFailed: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val versions = remember(notes, fallbackTitle, highlightedVersion) {
        parseReleaseNotes(notes, fallbackTitle, highlightedVersion)
    }
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 792.dp).fillMaxWidth().padding(16.dp),
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight * 0.9f),
                shape = tokens.shapes.dialog,
                color = tokens.colors.surfaceDialog,
                contentColor = tokens.colors.textPrimary,
                border = BorderStroke(tokens.borders.thin, tokens.colors.borderSubtle),
            ) {
                Column(Modifier.padding(tokens.spacing.dialogPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(Res.string.changelog_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, stringResource(Res.string.action_close))
                        }
                    }
                    HorizontalDivider(color = tokens.colors.borderSubtle)
                    when {
                        loading -> Text(stringResource(Res.string.changelog_loading))
                        loadFailed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.changelog_load_failed))
                            TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                        }
                        versions.isEmpty() -> Text(stringResource(Res.string.updates_no_release_notes))
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(versions) { index, version ->
                                ReleaseVersionCard(
                                    release = version,
                                    initiallyExpanded = index == 0,
                                    badge = highlightedLabel.takeIf {
                                        highlightedVersion != null && version.version?.removePrefix("v") == highlightedVersion.removePrefix("v")
                                    },
                                )
                            }
                        }
                    }
                    githubReleaseNotesUrl(releaseUrl)?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) {
                            Text(stringResource(Res.string.changelog_view_github))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseVersionCard(release: ReleaseNotesVersion, initiallyExpanded: Boolean, badge: String?) {
    var expanded by rememberSaveable(release) { mutableStateOf(initiallyExpanded) }
    val expandedDescription = stringResource(if (expanded) Res.string.changelog_expanded else Res.string.changelog_collapsed)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.nuvio.colors.borderSubtle),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expandedDescription }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(release.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (badge != null) Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(badge, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            if (expanded) SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    release.blocks.forEach { block ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (block.kind == ReleaseNotesBlock.Kind.Bullet) Text("•", color = MaterialTheme.colorScheme.primary)
                            val isHeading = block.kind == ReleaseNotesBlock.Kind.Heading
                            Text(
                                text = if (block.kind == ReleaseNotesBlock.Kind.Code) AnnotatedString(block.text)
                                    else releaseNotesAnnotatedText(block.text, MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f).then(if (isHeading) Modifier.semantics { heading() } else Modifier),
                                style = if (isHeading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace.takeIf { block.kind == ReleaseNotesBlock.Kind.Code },
                                color = if (isHeading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val inlineNotes = Regex("!?\\[([^\\]]+)\\]\\(([^\\s)]+)\\)|\\*\\*([^*]+)\\*\\*|`([^`]+)`|https?://[^\\s<>]+")

internal fun releaseNotesAnnotatedText(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    inlineNotes.findAll(text).forEach { token ->
        append(text.substring(cursor, token.range.first))
        val start = length
        when {
            token.value.startsWith("![") -> append(token.value) // Images remain text and never load remotely.
            token.value.startsWith("[") -> {
                append(token.groupValues[1])
                safeReleaseNotesUrl(token.groupValues[2])?.let { url ->
                    addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))), start, length)
                }
            }
            token.value.startsWith("**") -> {
                append(token.groupValues[3])
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
            }
            token.value.startsWith("`") -> {
                append(token.groupValues[4])
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, length)
            }
            else -> {
                append(token.value)
                safeReleaseNotesUrl(token.value)?.let { url ->
                    addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))), start, length)
                }
            }
        }
        cursor = token.range.last + 1
    }
    append(text.substring(cursor))
}
