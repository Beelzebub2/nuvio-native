package com.nuvio.app.features.updater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
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
    primaryAction: UpdateAction? = null,
) {
    val versions = remember(notes, fallbackTitle, highlightedVersion) {
        parseReleaseNotes(notes, fallbackTitle, highlightedVersion)
    }
    val tokens = MaterialTheme.nuvio
    val colors = tokens.colors
    val uriHandler = LocalUriHandler.current
    val githubUrl = githubReleaseNotesUrl(releaseUrl)
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 792.dp).fillMaxWidth().padding(16.dp),
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val compact = maxWidth < 560.dp
            val gutter = if (compact) NuvioTokens.Space.s16 else NuvioTokens.Space.s24
            val headerWash = colors.accent.copy(alpha = 0.10f)
            Surface(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight * 0.9f),
                shape = tokens.shapes.dialog,
                color = colors.surfaceDialog,
                contentColor = colors.textPrimary,
                border = BorderStroke(tokens.borders.thin, colors.borderSubtle),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind { drawRect(Brush.verticalGradient(listOf(headerWash, headerWash.copy(alpha = 0f)))) }
                            .padding(start = gutter, end = gutter - 4.dp, top = gutter, bottom = NuvioTokens.Space.s16),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (compact) NuvioTokens.Space.s40 else NuvioTokens.Space.s48)
                                .clip(RoundedCornerShape(NuvioTokens.Radius.xl))
                                .background(colors.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(if (compact) 22.dp else 26.dp),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                            Text(
                                text = title,
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = stringResource(Res.string.changelog_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        NuvioIconActionButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.action_close),
                            onClick = onDismiss,
                            tone = NuvioCompactActionTone.Secondary,
                            size = NuvioActionSize.Small,
                        )
                    }
                    HorizontalDivider(color = colors.borderSubtle)
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        when {
                            loading -> ReleaseNotesStatus(text = stringResource(Res.string.changelog_loading), loading = true)
                            loadFailed -> ReleaseNotesStatus(
                                text = stringResource(Res.string.changelog_load_failed),
                                icon = Icons.Rounded.ErrorOutline,
                                iconTint = colors.danger,
                            ) {
                                NuvioCompactActionButton(
                                    label = stringResource(Res.string.action_retry),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = onRetry,
                                    tone = NuvioCompactActionTone.Secondary,
                                    size = NuvioActionSize.Small,
                                )
                            }
                            versions.isEmpty() -> ReleaseNotesStatus(text = stringResource(Res.string.updates_no_release_notes))
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = gutter, vertical = NuvioTokens.Space.s16),
                                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
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
                    }
                    if (githubUrl != null || primaryAction != null) {
                        HorizontalDivider(color = colors.borderSubtle)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = NuvioTokens.Space.s14),
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            githubUrl?.let { url ->
                                NuvioCompactActionButton(
                                    label = stringResource(Res.string.changelog_view_github),
                                    icon = null,
                                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { uriHandler.openUri(url) },
                                    tone = NuvioCompactActionTone.Ghost,
                                    size = NuvioActionSize.Small,
                                )
                            }
                            primaryAction?.let { action ->
                                NuvioCompactActionButton(
                                    label = action.label,
                                    icon = action.icon,
                                    onClick = action.onClick,
                                    enabled = action.enabled,
                                    tone = NuvioCompactActionTone.Primary,
                                    size = NuvioActionSize.Small,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesStatus(
    text: String,
    loading: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s24, vertical = NuvioTokens.Space.s32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        when {
            loading -> NuvioLoadingIndicator(size = NuvioTokens.Space.s32)
            icon != null -> Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** First readable line of a release, shown under a collapsed card's title. */
private fun ReleaseNotesVersion.previewText(): String? =
    blocks.firstOrNull { it.kind == ReleaseNotesBlock.Kind.Paragraph || it.kind == ReleaseNotesBlock.Kind.Bullet }
        ?.let { releaseNotesAnnotatedText(it.text, Color.Unspecified).text.replace('\n', ' ') }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseVersionCard(release: ReleaseNotesVersion, initiallyExpanded: Boolean, badge: String?) {
    var expanded by rememberSaveable(release) { mutableStateOf(initiallyExpanded) }
    val expandedDescription = stringResource(if (expanded) Res.string.changelog_expanded else Res.string.changelog_collapsed)
    val tokens = MaterialTheme.nuvio
    val colors = tokens.colors
    val highlighted = badge != null
    val preview = remember(release) { release.previewText() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(tokens.motion.normalMillis, easing = tokens.motion.standard),
        label = "changelogChevron",
    )
    val baseSurface = colors.surfaceDialog
    Surface(
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = (if (highlighted) colors.accent.copy(alpha = 0.07f) else colors.textPrimary.copy(alpha = 0.035f))
            .compositeOver(baseSurface),
        border = BorderStroke(
            tokens.borders.thin,
            if (highlighted) colors.accent.copy(alpha = 0.38f) else colors.borderDefault.copy(alpha = 0.8f),
        ),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expandedDescription }
                    .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            release.title,
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        if (badge != null) ReleaseBadge(badge)
                    }
                    AnimatedVisibility(
                        visible = !expanded && preview != null,
                        enter = fadeIn(tween(tokens.motion.fastMillis)) + expandVertically(),
                        exit = fadeOut(tween(tokens.motion.fastMillis)) + shrinkVertically(),
                    ) {
                        Text(
                            text = preview.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier.size(NuvioTokens.Space.s32).clip(CircleShape)
                        .background(colors.textPrimary.copy(alpha = if (expanded) 0.10f else 0.05f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(tokens.icons.md).rotate(chevronRotation),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(tokens.motion.normalMillis)) +
                    expandVertically(tween(tokens.motion.normalMillis, easing = tokens.motion.standard)),
                exit = fadeOut(tween(tokens.motion.fastMillis)) +
                    shrinkVertically(tween(tokens.motion.normalMillis, easing = tokens.motion.standard)),
            ) {
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        release.blocks.forEachIndexed { index, block -> ReleaseNotesBlockText(block, isLead = index == 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseBadge(text: String) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        shape = RoundedCornerShape(NuvioTokens.Radius.full),
        color = colors.accent,
        contentColor = colors.onAccent,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReleaseNotesBlockText(block: ReleaseNotesBlock, isLead: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    val linkColor = colors.accent
    when (block.kind) {
        ReleaseNotesBlock.Kind.Heading -> Text(
            text = block.text.uppercase(),
            modifier = Modifier.padding(top = if (isLead) 0.dp else NuvioTokens.Space.s6).semantics { heading() },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = colors.textMuted,
        )
        ReleaseNotesBlock.Kind.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            Box(
                Modifier.padding(top = 7.dp).size(NuvioTokens.Space.s6).clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.85f)),
            )
            Text(
                text = releaseNotesAnnotatedText(block.text, linkColor),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        ReleaseNotesBlock.Kind.Code -> Text(
            text = block.text,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(colors.textPrimary.copy(alpha = 0.05f)).padding(NuvioTokens.Space.s12),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.textSecondary,
        )
        ReleaseNotesBlock.Kind.Paragraph -> Text(
            text = releaseNotesAnnotatedText(block.text, linkColor),
            style = if (isLead) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (isLead) colors.textPrimary.copy(alpha = 0.92f) else colors.textSecondary,
        )
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
