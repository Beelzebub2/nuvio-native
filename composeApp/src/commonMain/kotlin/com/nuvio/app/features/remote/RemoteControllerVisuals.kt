package com.nuvio.app.features.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

// --- Icons --------------------------------------------------------------------------------------

private class IconStroke(val d: String, val filled: Boolean = false, val stroked: Boolean = true, val width: Float? = null)

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}" +
        "h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

/** One 24 px line icon: 1.9 px round strokes, optionally filled where a glyph needs weight. */
private fun remoteIcon(name: String, strokeWidth: Float = 1.9f, vararg paths: IconStroke): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path.d).toNodes(),
                fill = if (path.filled) SolidColor(Color.Black) else null,
                stroke = if (path.stroked) SolidColor(Color.Black) else null,
                strokeLineWidth = path.width ?: strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** The controller's icon set, drawn on one 24 px grid so every control reads as one family. */
internal object RemoteIcons {
    val Back by lazy { remoteIcon("RemoteBack", 2f, IconStroke("M15 5l-7 7 7 7")) }
    val Wifi by lazy {
        remoteIcon(
            "RemoteWifi", 2f,
            IconStroke("M2.5 9a14 14 0 0 1 19 0"),
            IconStroke("M5.5 12.5a9.5 9.5 0 0 1 13 0"),
            IconStroke("M8.7 16a5 5 0 0 1 6.6 0"),
            IconStroke(circle(12f, 19.4f, 1.1f), filled = true, stroked = false),
        )
    }
    val Bluetooth by lazy { remoteIcon("RemoteBluetooth", 1.9f, IconStroke("M7 7.5l10 9-5 4.5V3l5 4.5-10 9")) }
    val Pc by lazy { remoteIcon("RemotePc", 1.9f, IconStroke(rect(2.5f, 4f, 19f, 13f, 2.5f)), IconStroke("M8.5 20.5h7M12 17v3.5")) }
    val Remote by lazy {
        remoteIcon(
            "RemoteHandset", 2f,
            IconStroke(rect(7f, 2.5f, 10f, 19f, 3f)),
            IconStroke(circle(12f, 8f, 2f)),
            IconStroke("M10 13.5h4M10 16.5h4"),
        )
    }
    val Download by lazy { remoteIcon("RemoteDownload", 2f, IconStroke("M12 3.5v11"), IconStroke("M7.5 10l4.5 4.5 4.5-4.5"), IconStroke("M4.5 19.5h15")) }
    val Play by lazy {
        remoteIcon(
            "RemotePlay", 0f,
            IconStroke("M7.5 5.6v12.8a1.1 1.1 0 0 0 1.68.93l10.1-6.4a1.1 1.1 0 0 0 0-1.86L9.18 4.67A1.1 1.1 0 0 0 7.5 5.6z", filled = true, stroked = false),
        )
    }
    val Pause by lazy {
        remoteIcon(
            "RemotePause", 0f,
            IconStroke(rect(6f, 5f, 4.2f, 14f, 1.3f), filled = true, stroked = false),
            IconStroke(rect(13.8f, 5f, 4.2f, 14f, 1.3f), filled = true, stroked = false),
        )
    }
    val Previous by lazy {
        remoteIcon(
            "RemotePrevious", 2f,
            IconStroke("M6 5v14"),
            IconStroke("M18 6.2v11.6a.8.8 0 0 1-1.25.66L9.4 12.66a.8.8 0 0 1 0-1.32l7.35-5.8A.8.8 0 0 1 18 6.2z", filled = true),
        )
    }
    val Next by lazy {
        remoteIcon(
            "RemoteNext", 2f,
            IconStroke("M18 5v14"),
            IconStroke("M6 6.2v11.6a.8.8 0 0 0 1.25.66l7.35-5.8a.8.8 0 0 0 0-1.32L7.25 5.54A.8.8 0 0 0 6 6.2z", filled = true),
        )
    }
    val RewindArc by lazy { remoteIcon("RemoteRewindArc", 1.8f, IconStroke("M3.5 12a8.5 8.5 0 1 0 2.5-6"), IconStroke("M3.5 3.5V8H8")) }
    val ForwardArc by lazy { remoteIcon("RemoteForwardArc", 1.8f, IconStroke("M20.5 12a8.5 8.5 0 1 1-2.5-6"), IconStroke("M20.5 3.5V8H16")) }
    val SkipIntro by lazy { remoteIcon("RemoteSkipIntro", 2.2f, IconStroke("M5 6l6 6-6 6M12 6l6 6-6 6")) }
    val SpeakerBody by lazy { remoteIcon("RemoteSpeaker", 1.9f, IconStroke("M4 9.5v5h3.5l4.5 4V5.5l-4.5 4H4z", filled = true)) }
    val SpeakerWave1 by lazy { remoteIcon("RemoteSpeakerWave1", 1.9f, IconStroke("M15.5 9.5a3.5 3.5 0 0 1 0 5")) }
    val SpeakerWave2 by lazy { remoteIcon("RemoteSpeakerWave2", 1.9f, IconStroke("M18 7a7 7 0 0 1 0 10")) }
    val SpeakerWave3 by lazy { remoteIcon("RemoteSpeakerWave3", 1.9f, IconStroke("M20.5 4.5a10.5 10.5 0 0 1 0 15")) }
    val SpeakerMute by lazy { remoteIcon("RemoteSpeakerMute", 1.9f, IconStroke("M16 9.5l5 5M21 9.5l-5 5")) }
    val Subtitles by lazy {
        remoteIcon(
            "RemoteSubtitles", 1.9f,
            IconStroke(rect(2.5f, 5f, 19f, 14f, 3f)),
            IconStroke("M10.6 10.2a2.2 2.2 0 1 0 0 3.6M17 10.2a2.2 2.2 0 1 0 0 3.6"),
        )
    }
    val Audio by lazy { remoteIcon("RemoteAudio", 2f, IconStroke("M4 10v4M8 7v10M12 4v16M16 8v8M20 10.5v3")) }
    val Episodes by lazy {
        remoteIcon(
            "RemoteEpisodes", 1.9f,
            IconStroke(rect(3f, 8f, 18f, 12f, 2.5f)),
            IconStroke("M6 5h12M8.5 2.5h7"),
            IconStroke("M10.5 11.5v5l4.2-2.5z", filled = true),
        )
    }
    val Source by lazy {
        remoteIcon(
            "RemoteSource", 1.9f,
            IconStroke(rect(3.5f, 4f, 17f, 7f, 2f)),
            IconStroke(rect(3.5f, 13f, 17f, 7f, 2f)),
            IconStroke("M7.5 7.5h.01M7.5 16.5h.01", width = 2.8f),
        )
    }
    val Fullscreen by lazy {
        remoteIcon(
            "RemoteFullscreen", 1.9f,
            IconStroke("M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15"),
        )
    }
    val Search by lazy { remoteIcon("RemoteSearch", 2f, IconStroke(circle(11f, 11f, 6.5f)), IconStroke("M16 16l4.5 4.5")) }
    val Qr by lazy {
        remoteIcon(
            "RemoteQr", 1.9f,
            IconStroke(rect(3.5f, 3.5f, 6.5f, 6.5f, 1.2f)),
            IconStroke(rect(14f, 3.5f, 6.5f, 6.5f, 1.2f)),
            IconStroke(rect(3.5f, 14f, 6.5f, 6.5f, 1.2f)),
            IconStroke("M14 14h2.5v2.5H14zM18 18h2.5v2.5H18zM14 20.5h1M20.5 14v1"),
        )
    }
    val Address by lazy {
        remoteIcon(
            "RemoteAddress", 1.9f,
            IconStroke(rect(2.5f, 6f, 19f, 12f, 2.5f)),
            IconStroke("M6.5 10h.01M10 10h.01M13.5 10h.01M17 10h.01M7.5 14h9", width = 2.2f),
        )
    }
    val Check by lazy { remoteIcon("RemoteCheck", 2.4f, IconStroke("M5 12.5l4.5 4.5L19 7.5")) }
    val Chevron by lazy { remoteIcon("RemoteChevron", 2.2f, IconStroke("M9.5 6l6 6-6 6")) }
    val Poster by lazy {
        remoteIcon(
            "RemotePoster", 1.5f,
            IconStroke(rect(3.5f, 3.5f, 17f, 17f, 2.5f)),
            IconStroke("M7.5 3.5v17M16.5 3.5v17M3.5 8h4M3.5 12h4M3.5 16h4M16.5 8h4M16.5 12h4M16.5 16h4"),
        )
    }

