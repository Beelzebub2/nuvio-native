package com.nuvio.app.features.streams

import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.NuvioTag
import com.nuvio.app.core.ui.NuvioTagStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.secondaryClickAt
import com.nuvio.app.core.ui.nuvioDesktopDragScroll
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.isDesktop

@Composable
internal fun StreamCard(
    stream: StreamItem,
    enabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onSecondaryClick: ((Offset) -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    currentLabel: String? = null,
    compact: Boolean = false,
    inlineMetadata: Boolean = false,
    /** Single-line preview for height-constrained desktop source panels. */
    dense: Boolean = false,
) {
    val cardShape = RoundedCornerShape(12.dp)
    val tokens = MaterialTheme.nuvio
    val badgeImages = stream.badges.filter { it.imageURL.isNotBlank() }
    val hasBadges = !dense && (badgeImages.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints.videoSize != null))
    var cardPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 44.dp else 68.dp)
            .clip(cardShape)
            .background(
                if (isCurrent) {
                    tokens.colors.accent.copy(alpha = tokens.opacity.subtle).compositeOver(tokens.colors.surface)
                } else {
                    tokens.colors.surface
                },
            )
            // The current (or best-match) source carries the selection outline; the rest a hairline.
            .border(
                width = tokens.borders.thin,
                color = if (isCurrent) tokens.colors.borderSelected else tokens.colors.borderSubtle,
                shape = cardShape,
            )
            .then(
                if (isDesktop) {
                    Modifier.onGloballyPositioned { coordinates ->
                        cardPositionInRoot = coordinates.positionInRoot()
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = if (isDesktop) null else onLongClick,
            )
            .secondaryClickAt(
                if (isDesktop && enabled && onSecondaryClick != null) {
                    { localPosition -> onSecondaryClick(cardPositionInRoot + localPosition) }
                } else {
                    null
                },
            )
            .padding(if (dense) 7.dp else if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            StreamNameWithInstantService(
                stream = stream,
                appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                compact = compact,
                inlineMetadata = inlineMetadata,
            ) {
                if (isCurrent && !currentLabel.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CurrentStreamBadge(label = currentLabel)
                }
            }

            val subtitle = stream.streamSubtitle
            if (!dense && !subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (compact || inlineMetadata) subtitle.lineSequence().joinToString(" · ") { it.trim() } else subtitle,
                    style = MaterialTheme.nuvioTypeScale.bodySm.copy(fontFeatureSettings = "tnum"),
                    color = tokens.colors.textMuted,
                    maxLines = if (compact) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                Spacer(modifier = Modifier.height(5.dp))
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
            }
        }

        if (showAddonLogo && !dense) {
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = if (compact || inlineMetadata) Modifier.widthIn(max = 96.dp) else Modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!stream.addonLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = stream.addonLogo,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stream.addonName,
                    style = MaterialTheme.nuvioTypeScale.labelXs.copy(fontWeight = FontWeight.Normal),
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StreamCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.nuvio.colors.surface)
            .border(
                MaterialTheme.nuvio.borders.thin,
                MaterialTheme.nuvio.colors.borderSubtle,
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f), height = 14.dp, cornerRadius = 4.dp)
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f), height = 10.dp, cornerRadius = 4.dp)
    }
}

@Composable
private fun StreamCardBadgeRow(
    badgeImages: List<StreamBadge>,
    stream: StreamItem,
    showFileSizeBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .nuvioDesktopDragScroll(scrollState)
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badgeImages.forEach { badge ->
            StreamBadgeImage(badge = badge)
        }
        if (showFileSizeBadges) {
            StreamFileSizeBadge(stream = stream)
        }
    }
}

@Composable
private fun StreamNameWithInstantService(
    stream: StreamItem,
    appendInstantServiceToDefaultName: Boolean,
    compact: Boolean,
    inlineMetadata: Boolean,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
    val instantLabel = if (appendInstantServiceToDefaultName) {
        stream.instantServiceLabel()
    } else {
        null
    }
    val showInstantLabel = instantLabel != null
    val visibleState = remember(stream.streamLabel) {
        MutableTransitionState(showInstantLabel)
    }
    visibleState.targetState = showInstantLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (compact || inlineMetadata) stream.streamLabel.lineSequence().joinToString(" · ") { it.trim() } else stream.streamLabel,
            modifier = Modifier.weight(1f, fill = false),
            style = nameStyle,
            color = MaterialTheme.nuvio.colors.textPrimary,
            maxLines = if (compact) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 260)) +
                expandHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                shrinkHorizontally(
                    animationSpec = tween(durationMillis = 120),
                    shrinkTowards = Alignment.Start,
                ),
            label = "streamNameInstantService",
        ) {
            Text(
                text = " ${instantLabel.orEmpty()}",
                style = nameStyle,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
        }
        trailingContent()
    }
}

@Composable
private fun CurrentStreamBadge(label: String) {
    NuvioTag(text = label, style = NuvioTagStyle.Accent)
}

private fun StreamItem.instantServiceLabel(): String? {
    val status = debridCacheStatus ?: return null
    if (status.state != StreamDebridCacheState.CACHED) return null
    val providerLabel = DebridProviders.shortName(status.providerId)
        .ifBlank { status.providerName.trim() }
        .ifBlank { DebridProviders.displayName(status.providerId) }
    return "- $providerLabel Instant"
}
