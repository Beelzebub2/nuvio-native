package com.nuvio.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioDesktopImageScaling
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.DesktopBackdropVerticalBias
import com.nuvio.app.core.ui.StandardDesktopViewportAspectRatio
import com.nuvio.app.core.ui.FullscreenActionButton
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.fullscreenActionHorizontalInsetForWidth
import com.nuvio.app.core.ui.expandingWideArtworkWidthDp
import com.nuvio.app.core.ui.isFullscreenActionSupported
import com.nuvio.app.core.ui.WideDesktopViewportAspectRatio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.tmdb.originalTmdbImageUrl
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.detail_logo_content_description
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.playback_unavailable
import nuvio.composeapp.generated.resources.random_episode_title
import nuvio.composeapp.generated.resources.shuffle_stop
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.rating_imdb
import nuvio.composeapp.generated.resources.source_imdb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import nuvio.composeapp.generated.resources.detail_action_library
import nuvio.composeapp.generated.resources.detail_action_watched
import nuvio.composeapp.generated.resources.detail_minutes_left
import nuvio.composeapp.generated.resources.detail_synopsis_more
import nuvio.composeapp.generated.resources.detail_trailer_muted
import nuvio.composeapp.generated.resources.detail_trailer_sound_on
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DesktopDetailBackdrop(
    meta: MetaDetails,
    viewportHeight: Dp,
    heroTrailerSourceUrl: String?,
    heroTrailerSourceAudioUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerPlayWhenReady: Boolean,
    heroTrailerMuted: Boolean,
    heroGradientColor: Color? = null,
    blurBackdrop: Boolean = false,
    onBackdropLoaded: (Painter) -> Unit = {},
    onHeroTrailerReady: () -> Unit,
    onHeroTrailerEnded: () -> Unit,
    onHeroTrailerError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val sideGradientColor = heroGradientColor ?: colorScheme.background
    val opacity = NuvioTokens.Opacity
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_trailer_alpha",
    )
    val gradientIntensity by animateFloatAsState(
        targetValue = if (heroTrailerReady) 0.3f else 1f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_gradient_intensity",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(sideGradientColor),
    ) {
        val aspectRatio = maxWidth.value / viewportHeight.value
        val useWideArtworkFrame = aspectRatio > StandardDesktopViewportAspectRatio
        val cropSideBackdrop = aspectRatio > WideDesktopViewportAspectRatio
        val backdropWidth = if (useWideArtworkFrame) {
            expandingWideArtworkWidthDp(maxWidth.value, viewportHeight.value).dp
        } else {
            maxWidth
        }
        val backdropModifier = Modifier
            .align(Alignment.CenterEnd)
            .width(backdropWidth)
            .fillMaxHeight()
        val artworkModifier = if (blurBackdrop) backdropModifier.blur(30.dp) else backdropModifier
        val bottomSpreadStrength = ((21f / 9f - aspectRatio) / (5f / 9f)).coerceIn(0f, 1f)
        val baseSideFade = Brush.horizontalGradient(
            colorStops = arrayOf(
                0.00f to sideGradientColor,
                0.12f to sideGradientColor.copy(alpha = 0.98f * gradientIntensity),
                0.34f to sideGradientColor.copy(alpha = opacity.overlayHeavy * gradientIntensity),
                0.62f to sideGradientColor.copy(alpha = opacity.overlayLight * gradientIntensity),
                0.86f to sideGradientColor.copy(alpha = opacity.subtle * gradientIntensity),
                1.00f to Color.Transparent,
            ),
        )
        val imageUrl = meta.background ?: meta.poster
        if (imageUrl != null) {
            AsyncImage(
                model = originalTmdbImageUrl(imageUrl),
                contentDescription = meta.name,
                modifier = artworkModifier,
                alignment = BiasAlignment(0f, DesktopBackdropVerticalBias),
                contentScale = if (useWideArtworkFrame && !cropSideBackdrop) ContentScale.Fit else ContentScale.Crop,
                desktopImageScaling = NuvioDesktopImageScaling.Disabled,
                onSuccess = { state -> onBackdropLoaded(state.painter) },
            )
        } else {
            DesktopStripePlaceholder(modifier = backdropModifier)
        }

        if (heroTrailerSourceUrl != null) {
            HeroTrailerPlayerSurface(
                sourceUrl = heroTrailerSourceUrl,
                sourceAudioUrl = heroTrailerSourceAudioUrl,
                playWhenReady = heroTrailerPlayWhenReady,
                muted = heroTrailerMuted,
                fillFrame = true,
                modifier = artworkModifier.graphicsLayer { alpha = trailerAlpha },
                onReady = onHeroTrailerReady,
                onEnded = onHeroTrailerEnded,
                onError = onHeroTrailerError,
            )
        }

        Box(
            modifier = backdropModifier
                .drawWithCache {
                    val bottomSpread = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to sideGradientColor.copy(alpha = 0.12f * gradientIntensity * bottomSpreadStrength),
                            0.55f to sideGradientColor.copy(alpha = 0.12f * gradientIntensity * bottomSpreadStrength),
                            1.00f to Color.Transparent,
                        ),
                        center = Offset(0f, size.height * 1.15f),
                        radius = size.width * 0.72f,
                    )
                    onDrawBehind {
                        drawRect(baseSideFade)
                        if (bottomSpreadStrength > 0f) drawRect(bottomSpread)
                    }
                },
        )
    }
}