    /** The small play mark drawn inside [Pc]'s screen, on the same grid. */
    val PcScreenPlay by lazy { remoteIcon("RemotePcScreenPlay", 1f, IconStroke("M10.5 8.3v4.4l3.8-2.2z", filled = true)) }
}

// --- Motion helpers -----------------------------------------------------------------------------

/** Shrinks a control slightly while it is pressed and springs it back on release. */
internal fun Modifier.remotePressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.94f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(NuvioTokens.Motion.fastMillis, easing = NuvioTokens.Motion.standard),
        label = "remote_press_scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Fades content in while it rises a few pixels, once, after [delayMillis]. */
internal fun Modifier.remoteRiseIn(
    delayMillis: Int = 0,
    distance: Dp = 10.dp,
    durationMillis: Int = NuvioTokens.Motion.slowMillis,
): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(1f, tween(durationMillis, easing = NuvioTokens.Motion.standard))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * distance.toPx()
    }
}

/** A status dot that sends out a soft ring while [pulsing]. */
@Composable
internal fun RemoteLiveDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "remote_live_dot")
    val ping = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = NuvioTokens.Motion.decelerate)),
        label = "remote_live_dot_ping",
    )
    Box(
        modifier
            .size(size)
            .drawBehind {
                val radius = this.size.minDimension / 2f
                if (pulsing) {
                    val t = ping.value
                    drawCircle(color.copy(alpha = 0.6f * (1f - t)), radius = radius * (1f + 1.8f * t))
                }
                drawCircle(color, radius = radius)
            },
    )
}

