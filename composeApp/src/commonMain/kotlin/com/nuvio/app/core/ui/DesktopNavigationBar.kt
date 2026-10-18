package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.jelly.JellyNavigationBar
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * Desktop top bar. With [leadingContent]/[trailingContent] it spans the window: brand on the
 * left, the tab group, then search/settings/profile on the right. It is clear over the hero
 * and turns solid with a hairline once the page scrolls. Without slots it renders only the
 * centered tab group (tablet floating bar, tests).
 */
@Composable
internal fun DesktopNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    contentPadding: PaddingValues = PaddingValues(top = 10.dp, bottom = 8.dp),
    navBarStyle: NavBarStyle = NavBarStyle.ADAPTIVE,
    isHeroEnabled: Boolean = false,
    profileSwitcherOpen: Boolean = false,
    windowWidth: Dp? = null,
    glowEnabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.nuvio.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scrollOffset = scrollState?.totalScrollOffset ?: 0f
    val isScrolledAwayFromTop = scrollOffset > 35f
    val spansWindow = leadingContent != null || trailingContent != null
    val labelFraction by animateFloatAsState(
        targetValue = desktopNavigationLabelFraction(navBarStyle),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_labels",
    )
    val solidness by animateFloatAsState(
        targetValue = if (spansWindow && isScrolledAwayFromTop) 1f else 0f,
        animationSpec = tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard),
        label = "desktop_nav_solid",
    )
    val groupFrosted = !spansWindow && (isHovered || profileSwitcherOpen || isScrolledAwayFromTop)
    val groupColor by animateColorAsState(
        targetValue = when {
            spansWindow -> colors.textPrimary.copy(alpha = 0.06f)
            groupFrosted -> Color(0xFF1C1C1E).copy(alpha = 0.30f)
            else -> Color(0xFF0F0F11).copy(alpha = 0.20f)
        },
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_surface",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (glowEnabled) 1f else 0f,
        animationSpec = tween(420, easing = NuvioTokens.Motion.standard),
        label = "desktop_nav_glow",
    )
    val background = colors.background
    val hairline = colors.borderSubtle
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (spansWindow) {
                    Modifier.drawBehind {
                        if (solidness < 1f) {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.55f * (1f - solidness)), Color.Transparent),
                                ),
                            )
                        }
                        if (solidness > 0f) {
                            drawRect(background.copy(alpha = 0.96f * solidness))
                            drawLine(
                                color = hairline.copy(alpha = hairline.alpha * solidness),
                                start = Offset(0f, size.height - 0.5f),
                                end = Offset(size.width, size.height - 0.5f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val viewportWidth = windowWidth ?: maxWidth
        val iconSize = when {
            viewportWidth < 800.dp -> 26.dp
            viewportWidth > 1600.dp -> 30.dp
            else -> 28.dp
        }
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
        val widestLabel = with(LocalDensity.current) {
            items.maxOf { textMeasurer.measure(AnnotatedString(it.label), labelStyle, softWrap = false).size.width }.toDp()
        }
        val compactItemWidth = iconSize + 20.dp
        val expandedItemWidth = iconSize + widestLabel + 32.dp
        val effectiveLabelFraction = if (maxWidth < expandedItemWidth * items.size + 8.dp) 0f else labelFraction
        val trackWidth = ((compactItemWidth + (expandedItemWidth - compactItemWidth) * effectiveLabelFraction) * items.size + 8.dp)
            .coerceAtMost(maxWidth)
        val tabs: @Composable () -> Unit = {
            JellyNavigationBar(
                items = items,
                labelFraction = effectiveLabelFraction,
                glowStrength = glowStrength,
                horizontalLabels = true,
                iconSize = iconSize,
                modifier = Modifier.width(trackWidth).height(iconSize + 20.dp).hoverable(interactionSource),
            ) {
                Box(
                    modifier = Modifier.matchParentSize()
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (groupFrosted && hazeState != null) {
                                Modifier.hazeEffect(state = hazeState) { blurRadius = 14.dp }
                            } else {
                                Modifier
                            },
                        )
                        .background(groupColor),
                )
            }
        }
        if (spansWindow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (desktopPageHorizontalPaddingForWidth(viewportWidth.value) - 16.dp).coerceAtLeast(0.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingContent?.invoke()
                Spacer(Modifier.width(28.dp))
                tabs()
                Spacer(Modifier.weight(1f))
                trailingContent?.invoke(this)
            }
        } else {
            tabs()
        }
    }
}

/** Desktop keeps tab labels visible unless the user picks the compact style. */
internal fun desktopNavigationLabelFraction(style: NavBarStyle): Float = when (style) {
    NavBarStyle.COMPACT -> 0f
    else -> 1f
}