/** What the primary action resumes or starts, shown above the desktop action bar. */
data class DesktopResumeLine(
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val remainingMinutes: Int?,
    val progress: Float?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopDetailHero(
    meta: MetaDetails,
    showOverallRatings: Boolean,
    isMdbListActive: Boolean,
    playButtonLabel: String,
    isPrimaryPlayEnabled: Boolean,
    isSaved: Boolean,
    isWatched: Boolean,
    onHeightChanged: (Int) -> Unit,
    heroTrailerSourceUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerMuted: Boolean,
    onHeroTrailerMuteToggle: () -> Unit,
    onPlayClick: () -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onShuffleClick: (() -> Unit)?,
    shuffleEnabled: Boolean,
    onWatchedClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
    watchLaterEpisode: com.nuvio.app.features.details.MetaVideo? = null,
    resumeLine: DesktopResumeLine? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.nuvio.colors
    val space = NuvioTokens.Space
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_controls_alpha",
    )
    var logoLoadError by remember(meta.id, meta.logo) { mutableStateOf(false) }
    val logoUrl = meta.logo?.takeIf { it.isNotBlank() }
    var showSynopsisPanel by remember(meta.id) { mutableStateOf(false) }
    var synopsisOverflows by remember(meta.id, meta.description) { mutableStateOf(false) }
    val playLabel = if (isPrimaryPlayEnabled) playButtonLabel else stringResource(Res.string.playback_unavailable)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 660.dp)
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        val actionHorizontalInset = fullscreenActionHorizontalInsetForWidth(maxWidth.value)
        val pageHorizontalPadding = desktopPageHorizontalPaddingForWidth(maxWidth.value)

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 780.dp)
                .padding(
                    start = pageHorizontalPadding,
                    end = space.s32,
                    top = 120.dp,
                    bottom = space.s40,
                ),
            verticalArrangement = Arrangement.spacedBy(space.s16),
        ) {
            if (logoUrl != null && !logoLoadError) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .height(120.dp),
                    alignment = Alignment.CenterStart,
                    contentScale = ContentScale.Fit,
                    onError = { logoLoadError = true },
                )
            } else {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = NuvioTokens.Type.displayMd,
                        lineHeight = NuvioTokens.LineHeight.displayMd,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.pageDisplay,
                    ),
                    color = colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(space.s12)) {
                DesktopHeroMetaRow(meta = meta, showOverallRatings = showOverallRatings && !isMdbListActive)
                if (isMdbListActive && meta.externalRatings.isNotEmpty()) {
                    DetailRatingsRow(
                        ratings = meta.externalRatings,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                }
            }
            if (meta.genres.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(space.s8),
                    verticalArrangement = Arrangement.spacedBy(space.s8),
                ) {
                    meta.genres.take(4).forEach { genre -> DetailGenreChip(genre) }
                }
            }
            meta.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Column(
                    modifier = Modifier.widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(space.s4),
                ) {
                    Text(
                        text = synopsis,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = NuvioTokens.Type.bodyLg,
                            lineHeight = 25.sp,
                            letterSpacing = NuvioTokens.LetterSpacing.none,
                        ),
                        color = colors.textPrimary.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> synopsisOverflows = layout.hasVisualOverflow },
                    )
                    if (synopsisOverflows) {
                        Text(
                            text = stringResource(Res.string.detail_synopsis_more),
                            modifier = Modifier
                                .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                                .clickable(role = Role.Button) { showSynopsisPanel = true }
                                .padding(vertical = space.s2),
                            style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
            resumeLine?.let { DesktopHeroResumeLine(it) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(space.s10),
                verticalArrangement = Arrangement.spacedBy(space.s10),
            ) {
                NuvioCompactActionButton(
                    label = playLabel,
                    icon = Icons.Rounded.PlayArrow,
                    onClick = onPlayClick,
                    modifier = Modifier.secondaryClick(onPlayLongClick),
                    enabled = isPrimaryPlayEnabled,
                    tone = NuvioCompactActionTone.Primary,
                    size = NuvioActionSize.Large,
                )
                NuvioCompactActionButton(
                    label = stringResource(Res.string.detail_action_library),
                    icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                    onClick = onSaveClick,
                    modifier = Modifier.secondaryClick(onSaveLongClick),
                    tone = NuvioCompactActionTone.Secondary,
                    accessibilityLabel = stringResource(
                        if (isSaved) Res.string.hero_remove_from_library else Res.string.hero_add_to_library,
                    ),
                    size = NuvioActionSize.Large,
                    selected = isSaved,
                )
                com.nuvio.app.features.watchlater.WatchLaterHeroButton(
                    item = com.nuvio.app.features.home.MetaPreview(
                        meta.id, meta.type, meta.name,
                        poster = meta.poster, banner = meta.background, logo = meta.logo, description = meta.description,
                    ),
                    episode = watchLaterEpisode,
                )
                NuvioCompactActionButton(
                    label = stringResource(Res.string.detail_action_watched),
                    icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                    onClick = onWatchedClick,
                    tone = NuvioCompactActionTone.Secondary,
                    accessibilityLabel = stringResource(
                        if (isWatched) Res.string.hero_mark_unwatched else Res.string.hero_mark_watched,
                    ),
                    size = NuvioActionSize.Large,
                    selected = isWatched,
                )
                onShuffleClick?.let { onClick ->
                    NuvioIconActionButton(
                        icon = Icons.Default.Shuffle,
                        contentDescription = stringResource(
                            if (shuffleEnabled) Res.string.shuffle_stop else Res.string.random_episode_title,
                        ),
                        onClick = onClick,
                        size = NuvioActionSize.Large,
                        selected = shuffleEnabled,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(space.s8),
                verticalArrangement = Arrangement.spacedBy(space.s8),
            ) {
                com.nuvio.app.features.myanimelist.MyAnimeListControls(meta)
            }
        }

        if (heroTrailerSourceUrl != null) {
            NuvioCompactActionButton(
                label = stringResource(
                    if (heroTrailerMuted) Res.string.detail_trailer_muted else Res.string.detail_trailer_sound_on,
                ),
                icon = if (heroTrailerMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                onClick = onHeroTrailerMuteToggle,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = pageHorizontalPadding, bottom = space.s40)
                    .graphicsLayer { alpha = trailerAlpha },
                enabled = heroTrailerReady,
                tone = NuvioCompactActionTone.Ghost,
                size = NuvioActionSize.Small,
            )
        }

        if (isFullscreenActionSupported) {
            FullscreenActionButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = space.s32, end = actionHorizontalInset),
                buttonSize = 48.dp,
                iconSize = 24.dp,
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.82f),
                contentColor = colorScheme.onSurface,
            )
        }
    }

    if (showSynopsisPanel) {
        DetailSynopsisPanel(
            meta = meta,
            onDismiss = { showSynopsisPanel = false },
        ) {
            NuvioCompactActionButton(
                label = playLabel,
                icon = Icons.Rounded.PlayArrow,
                onClick = {
                    showSynopsisPanel = false
                    onPlayClick()
                },
                enabled = isPrimaryPlayEnabled,
                tone = NuvioCompactActionTone.Primary,
            )
        }
    }
}