/** Three small bars that bounce while the PC plays and rest while it is paused. */
@Composable
internal fun RemoteNowPlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "remote_now_playing")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "remote_now_playing_phase",
    )
    val resting = floatArrayOf(0.35f, 0.75f, 0.5f)
    Canvas(modifier.size(width = 13.dp, height = 12.dp)) {
        val barWidth = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val minHeight = 4.dp.toPx()
        val travel = size.height - minHeight
        for (index in 0..2) {
            val level = if (playing) 0.5f - 0.5f * cos(2f * PI.toFloat() * (phase.value + index / 3f)) else resting[index]
            val height = minHeight + travel * level
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

// --- Tabs ---------------------------------------------------------------------------------------

@Immutable
internal data class RemoteTabItem<T>(
    val key: T,
    val label: String,
    val icon: ImageVector,
    val badge: String? = null,
    val badgeDescription: String? = null,
)

/** The controller's tab bar: a highlight slides to the chosen tab. */
@Composable
internal fun <T> RemoteControllerTabs(
    tabs: List<RemoteTabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val outer = RoundedCornerShape(16.dp)
    val inner = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier
            .height(48.dp)
            .clip(outer)
            .background(tokens.colors.surface)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, outer)
            .padding(4.dp),
    ) {
        val count = tabs.size.coerceAtLeast(1)
        val tabWidth = maxWidth / count
        val index = tabs.indexOfFirst { it.key == selected }.coerceAtLeast(0)
        val offset by animateDpAsState(
            targetValue = tabWidth * index,
            animationSpec = tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard),
            label = "remote_tab_indicator",
        )
        // Icons make way for the label when a translation is long or the phone is narrow.
        val showIcons = tabWidth >= 112.dp
        Box(
            Modifier
                .offset(x = offset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(inner)
                .background(lerp(tokens.colors.surface, tokens.colors.textPrimary, 0.12f)),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEach { tab ->
                val isSelected = tab.key == selected
                val content by animateColorAsState(
                    targetValue = if (isSelected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    animationSpec = tween(NuvioTokens.Motion.normalMillis),
                    label = "remote_tab_content",
                )
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .selectable(
                            selected = isSelected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(tab.key) },
                        )
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showIcons) {
                        Box {
                            Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                            if (tab.badge != null) {
                                RemoteTabBadge(
                                    tab.badgeDescription,
                                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                                    ring = tokens.colors.surface,
                                )
                            }
                        }
                    }
                    Text(
                        tab.label,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!showIcons && tab.badge != null) RemoteTabBadge(tab.badgeDescription)
                }
            }
        }
    }
}

