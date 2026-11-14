package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nuvio.app.LocalTelevisionUi
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Height of the phone seek row, gap and control row, used to keep prompts above the bar. */
internal val PlayerModernControlsHeight = 86.dp

private val ScrubRowHeight = 24.dp
private val ActionRowHeight = 56.dp
private val ControlForeground = Color.White.copy(alpha = 0.92f)
private val PopoverSurface = Color(0xF2111217)
private val PopoverBorder = Color.White.copy(alpha = 0.08f)
private val TitleShadow = Shadow(color = Color.Black.copy(alpha = 0.45f), offset = Offset(0f, 1f), blurRadius = 10f)
private val TimeShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

private enum class PlayerControlMenu { More, PlaybackOptions }

/**
 * The phone layout of the desktop control redesign: back and title top left, tools top right,
 * a chapter-aware seek row, and one control bar whose overflow folds into More.
 */
@Composable
internal fun BoxScope.PlayerModernControlsLayer(
    title: String,
    episodeLine: String?,
    sourceLine: String?,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    isLocked: Boolean,
    showControls: Boolean,
    showMetadata: Boolean,
    showRemainingTime: Boolean,
    skipSegments: List<PlayerControlSkipSegment>,
    subtitlesActive: Boolean,
    shortSeekSeconds: Int,
    toggles: List<PlayerControlToggle>,
    parentalWarnings: List<ParentalWarning>,
    showParentalGuide: Boolean,
    onParentalGuideAnimationComplete: () -> Unit,
    onInteraction: () -> Unit,
    onRuntimeClick: () -> Unit,
    onLockToggle: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onNextEpisodeClick: (() -> Unit)?,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onSpeedClick: () -> Unit,
    onResizeModeClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
    onSubmitIntroClick: (() -> Unit)?,
    onHandoffClick: (() -> Unit)?,
    onDiagnosticsClick: (() -> Unit)?,
    onCastInfoClick: (() -> Unit)?,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onControlsFocusChanged: (Boolean) -> Unit = {},
) {
    var openMenu by remember { mutableStateOf<PlayerControlMenu?>(null) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(showControls, isLocked) {
        if (!showControls || isLocked) openMenu = null
    }

    // Menus keep the controls awake and close on back before the player does.
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    LaunchedEffect(openMenu) {
        while (openMenu != null) {
            currentOnInteraction()
            delay(1_500)
        }
    }
    // Remotes: play takes focus when the bar appears, a menu takes it when it opens and
    // hands it back to the button that opened it when it closes.
    val isTelevision = LocalTelevisionUi.current
    val playFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    fun closeMenu() {
        val menu = openMenu ?: return
        openMenu = null
        if (!isTelevision) return
        val trigger = if (menu == PlayerControlMenu.PlaybackOptions) optionsFocus else moreFocus
        if (runCatching { trigger.requestFocus() }.isFailure) runCatching { moreFocus.requestFocus() }
    }
    LaunchedEffect(showControls, isTelevision) {
        if (isTelevision && showControls) {
            delay(32)
            runCatching { playFocus.requestFocus() }
        }
    }
    LaunchedEffect(openMenu, isTelevision) {
        if (isTelevision && openMenu != null) {
            delay(32)
            runCatching { menuFocus.requestFocus() }
        }
    }
    PlatformBackHandler(enabled = openMenu != null) { closeMenu() }

    CompositionLocalProvider(LocalPlayerFocusInteraction provides { currentOnInteraction() }) {
    BoxWithConstraints(Modifier.fillMaxSize().onFocusChanged { onControlsFocusChanged(it.hasFocus) }) {
        val barWidth = maxWidth - metrics.horizontalPadding * 2
        val hasPlaybackOptions = toggles.isNotEmpty() || onVideoSettingsClick != null
        val availableItems = buildSet {
            if (onNextEpisodeClick != null) add(PlayerBarItem.NextEpisode)
            if (onEpisodesClick != null) add(PlayerBarItem.Episodes)
            if (onSourcesClick != null) add(PlayerBarItem.Sources)
            add(PlayerBarItem.Subtitles)
            add(PlayerBarItem.Audio)
            add(PlayerBarItem.Speed)
            add(PlayerBarItem.Aspect)
            if (hasPlaybackOptions) add(PlayerBarItem.PlaybackOptions)
        }
        val plan = remember(barWidth, availableItems) { planPlayerBar(barWidth.value, availableItems) }
        val closeMenuThen: (() -> Unit) -> () -> Unit = { action ->
            {
                closeMenu()
                onInteraction()
                action()
            }
        }

        if (showControls) {
            PlayerModernHeader(
                title = title,
                episodeLine = episodeLine,
                sourceLine = sourceLine,
                sourceVisible = showMetadata && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading,
                qualityLabel = playerVideoQualityLabel(playbackSnapshot.videoWidth, playbackSnapshot.videoHeight)
                    .takeUnless { plan.compact },
                metadataVisible = showMetadata,
                metrics = metrics,
                isLocked = isLocked,
                // A remote cannot brush the screen, so TV has no lock.
                onLockToggle = if (isTelevision) null else closeMenuThen(onLockToggle),
                onBack = onBack,
                onSubmitIntroClick = onSubmitIntroClick?.takeUnless { plan.compact }?.let(closeMenuThen),
                onDiagnosticsClick = onDiagnosticsClick?.takeUnless { plan.compact }?.let(closeMenuThen),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                    .padding(horizontal = metrics.horizontalPadding)
                    .padding(top = metrics.sliderBottomOffset)
                    .onSizeChanged { headerHeightPx = it.height },
            )
        }

        ParentalGuideOverlay(
            warnings = parentalWarnings,
            isVisible = showParentalGuide,
            onAnimationComplete = onParentalGuideAnimationComplete,
            contentPadding = PaddingValues(horizontal = metrics.horizontalPadding, vertical = metrics.verticalPadding),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = if (showControls) with(density) { headerHeightPx.toDp() } else 0.dp),
        )

        if (showControls && playbackSnapshot.isLoading) {
            NuvioLoadingIndicator(modifier = Modifier.align(Alignment.Center), size = 48.dp)
        }

        if (showControls) {
            PlayerModernBottomControls(
                playbackSnapshot = playbackSnapshot,
                displayedPositionMs = displayedPositionMs,
                plan = plan,
                resizeMode = resizeMode,
                showRemainingTime = showRemainingTime,
                skipSegments = skipSegments,
                subtitlesActive = subtitlesActive,
                shortSeekSeconds = shortSeekSeconds,
                toggles = toggles,
                openMenu = openMenu,
                playFocus = playFocus,
                moreFocus = moreFocus,
                optionsFocus = optionsFocus,
                onRuntimeClick = {
                    onRuntimeClick()
                    onInteraction()
                },
                onTogglePlayback = closeMenuThen(onTogglePlayback),
                onSeekBack = closeMenuThen(onSeekBack),
                onSeekForward = closeMenuThen(onSeekForward),
                onNextEpisodeClick = onNextEpisodeClick?.let(closeMenuThen),
                onSubtitleClick = closeMenuThen(onSubtitleClick),
                onAudioClick = closeMenuThen(onAudioClick),
                onSourcesClick = onSourcesClick?.let(closeMenuThen),
                onEpisodesClick = onEpisodesClick?.let(closeMenuThen),
                onSpeedClick = closeMenuThen(onSpeedClick),
                onResizeModeClick = closeMenuThen(onResizeModeClick),
                onPlaybackOptionsClick = {
                    if (openMenu == PlayerControlMenu.PlaybackOptions) closeMenu() else openMenu = PlayerControlMenu.PlaybackOptions
                    onInteraction()
                },
                onMoreClick = {
                    if (openMenu == PlayerControlMenu.More) closeMenu() else openMenu = PlayerControlMenu.More
                    onInteraction()
                },
                onScrubChange = onScrubChange,
                onScrubFinished = {
                    onInteraction()
                    onScrubFinished(it)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(playerTimelineBottomInsets(metrics))
                    .padding(horizontal = metrics.horizontalPadding),
            )
        }

        val menu = openMenu
        if (showControls && menu != null) {
            // A tap anywhere outside the menu closes it, like the desktop popovers.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { closeMenu() } },
            )
            val bottomInset = playerTimelineBottomInsets(metrics).asPaddingValues().calculateBottomPadding()
            val topInset = WindowInsets.safeContent.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
            val menuBottom = bottomInset + PlayerModernControlsHeight + 14.dp
            val menuMaxHeight = (maxHeight - menuBottom - topInset - metrics.sliderBottomOffset).coerceAtLeast(120.dp)
            PlayerControlPopover(
                width = if (menu == PlayerControlMenu.More) 300.dp else 344.dp,
                maxWidth = barWidth,
                maxHeight = menuMaxHeight,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = metrics.horizontalPadding, bottom = menuBottom),
            ) {
                when (menu) {
                    PlayerControlMenu.More -> PlayerMoreMenuContent(
                        plan = plan,
                        playbackSpeed = playbackSnapshot.playbackSpeed,
                        resizeMode = resizeMode,
                        subtitlesActive = subtitlesActive,
                        hasPlaybackOptions = hasPlaybackOptions,
                        enabledToggleCount = toggles.count { it.checked },
                        firstItemFocus = menuFocus,
                        onNextEpisodeClick = onNextEpisodeClick?.let(closeMenuThen),
                        onEpisodesClick = onEpisodesClick?.let(closeMenuThen),
                        onSourcesClick = onSourcesClick?.let(closeMenuThen),
                        onSubtitleClick = closeMenuThen(onSubtitleClick),
                        onAudioClick = closeMenuThen(onAudioClick),
                        onSpeedClick = closeMenuThen(onSpeedClick),
                        onResizeModeClick = closeMenuThen(onResizeModeClick),
                        onPlaybackOptionsClick = {
                            openMenu = PlayerControlMenu.PlaybackOptions
                            onInteraction()
                        },
                        onOpenInExternalPlayer = onOpenInExternalPlayer?.let(closeMenuThen),
                        onHandoffClick = onHandoffClick?.let(closeMenuThen),
                        onCastInfoClick = onCastInfoClick?.let(closeMenuThen),
                        onSubmitIntroClick = onSubmitIntroClick?.takeIf { plan.compact }?.let(closeMenuThen),
                        onDiagnosticsClick = onDiagnosticsClick?.takeIf { plan.compact }?.let(closeMenuThen),
                    )
                    PlayerControlMenu.PlaybackOptions -> PlayerPlaybackOptionsContent(
                        toggles = toggles,
                        firstItemFocus = menuFocus,
                        onInteraction = onInteraction,
                        onVideoSettingsClick = onVideoSettingsClick?.let(closeMenuThen),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun PlayerModernHeader(
    title: String,
    episodeLine: String?,
    sourceLine: String?,
    sourceVisible: Boolean,
    qualityLabel: String?,
    metadataVisible: Boolean,
    metrics: PlayerLayoutMetrics,
    isLocked: Boolean,
    onLockToggle: (() -> Unit)?,
    onBack: () -> Unit,
    onSubmitIntroClick: (() -> Unit)?,
    onDiagnosticsClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    val metadataAlpha by animateFloatAsState(
        targetValue = if (metadataVisible) 1f else 0f,
        animationSpec = tween(durationMillis = if (metadataVisible) 260 else 160),
        label = "playerHeaderMetadataAlpha",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerBackChip(onClick = onBack)
            Column(Modifier.weight(1f).graphicsLayer { alpha = metadataAlpha }) {
                Text(
                    text = title,
                    style = typeScale.titleLg.copy(
                        fontSize = metrics.titleSize,
                        lineHeight = metrics.titleSize * 1.16f,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.01).em,
                        shadow = TitleShadow,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!episodeLine.isNullOrBlank() || qualityLabel != null) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!episodeLine.isNullOrBlank()) {
                            Text(
                                text = episodeLine,
                                style = typeScale.bodyMd.copy(
                                    fontSize = metrics.episodeInfoSize,
                                    lineHeight = metrics.episodeInfoSize * 1.3f,
                                ),
                                color = Color.White.copy(alpha = 0.82f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (qualityLabel != null) PlayerStatusChip(qualityLabel)
                    }
                }
                AnimatedVisibility(
                    visible = sourceVisible && !sourceLine.isNullOrBlank(),
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
                ) {
                    Text(
                        text = sourceLine.orEmpty(),
                        style = typeScale.labelSm.copy(
                            fontSize = metrics.metadataSize,
                            lineHeight = metrics.metadataSize * 1.25f,
                            fontStyle = FontStyle.Italic,
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (onSubmitIntroClick != null || onDiagnosticsClick != null || onLockToggle != null) Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF08090C).copy(alpha = 0.38f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSubmitIntroClick != null) {
                PlayerHeaderToolButton(
                    icon = PlayerControlIcons.Flag,
                    description = stringResource(Res.string.submit_intro_action),
                    onClick = onSubmitIntroClick,
                )
            }
            if (onDiagnosticsClick != null) {
                PlayerHeaderToolButton(
                    icon = PlayerControlIcons.Diagnostics,
                    description = stringResource(Res.string.player_diagnostics_title),
                    onClick = onDiagnosticsClick,
                )
            }
            if (onLockToggle != null) {
                PlayerHeaderToolButton(
                    icon = if (isLocked) PlayerControlIcons.LockOpen else PlayerControlIcons.Lock,
                    description = stringResource(
                        if (isLocked) Res.string.compose_player_unlock_controls else Res.string.compose_player_lock_controls,
                    ),
                    onClick = onLockToggle,
                )
            }
        }
    }
}

@Composable
private fun PlayerBackChip(onClick: () -> Unit) {
    val description = stringResource(Res.string.compose_player_close)
    PressScaleBox(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier
            .size(PlayerBarButtonDp.dp)
            .background(Color.White.copy(alpha = 0.1f), CircleShape)
            .semantics { contentDescription = description },
    ) {
        Icon(PlayerControlIcons.Back, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PlayerHeaderToolButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    PressScaleBox(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(PlayerBarButtonDp.dp),
    ) {
        Icon(icon, contentDescription = description, tint = ControlForeground, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun PlayerStatusChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.nuvioTypeScale.labelSm.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.88f),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun PlayerModernBottomControls(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    plan: PlayerBarPlan,
    resizeMode: PlayerResizeMode,
    showRemainingTime: Boolean,
    skipSegments: List<PlayerControlSkipSegment>,
    subtitlesActive: Boolean,
    shortSeekSeconds: Int,
    toggles: List<PlayerControlToggle>,
    openMenu: PlayerControlMenu?,
    playFocus: FocusRequester,
    moreFocus: FocusRequester,
    optionsFocus: FocusRequester,
    onRuntimeClick: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onNextEpisodeClick: (() -> Unit)?,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onSpeedClick: () -> Unit,
    onResizeModeClick: () -> Unit,
    onPlaybackOptionsClick: () -> Unit,
    onMoreClick: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Transport stays left to right in every language, like the seek bar it sits under.
    // Remote navigation is explicit: Up from any bar button reaches the seek bar and Down
    // from it returns to play (the bar's touch area overlaps the buttons, which can tip
    // geometric focus search elsewhere).
    val isTelevision = LocalTelevisionUi.current
    val seekFocus = remember { FocusRequester() }
    val seekAvailable = playbackSnapshot.durationMs > 0L
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerScrubRow(
                playbackSnapshot = playbackSnapshot,
                displayedPositionMs = displayedPositionMs,
                showRemainingTime = showRemainingTime,
                skipSegments = skipSegments,
                onRuntimeClick = onRuntimeClick,
                onScrubChange = onScrubChange,
                onScrubFinished = onScrubFinished,
                seekFocusModifier = Modifier.focusRequester(seekFocus).focusProperties { down = playFocus },
                timeFocusable = !isTelevision,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ActionRowHeight)
                    .focusProperties { if (seekAvailable) up = seekFocus },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerPlayButton(
                        isPlaying = playbackSnapshot.isPlaying,
                        compact = plan.compact,
                        onClick = onTogglePlayback,
                        focusRequester = playFocus,
                    )
                    Spacer(Modifier.width(if (plan.compact) 0.dp else 4.dp))
                    PlayerSeekButton(
                        icon = PlayerControlIcons.Replay,
                        seconds = shortSeekSeconds,
                        description = stringResource(Res.string.compose_player_seek_back_seconds, shortSeekSeconds),
                        onClick = onSeekBack,
                    )
                    PlayerSeekButton(
                        icon = PlayerControlIcons.Forward,
                        seconds = shortSeekSeconds,
                        description = stringResource(Res.string.compose_player_seek_forward_seconds, shortSeekSeconds),
                        onClick = onSeekForward,
                    )
                    if (onNextEpisodeClick != null && plan.isInline(PlayerBarItem.NextEpisode)) {
                        PlayerBarButton(
                            icon = PlayerControlIcons.SkipNext,
                            description = stringResource(Res.string.player_next_episode),
                            onClick = onNextEpisodeClick,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val showEpisodes = onEpisodesClick != null && plan.isInline(PlayerBarItem.Episodes)
                    val showSources = onSourcesClick != null && plan.isInline(PlayerBarItem.Sources)
                    if (showEpisodes) {
                        PlayerBarButton(PlayerControlIcons.Episodes, stringResource(Res.string.compose_player_episodes), onEpisodesClick!!)
                    }
                    if (showSources) {
                        PlayerBarButton(PlayerControlIcons.Source, stringResource(Res.string.compose_player_sources), onSourcesClick!!)
                    }
                    val trackItems = listOf(PlayerBarItem.Subtitles, PlayerBarItem.Audio, PlayerBarItem.Speed, PlayerBarItem.Aspect)
                        .filter(plan::isInline)
                    if ((showEpisodes || showSources) && trackItems.isNotEmpty()) PlayerBarDivider()
                    trackItems.forEach { item ->
                        when (item) {
                            PlayerBarItem.Subtitles -> PlayerBarButton(
                                icon = if (subtitlesActive) PlayerControlIcons.Subtitles else PlayerControlIcons.SubtitlesOff,
                                description = stringResource(Res.string.compose_player_subtitles),
                                onClick = onSubtitleClick,
                            )
                            PlayerBarItem.Audio -> PlayerBarButton(
                                PlayerControlIcons.Audio,
                                stringResource(Res.string.compose_player_audio),
                                onAudioClick,
                            )
                            PlayerBarItem.Speed -> PlayerBarButton(
                                icon = PlayerControlIcons.Speed,
                                description = "${stringResource(Res.string.compose_player_speed)} " +
                                    formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed),
                                onClick = onSpeedClick,
                                badge = playerSpeedBadge(playbackSnapshot.playbackSpeed),
                                badgeIsSpeed = true,
                            )
                            PlayerBarItem.Aspect -> PlayerBarButton(
                                PlayerControlIcons.Aspect,
                                "${stringResource(Res.string.player_aspect)} ${stringResource(resizeMode.labelRes)}",
                                onResizeModeClick,
                            )
                            else -> Unit
                        }
                    }
                    if (trackItems.isNotEmpty() || showEpisodes || showSources) PlayerBarDivider()
                    if (plan.isInline(PlayerBarItem.PlaybackOptions)) {
                        val enabledCount = toggles.count { it.checked }
                        PlayerBarButton(
                            icon = PlayerControlIcons.Options,
                            description = stringResource(Res.string.player_playback_options),
                            onClick = onPlaybackOptionsClick,
                            badge = enabledCount.takeIf { it > 0 }?.toString(),
                            selected = openMenu == PlayerControlMenu.PlaybackOptions,
                            focusRequester = optionsFocus,
                        )
                    }
                    PlayerBarButton(
                        icon = PlayerControlIcons.More,
                        description = stringResource(Res.string.compose_player_more_actions),
                        onClick = onMoreClick,
                        selected = openMenu == PlayerControlMenu.More,
                        focusRequester = moreFocus,
                    )
                }
            }
        }
    }
}

private fun playerSpeedBadge(speed: Float): String? {
    if (speed == 1f) return null
    return formatPlaybackSpeedLabel(speed).removeSuffix("x") + "×"
}

@Composable
private fun PlayerScrubRow(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    showRemainingTime: Boolean,
    skipSegments: List<PlayerControlSkipSegment>,
    onRuntimeClick: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    enabled: Boolean = true,
    seekFocusModifier: Modifier = Modifier,
    timeFocusable: Boolean = true,
) {
    val durationMs = playbackSnapshot.durationMs.coerceAtLeast(0L)
    val timeStyle = MaterialTheme.nuvioTypeScale.labelSm.copy(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        shadow = TimeShadow,
        fontFeatureSettings = "tnum",
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(ScrubRowHeight),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPlaybackTime(displayedPositionMs.coerceAtLeast(0L)),
            style = timeStyle,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        PlayerChapterTimeline(
            durationMs = durationMs,
            displayedPositionMs = displayedPositionMs,
            bufferedPositionMs = playbackSnapshot.bufferedPositionMs,
            skipSegments = skipSegments,
            onScrubChange = onScrubChange,
            onScrubFinished = onScrubFinished,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            sliderModifier = seekFocusModifier,
        )
        val runtimeLabel = stringResource(
            if (showRemainingTime) Res.string.compose_player_show_elapsed_time else Res.string.compose_player_show_remaining_time,
        )
        Box(
            modifier = Modifier
                .widthIn(min = 56.dp)
                .clip(RoundedCornerShape(6.dp))
                // A touch shortcut; a remote skips it so Up from the bar lands on the seek bar.
                .focusProperties { canFocus = timeFocusable }
                .then(
                    if (enabled) {
                        Modifier.clickable(role = Role.Button, onClickLabel = runtimeLabel, onClick = onRuntimeClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = if (showRemainingTime) {
                    "−${formatPlaybackTime((durationMs - displayedPositionMs).coerceAtLeast(0L))}"
                } else {
                    formatPlaybackTime(durationMs)
                },
                style = timeStyle,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/**
 * Seek bar split into recap, intro, episode and credits chapters with 3 dp gaps, a buffered range
 * and a ringed thumb. It thickens while dragged and shows the target time and chapter above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerChapterTimeline(
    durationMs: Long,
    displayedPositionMs: Long,
    bufferedPositionMs: Long,
    skipSegments: List<PlayerControlSkipSegment>,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sliderModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val palette = MaterialTheme.themePalette
    val chapters = remember(durationMs, skipSegments) { buildPlayerTimelineChapters(durationMs, skipSegments) }
    val rangeEnd = durationMs.coerceAtLeast(1L).toFloat()
    val description = stringResource(Res.string.player_seek_position)
    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    // A remote focusing the bar counts as hovering it on desktop: thicker rail, bubble.
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isInteracting = enabled && durationMs > 0L && (isPressed || isDragged || isFocused)
    val onFocusInteraction = LocalPlayerFocusInteraction.current
    LaunchedEffect(isFocused) { if (isFocused) onFocusInteraction() }
    val railHeight by animateDpAsState(if (isInteracting) 6.dp else 4.dp, tween(180), label = "playerRailHeight")
    val thumbScale by animateFloatAsState(if (isInteracting) 1f else 0.8f, tween(120), label = "playerThumbScale")
    val playedBrushColors = palette.accentGradient.takeIf { it.size >= 2 }
    val playedColor = colors.playerTimelineFill
    val ringColor = if (isFocused) colors.focusRing else colors.accent.copy(alpha = 0.35f)
    val trackColor = colors.playerTimelineTrack
    val bufferColor = Color.White.copy(alpha = 0.3f)
    val chapterLabels = mapOf(
        PlayerTimelineChapterType.Recap to stringResource(Res.string.player_chapter_recap),
        PlayerTimelineChapterType.Intro to stringResource(Res.string.player_chapter_intro),
        PlayerTimelineChapterType.Credits to stringResource(Res.string.player_chapter_credits),
    )
    var widthPx by remember { mutableIntStateOf(0) }
    val targetMs = (scrubPosition ?: displayedPositionMs).coerceIn(0L, durationMs)
    val chapterLabel = chapters.chapterAt(targetMs)?.let { chapterLabels[it.type] }
    val positionState = buildString {
        append(formatPlaybackTime(targetMs))
        append(" / ")
        append(formatPlaybackTime(durationMs))
        if (chapterLabel != null) append(" · ").append(chapterLabel)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier.height(ScrubRowHeight).onSizeChanged { widthPx = it.width }) {
            Slider(
                value = displayedPositionMs.coerceIn(0L, durationMs).toFloat(),
                onValueChange = { value ->
                    val position = value.toLong().coerceIn(0L, durationMs)
                    scrubPosition = position
                    onScrubChange(position)
                },
                onValueChangeFinished = {
                    onScrubFinished((scrubPosition ?: displayedPositionMs).coerceIn(0L, durationMs))
                    scrubPosition = null
                },
                valueRange = 0f..rangeEnd,
                enabled = enabled && durationMs > 0L,
                interactionSource = interactionSource,
                thumb = {},
                track = { state ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .drawBehind {
                                val gap = 3.dp.toPx()
                                val railPx = railHeight.toPx()
                                val top = size.height / 2 - railPx / 2
                                val radius = CornerRadius(railPx / 2)
                                val total = rangeEnd
                                val usable = (size.width - gap * (chapters.size - 1)).coerceAtLeast(0f)
                                val positionMs = state.value
                                val bufferedMs = if (bufferedPositionMs > 0L) maxOf(positionMs, bufferedPositionMs.toFloat()) else 0f
                                val playedBrush = playedBrushColors?.let {
                                    Brush.horizontalGradient(it, startX = 0f, endX = size.width)
                                } ?: SolidColor(playedColor)
                                var x = 0f
                                chapters.forEach { chapter ->
                                    val span = (chapter.endMs - chapter.startMs).coerceAtLeast(1L).toFloat()
                                    val width = (usable * span / total).coerceAtLeast(2.dp.toPx())
                                    fun fill(valueMs: Float) = ((valueMs - chapter.startMs) / span).coerceIn(0f, 1f)
                                    drawRoundRect(trackColor, Offset(x, top), Size(width, railPx), radius)
                                    val buffered = fill(bufferedMs)
                                    if (buffered > 0f) {
                                        clipRect(left = x, top = top, right = x + width * buffered, bottom = top + railPx) {
                                            drawRoundRect(bufferColor, Offset(x, top), Size(width, railPx), radius)
                                        }
                                    }
                                    val played = fill(positionMs)
                                    if (played > 0f) {
                                        clipRect(left = x, top = top, right = x + width * played, bottom = top + railPx) {
                                            drawRoundRect(playedBrush, Offset(x, top), Size(width, railPx), radius)
                                        }
                                    }
                                    x += width + gap
                                }
                                if (durationMs > 0L) {
                                    val center = Offset(size.width * (positionMs / total).coerceIn(0f, 1f), size.height / 2)
                                    val thumbRadius = 7.dp.toPx() * thumbScale
                                    drawCircle(Color.Black.copy(alpha = 0.35f), thumbRadius + 3.dp.toPx() * thumbScale, center.copy(y = center.y + 1.dp.toPx()))
                                    drawCircle(ringColor, thumbRadius + 3.dp.toPx() * thumbScale, center)
                                    drawCircle(Color.White, thumbRadius, center)
                                }
                            },
                    )
                },
                modifier = sliderModifier
                    .fillMaxWidth()
                    .height(ScrubRowHeight)
                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                    .requiredHeight(44.dp)
                    .semantics {
                        contentDescription = description
                        stateDescription = positionState
                    },
            )
            if (isInteracting && widthPx > 0) {
                // Decorative, like the desktop hover time; the slider announces the same text.
                Text(
                    text = if (chapterLabel != null) "${formatPlaybackTime(targetMs)} · $chapterLabel" else formatPlaybackTime(targetMs),
                    style = MaterialTheme.nuvioTypeScale.labelSm.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE))
                            val center = widthPx * (targetMs.toFloat() / rangeEnd).coerceIn(0f, 1f)
                            val x = (center - placeable.width / 2f).coerceIn(0f, (widthPx - placeable.width).coerceAtLeast(0).toFloat())
                            layout(0, 0) {
                                placeable.place(x.toInt(), -placeable.height - 10.dp.roundToPx())
                            }
                        }
                        .background(Color(0xF0101115), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Draws a ring [RingGap] outside the [shape] while [focused]. */
private fun Modifier.playerFocusRing(focused: Boolean, color: Color, shape: Shape): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val gap = RingGap.toPx()
        val stroke = RingWidth.toPx()
        val grow = gap + stroke / 2
        val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
        translate(-grow, -grow) { drawOutline(outline, color, style = Stroke(width = stroke)) }
    }

private val RingGap = 2.dp
private val RingWidth = 2.5.dp

/** Called when a control gains focus so remote navigation keeps the controls on screen. */
private val LocalPlayerFocusInteraction = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun PlayerPlayButton(
    isPlaying: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val size = if (compact) PlayerBarCompactPlayDp.dp else PlayerBarPlayDp.dp
    PressScaleBox(
        onClick = onClick,
        shape = CircleShape,
        focusRequester = focusRequester,
        modifier = Modifier
            .size(size)
            .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
            .background(colors.accent, CircleShape),
    ) {
        Icon(
            imageVector = if (isPlaying) PlayerControlIcons.Pause else PlayerControlIcons.Play,
            contentDescription = stringResource(if (isPlaying) Res.string.compose_action_pause else Res.string.detail_btn_play),
            tint = colors.onAccent,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun PlayerSeekButton(icon: ImageVector, seconds: Int, description: String, onClick: () -> Unit) {
    PressScaleBox(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(PlayerBarButtonDp.dp),
    ) {
        Icon(icon, contentDescription = description, tint = ControlForeground, modifier = Modifier.size(22.dp))
        Text(
            text = seconds.toString(),
            style = TextStyle(
                fontSize = if (seconds >= 100) 6.sp else 7.5.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.03).em,
            ),
            color = ControlForeground,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PlayerBarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    badge: String? = null,
    badgeIsSpeed: Boolean = false,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    PressScaleBox(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        focusRequester = focusRequester,
        modifier = Modifier
            .size(PlayerBarButtonDp.dp)
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(12.dp)),
    ) {
        Icon(icon, contentDescription = description, tint = if (selected) Color.White else ControlForeground, modifier = Modifier.size(22.dp))
        if (badge != null) {
            Text(
                text = badge,
                style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold),
                color = colors.onAccent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (badgeIsSpeed) 2.dp else 3.dp, end = if (badgeIsSpeed) 0.dp else 3.dp)
                    .background(colors.accent, RoundedCornerShape(if (badgeIsSpeed) 6.dp else 8.dp))
                    .widthIn(min = 16.dp)
                    .padding(horizontal = 4.dp, vertical = if (badgeIsSpeed) 0.dp else 1.dp),
            )
        }
    }
}

@Composable
private fun PlayerBarDivider() {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(width = 1.dp, height = 24.dp)
            .background(Color.White.copy(alpha = 0.18f)),
    )
}

/**
 * A rounded, rippled touch target that dips slightly while pressed, like the desktop buttons.
 * With a remote or keyboard it shows the theme focus ring outside its edge, as desktop
 * does for :focus-visible, and keeps the controls from hiding while focus moves.
 */
@Composable
private fun PressScaleBox(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val onFocusInteraction = LocalPlayerFocusInteraction.current
    LaunchedEffect(focused) { if (focused) onFocusInteraction() }
    val focusRing = MaterialTheme.nuvio.colors.focusRing
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "playerButtonPress")
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(modifier)
            .playerFocusRing(focused, focusRing, shape)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun PlayerControlPopover(
    width: Dp,
    maxWidth: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .width(minOf(width, maxWidth))
            .heightIn(max = maxHeight)
            .shadow(elevation = 24.dp, shape = shape, clip = false)
            .clip(shape)
            .background(PopoverSurface)
            .border(1.dp, PopoverBorder, shape)
            // Swallow taps on the surface so they do not reach the dismiss layer.
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

private class PlayerMenuEntry(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val value: String? = null,
    val chevron: Boolean = false,
)

@Composable
private fun PlayerMoreMenuContent(
    plan: PlayerBarPlan,
    playbackSpeed: Float,
    resizeMode: PlayerResizeMode,
    subtitlesActive: Boolean,
    hasPlaybackOptions: Boolean,
    enabledToggleCount: Int,
    firstItemFocus: FocusRequester,
    onNextEpisodeClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onSourcesClick: (() -> Unit)?,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onResizeModeClick: () -> Unit,
    onPlaybackOptionsClick: () -> Unit,
    onOpenInExternalPlayer: (() -> Unit)?,
    onHandoffClick: (() -> Unit)?,
    onCastInfoClick: (() -> Unit)?,
    onSubmitIntroClick: (() -> Unit)?,
    onDiagnosticsClick: (() -> Unit)?,
) {
    val nextEpisodeLabel = stringResource(Res.string.player_next_episode)
    val episodesLabel = stringResource(Res.string.compose_player_episodes)
    val sourcesLabel = stringResource(Res.string.compose_player_sources)
    val subtitlesLabel = stringResource(Res.string.compose_player_subtitles)
    val audioLabel = stringResource(Res.string.compose_player_audio)
    val speedLabel = stringResource(Res.string.compose_player_speed)
    val aspectLabel = stringResource(Res.string.player_aspect)
    val aspectValue = stringResource(resizeMode.labelRes)
    val optionsLabel = stringResource(Res.string.player_playback_options)
    val onLabel = stringResource(Res.string.compose_action_on)
    val externalLabel = stringResource(Res.string.streams_open_external_player)
    val handoffLabel = stringResource(Res.string.remote_handoff_send)
    val castLabel = stringResource(Res.string.player_cast_availability_title)
    val submitIntroLabel = stringResource(Res.string.submit_intro_action)
    val diagnosticsLabel = stringResource(Res.string.player_diagnostics_title)

    // Grouped like the desktop More menu: library, then tracks and view, then the phone extras.
    val library = buildList {
        if (onNextEpisodeClick != null && !plan.isInline(PlayerBarItem.NextEpisode)) {
            add(PlayerMenuEntry(PlayerControlIcons.SkipNext, nextEpisodeLabel, onNextEpisodeClick))
        }
        if (onEpisodesClick != null && !plan.isInline(PlayerBarItem.Episodes)) {
            add(PlayerMenuEntry(PlayerControlIcons.Episodes, episodesLabel, onEpisodesClick))
        }
        if (onSourcesClick != null && !plan.isInline(PlayerBarItem.Sources)) {
            add(PlayerMenuEntry(PlayerControlIcons.Source, sourcesLabel, onSourcesClick))
        }
    }
    val tracks = buildList {
        if (!plan.isInline(PlayerBarItem.Subtitles)) {
            val icon = if (subtitlesActive) PlayerControlIcons.Subtitles else PlayerControlIcons.SubtitlesOff
            add(PlayerMenuEntry(icon, subtitlesLabel, onSubtitleClick))
        }
        if (!plan.isInline(PlayerBarItem.Audio)) {
            add(PlayerMenuEntry(PlayerControlIcons.Audio, audioLabel, onAudioClick))
        }
        if (!plan.isInline(PlayerBarItem.Speed)) {
            add(PlayerMenuEntry(PlayerControlIcons.Speed, speedLabel, onSpeedClick, value = formatPlaybackSpeedLabel(playbackSpeed)))
        }
        if (!plan.isInline(PlayerBarItem.Aspect)) {
            add(PlayerMenuEntry(PlayerControlIcons.Aspect, aspectLabel, onResizeModeClick, value = aspectValue))
        }
        if (hasPlaybackOptions && !plan.isInline(PlayerBarItem.PlaybackOptions)) {
            add(
                PlayerMenuEntry(
                    icon = PlayerControlIcons.Options,
                    label = optionsLabel,
                    onClick = onPlaybackOptionsClick,
                    value = enabledToggleCount.takeIf { it > 0 }?.let { "$it $onLabel" },
                    chevron = true,
                ),
            )
        }
    }
    val extras = buildList {
        onOpenInExternalPlayer?.let { add(PlayerMenuEntry(PlayerControlIcons.OpenExternal, externalLabel, it)) }
        onHandoffClick?.let { add(PlayerMenuEntry(PlayerControlIcons.Handoff, handoffLabel, it)) }
        onCastInfoClick?.let { add(PlayerMenuEntry(PlayerControlIcons.Cast, castLabel, it)) }
        onSubmitIntroClick?.let { add(PlayerMenuEntry(PlayerControlIcons.Flag, submitIntroLabel, it)) }
        onDiagnosticsClick?.let { add(PlayerMenuEntry(PlayerControlIcons.Diagnostics, diagnosticsLabel, it)) }
    }
    val groups = listOf(library, tracks, extras).filter { it.isNotEmpty() }
    val firstEntry = groups.firstOrNull()?.firstOrNull()
    groups.forEachIndexed { index, entries ->
        if (index > 0) PlayerPopoverDivider()
        entries.forEach { entry ->
            PlayerMenuRow(
                entry.icon,
                entry.label,
                value = entry.value,
                chevron = entry.chevron,
                onClick = entry.onClick,
                modifier = if (entry === firstEntry) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun PlayerPlaybackOptionsContent(
    toggles: List<PlayerControlToggle>,
    firstItemFocus: FocusRequester,
    onInteraction: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
) {
    Text(
        text = stringResource(Res.string.compose_settings_page_playback).uppercase(),
        style = MaterialTheme.nuvioTypeScale.labelSm.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.em,
        ),
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            .semantics { heading() },
    )
    toggles.forEachIndexed { index, toggle ->
        PlayerOptionToggleRow(
            toggle = toggle,
            onInteraction = onInteraction,
            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
        )
    }
    if (onVideoSettingsClick != null) {
        if (toggles.isNotEmpty()) PlayerPopoverDivider()
        PlayerOptionLinkRow(
            icon = PlayerControlIcons.Build,
            label = stringResource(Res.string.player_action_video_settings),
            onClick = onVideoSettingsClick,
            modifier = if (toggles.isEmpty()) Modifier.focusRequester(firstItemFocus) else Modifier,
        )
    }
}

@Composable
private fun PlayerMenuRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    chevron: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (interaction, focusModifier) = rememberMenuRowFocus()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(MenuRowShape)
            .then(focusModifier)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        PlayerOptionLabel(label)
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
            )
        }
        if (chevron) {
            Icon(PlayerControlIcons.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PlayerOptionToggleRow(
    toggle: PlayerControlToggle,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val stateLabel = stringResource(if (toggle.checked) Res.string.compose_action_on else Res.string.compose_action_off)
    val knobOffset by animateDpAsState(if (toggle.checked) 16.dp else 0.dp, tween(180), label = "playerOptionSwitch")
    val (interaction, focusModifier) = rememberMenuRowFocus()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MenuRowShape)
            .then(focusModifier)
            .toggleable(
                value = toggle.checked,
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Switch,
                onValueChange = { checked ->
                    onInteraction()
                    toggle.onCheckedChange(checked)
                },
            )
            .semantics { stateDescription = stateLabel }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerOptionIcon(toggle.icon, active = toggle.checked)
        PlayerOptionLabel(toggle.label)
        Box(
            Modifier
                .size(width = 40.dp, height = 24.dp)
                .background(if (toggle.checked) colors.accent else Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .padding(3.dp),
        ) {
            // On a light accent a white knob would vanish, so the on state uses the on-accent color.
            Box(
                Modifier
                    .padding(start = knobOffset)
                    .size(18.dp)
                    .background(if (toggle.checked) colors.onAccent else Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun PlayerOptionLinkRow(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (interaction, focusModifier) = rememberMenuRowFocus()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MenuRowShape)
            .then(focusModifier)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerOptionIcon(icon, active = false)
        PlayerOptionLabel(label)
        Icon(PlayerControlIcons.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    }
}

private val MenuRowShape = RoundedCornerShape(12.dp)

/** The inner focus ring of menu rows, like the outline desktop option rows show for :focus-visible. */
@Composable
private fun rememberMenuRowFocus(): Pair<MutableInteractionSource, Modifier> {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = MaterialTheme.nuvio.colors.focusRing
    return interaction to if (focused) {
        Modifier.background(Color.White.copy(alpha = 0.07f)).border(2.dp, ring, MenuRowShape)
    } else {
        Modifier
    }
}

@Composable
private fun PlayerOptionIcon(icon: ImageVector, active: Boolean) {
    val accent = MaterialTheme.nuvio.colors.accent
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) accent else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowScope.PlayerOptionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun PlayerPopoverDivider() {
    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(PopoverBorder),
    )
}

/** The read-only seek row shown under the lock screen. */
@Composable
internal fun PlayerLockedScrubRow(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    showRemainingTime: Boolean,
    skipSegments: List<PlayerControlSkipSegment>,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        PlayerScrubRow(
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            showRemainingTime = showRemainingTime,
            skipSegments = skipSegments,
            onRuntimeClick = {},
            onScrubChange = {},
            onScrubFinished = {},
            enabled = false,
        )
    }
}