@Composable
private fun DesktopHeroResumeLine(line: DesktopResumeLine) {
    val colors = MaterialTheme.nuvio.colors
    val code = localizedSeasonEpisodeCode(line.seasonNumber, line.episodeNumber)
    if (code == null && line.episodeTitle.isNullOrBlank() && line.progress == null) return
    Column(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            code?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            Text(
                text = line.episodeTitle.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            line.remainingMinutes?.let { minutes ->
                Text(
                    text = stringResource(Res.string.detail_minutes_left, minutes),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        line.progress?.let { progress ->
            NuvioProgressBar(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                height = NuvioTokens.Space.s4,
                trackColor = colors.textPrimary.copy(alpha = 0.14f),
                fillColor = colors.accent,
            )
        }
    }
}

@Composable
private fun DesktopHeroMetaRow(meta: MetaDetails, showOverallRatings: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val space = NuvioTokens.Space
    val opacity = NuvioTokens.Opacity
    val metaItems = buildList {
        desktopYearLabel(meta)?.let(::add)
        desktopSeasonCountLabel(meta)?.let(::add)
        formatRuntimeForDisplay(meta.runtime)?.let(::add)
    }
    val validImdbRating = meta.imdbRating
        ?.takeIf { raw -> raw.toDoubleOrNull()?.let { it > 0.0 } == true }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.s16),
    ) {
        metaItems.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = NuvioTokens.Type.bodyMd,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = NuvioTokens.LetterSpacing.none,
                ),
                color = MaterialTheme.nuvio.colors.textSecondary,
                maxLines = 1,
            )
        }
        meta.ageRating?.takeIf { it.isNotBlank() }?.let { rating ->
            Box(
                modifier = Modifier
                    .border(
                        NuvioTokens.Border.thin,
                        colorScheme.onBackground.copy(alpha = opacity.overlayLight),
                        RoundedCornerShape(NuvioTokens.Radius.sm),
                    )
                    .padding(horizontal = space.s8, vertical = space.s2),
            ) {
                Text(
                    text = rating,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = NuvioTokens.Type.bodySm,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
        if (validImdbRating != null && showOverallRatings) {
            val imdbTextStyle = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImdbRatingSourceLabel(
                    storeTextStyle = imdbTextStyle,
                    storeTextColor = ImdbYellow,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = validImdbRating,
                    style = imdbTextStyle,
                    color = ImdbYellow,
                )
            }
        }
    }
}