/**
 * An activity dot on a tab. A number would cover the small tab icon, so the count is only
 * announced to screen readers through [description].
 */
@Composable
private fun RemoteTabBadge(description: String?, modifier: Modifier = Modifier, ring: Color? = null) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier
            .clearAndSetSemantics { description?.let { contentDescription = it } }
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape).padding(2.dp) else Modifier)
            .size(8.dp)
            .clip(CircleShape)
            .background(tokens.colors.accent),
    )
}

// --- Transport ----------------------------------------------------------------------------------

/** A round icon button that shrinks when pressed; [pressRotation] turns the glyph as feedback. */
@Composable
internal fun RemoteRoundButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    container: Color = Color.Transparent,
    enabled: Boolean = true,
    pressRotation: Float = 0f,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotation by animateFloatAsState(
        targetValue = if (pressed) pressRotation else 0f,
        animationSpec = tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard),
        label = "remote_round_button_rotation",
    )
    Box(
        modifier
            .size(size)
            .remotePressScale(interaction)
            .clip(shape)
            .background(container)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else MaterialTheme.nuvio.opacity.disabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { rotationZ = rotation }, contentAlignment = Alignment.Center) { content() }
    }
}

/** Back or forward 10 s: a circular arrow with the step written inside it. */
@Composable
internal fun RemoteSeekTenIcon(forward: Boolean, tint: Color, size: Dp = 30.dp) {
    val density = LocalDensity.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            if (forward) RemoteIcons.ForwardArc else RemoteIcons.RewindArc,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
        Text(
            "10",
            modifier = Modifier.offset(x = if (forward) (-0.6).dp else 0.6.dp, y = 0.5.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = with(density) { (size * 0.34f).toSp() }),
            fontWeight = FontWeight.Bold,
            color = tint,
            maxLines = 1,
        )
    }
}

/** The main play/pause control; the new glyph pops in with a small turn. */
@Composable
internal fun RemotePlayPauseButton(
    playing: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val tokens = MaterialTheme.nuvio
    val glyph = size * (34f / 76f)
    RemoteRoundButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        container = tokens.colors.accent,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (fadeIn(tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard)) +
                    scaleIn(tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard), initialScale = 0.55f)) togetherWith
                    (fadeOut(tween(NuvioTokens.Motion.fastMillis)) + scaleOut(tween(NuvioTokens.Motion.fastMillis), targetScale = 0.55f))
            },
            label = "remote_play_pause",
        ) { isPlaying ->
            val turn by transition.animateFloat(label = "remote_play_pause_turn") { state ->
                if (state == EnterExitState.Visible) 0f else -25f
            }
            Icon(
                if (isPlaying) RemoteIcons.Pause else RemoteIcons.Play,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier
                    .size(glyph)
                    .offset(x = if (isPlaying) 0.dp else 2.dp)
                    .graphicsLayer { rotationZ = turn },
            )
        }
    }
}

/** Skip intro or credits. A soft highlight sweeps across it so the transient action gets noticed. */
@Composable
internal fun RemoteSkipPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val accent = tokens.colors.accent
    val interaction = remember { MutableInteractionSource() }
    val sweep = rememberInfiniteTransition(label = "remote_skip_sweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "remote_skip_sweep_x",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .remotePressScale(interaction, pressedScale = 0.97f)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.08f))
            .border(tokens.borders.thin, accent.copy(alpha = 0.45f), CircleShape)
            .drawBehind {
                val band = size.width * 0.35f
                val start = sweep.value * size.width - band / 2f
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.16f), Color.Transparent),
                        startX = start,
                        endX = start + band,
                    ),
                )
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RemoteIcons.SkipIntro, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- Volume -------------------------------------------------------------------------------------

/** A speaker whose waves follow the level: none when muted, a third above 100 %. */
@Composable
internal fun RemoteSpeakerIcon(level: Float, tint: Color, modifier: Modifier = Modifier) {
    val muted = level <= 0f
    val fade = tween<Float>(NuvioTokens.Motion.fastMillis)
    val one by animateFloatAsState(if (!muted) 1f else 0f, fade, label = "remote_speaker_wave_1")
    val two by animateFloatAsState(if (level >= 0.5f) 1f else 0f, fade, label = "remote_speaker_wave_2")
    val three by animateFloatAsState(if (level > 1.005f) 1f else 0f, fade, label = "remote_speaker_wave_3")
    val cross by animateFloatAsState(if (muted) 1f else 0f, fade, label = "remote_speaker_mute")
    Box(modifier.size(24.dp)) {
        Icon(RemoteIcons.SpeakerBody, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
        Icon(RemoteIcons.SpeakerWave1, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(one))
        Icon(RemoteIcons.SpeakerWave2, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(two))
        Icon(RemoteIcons.SpeakerWave3, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(three))
        Icon(RemoteIcons.SpeakerMute, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(cross))
    }
}

/**
 * The volume control: the whole pill is the slider. A marker shows 100 % when the PC allows boost,
 * and the fill takes the accent colour above it.
 */
@Composable
internal fun RemoteVolumePill(
    value: Float,
    max: Float,
    label: String,
    boostLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueModifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(16.dp)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)
    val currentMax by rememberUpdatedState(max)
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val clamped = value.coerceIn(0f, max)
    val fraction = if (max > 0f) clamped / max else 0f
    val shownFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging) snap() else tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard),
        label = "remote_volume_fill",
    )
    val boost = clamped > 1.005f
    val fill by animateColorAsState(
        targetValue = if (boost) tokens.colors.accent.copy(alpha = 0.30f) else tokens.colors.textPrimary.copy(alpha = 0.14f),
        animationSpec = tween(NuvioTokens.Motion.normalMillis),
        label = "remote_volume_fill_color",
    )
    val marker = tokens.colors.textPrimary.copy(alpha = 0.22f)
    fun valueAt(x: Float): Float = if (widthPx > 0) (x / widthPx).coerceIn(0f, 1f) * currentMax else clamped
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(tokens.colors.textPrimary.copy(alpha = 0.06f))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentChange(valueAt(offset.x))
                    currentFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        currentChange(valueAt(offset.x))
                    },
                    onDragEnd = { dragging = false; currentFinished() },
                    onDragCancel = { dragging = false; currentFinished() },
                ) { change, _ ->
                    change.consume()
                    currentChange(valueAt(change.position.x))
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..max)
                setProgress { target ->
                    currentChange(target.coerceIn(0f, currentMax))
                    currentFinished()
                    true
                }
            }
            .drawBehind {
                drawRect(fill, size = Size(size.width * shownFraction, size.height))
                if (max > 1f) {
                    val x = size.width / max
                    val inset = 15.dp.toPx()
                    drawRoundRect(
                        marker,
                        topLeft = Offset(x - 1.dp.toPx(), inset),
                        size = Size(2.dp.toPx(), size.height - inset * 2f),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(
                visible = boost,
                enter = fadeIn(tween(NuvioTokens.Motion.normalMillis)) + scaleIn(initialScale = 0.7f),
                exit = fadeOut(tween(NuvioTokens.Motion.fastMillis)) + scaleOut(targetScale = 0.7f),
            ) {
                Text(
                    boostLabel.uppercase(),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(tokens.colors.accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.nuvioTypeScale.labelXs,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.onAccent,
                    maxLines = 1,
                )
            }
            Text(
                "${(clamped * 100).roundToInt()}%",
                modifier = valueModifier.widthIn(min = 44.dp),
                style = MaterialTheme.typography.labelLarge.tabular,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

// --- Cards and tiles ----------------------------------------------------------------------------

/** A playback option tile: icon, name and the current choice. */
@Composable
internal fun RemoteOptionTile(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .remotePressScale(interaction, pressedScale = 0.97f)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RemoteIconBox(icon)
            Spacer(Modifier.weight(1f))
            Icon(RemoteIcons.Chevron, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The 34 dp rounded square behind a tile or row icon. */
@Composable
internal fun RemoteIconBox(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.nuvio.colors.textPrimary) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Nothing playing: the PC breathes softly while the controller waits for a video. */
@Composable
internal fun RemoteIdleIllustration(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val ring = tokens.colors.textPrimary.copy(alpha = 0.16f)
    val transition = rememberInfiniteTransition(label = "remote_idle")
    val breath = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing)),
        label = "remote_idle_breath",
    )
    val glow = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing)),
        label = "remote_idle_glow",
    )
    Box(modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val base = size.minDimension / 2f
            for (offset in floatArrayOf(0f, 0.5f)) {
                val p = (breath.value + offset) % 1f
                val eased = 1f - (1f - p) * (1f - p)
                drawCircle(
                    ring.copy(alpha = ring.alpha * (1f - p)),
                    radius = base * (1f + 0.7f * eased),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(tokens.colors.textPrimary.copy(alpha = 0.05f))
                .border(tokens.borders.thin, tokens.colors.borderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RemoteIcons.Pc, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(52.dp))
            Icon(
                RemoteIcons.PcScreenPlay,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer { alpha = 0.45f + 0.55f * (0.5f - 0.5f * cos(2f * PI.toFloat() * glow.value)) },
            )
        }
    }
}

/** Searching for PCs: rings travel outwards from the PC and a dashed orbit turns slowly. */
@Composable
internal fun RemoteSearchRadar(searching: Boolean, connected: Boolean, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val waveColor = if (connected) tokens.colors.success else tokens.colors.accent
    val orbit = tokens.colors.textPrimary.copy(alpha = 0.14f)
    val transition = rememberInfiniteTransition(label = "remote_radar")
    val wave = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "remote_radar_wave",
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "remote_radar_orbit",
    )
    val waves by animateFloatAsState(
        targetValue = if (searching) 1f else 0f,
        animationSpec = tween(NuvioTokens.Motion.slowMillis),
        label = "remote_radar_strength",
    )
    Box(modifier.size(108.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val core = 32.dp.toPx()
            if (waves > 0f) {
                for (index in 0..2) {
                    val p = (wave.value + index / 3f) % 1f
                    val eased = 1f - (1f - p) * (1f - p)
                    drawCircle(
                        waveColor.copy(alpha = 0.55f * (1f - p) * waves),
                        radius = core * (1f + 0.75f * eased),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            rotate(if (searching) spin.value else 0f) {
                val dash = 6.dp.toPx()
                drawCircle(
                    orbit,
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
        }
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(tokens.colors.surfaceCard)
                .border(tokens.borders.thin, tokens.colors.borderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RemoteIcons.Pc,
                contentDescription = null,
                tint = if (connected) tokens.colors.success else tokens.colors.textPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** A download bar; while active, stripes move along the filled part. */
@Composable
internal fun RemoteDownloadProgress(progress: Float, active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(3.dp)
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(NuvioTokens.Motion.slowMillis, easing = NuvioTokens.Motion.standard),
        label = "remote_download_progress",
    )
    val stripes = rememberInfiniteTransition(label = "remote_download_stripes").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "remote_download_stripes_x",
    )
    val stripe = Color.Black.copy(alpha = 0.22f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(tokens.colors.textPrimary.copy(alpha = 0.08f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .clip(shape)
                .background(color)
                .drawBehind {
                    if (active) {
                        val period = 12.dp.toPx()
                        val shift = stripes.value * period * 2f
                        drawRect(
                            Brush.linearGradient(
                                0f to stripe, 0.5f to stripe, 0.5f to Color.Transparent, 1f to Color.Transparent,
                                start = Offset(shift, 0f),
                                end = Offset(shift + period, period),
                                tileMode = TileMode.Repeated,
                            ),
                        )
                    }
                },
        )
    }
}

/** A radio mark for choice sheets: the dot pops in and its check draws itself. */
@Composable
internal fun RemoteChoiceMark(selected: Boolean, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (!selected) {
            Box(Modifier.size(22.dp).border(2.dp, tokens.colors.textMuted.copy(alpha = 0.6f), CircleShape))
        }
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard), initialScale = 0.4f) +
                fadeIn(tween(NuvioTokens.Motion.fastMillis)),
            exit = fadeOut(tween(NuvioTokens.Motion.fastMillis)),
        ) {
            val draw = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(80)
                draw.animateTo(1f, tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard))
            }
            val ink = tokens.colors.onAccent
            Canvas(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent),
            ) {
                val scale = size.minDimension / 24f
                val check = Path().apply {
                    moveTo(7f * scale, 12.5f * scale)
                    lineTo(10.5f * scale, 16f * scale)
                    lineTo(17f * scale, 8.5f * scale)
                }
                val measure = PathMeasure().apply { setPath(check, false) }
                val partial = Path()
                measure.getSegment(0f, measure.length * draw.value, partial, true)
                drawPath(partial, ink, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
