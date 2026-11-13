package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nuvio.app.isDesktop
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation

@Composable
fun NuvioScreen(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MaterialTheme.nuvio.spacing.screenHorizontal,
    topPadding: Dp? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = topPadding ?: tokens.spacing.screenTop + statusBarTop + nuvioPlatformExtraTopPadding,
                end = horizontalPadding,
                bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom),
            ),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            content = content,
        )
        NuvioDesktopVerticalScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

internal fun Modifier.nuvioConsumePointerEvents(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                    change.consume()
                }
            }
        }
    }

@Composable
fun NuvioSurfaceCard(
    modifier: Modifier = Modifier,
    tonalElevation: Int = 0,
    outlined: Boolean = false,
    contentPadding: Dp = MaterialTheme.nuvio.spacing.cardPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        tonalElevation = tonalElevation.dp,
        shadowElevation = tokens.elevation.flat,
        border = if (outlined) BorderStroke(tokens.borders.thin, tokens.colors.borderSubtle) else null,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

@Composable
fun NuvioScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.nuvio.colors.background,
    includeStatusBarPadding: Boolean = true,
    topPadding: Dp? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nativeDetailNavigation = LocalUseNativeNavigation.current &&
        !LocalNativeNavigationBarHidden.current &&
        onBack != null
    if (nativeDetailNavigation) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = NuvioTokens.Space.s4),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        return
    }
    val resolvedTopPadding = topPadding ?: if (includeStatusBarPadding) statusBarTop else NuvioTokens.Space.none
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(backgroundColor)
                .nuvioConsumePointerEvents(),
        ) {}
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = resolvedTopPadding, bottom = NuvioTokens.Space.s4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen_header_title",
                ) { currentTitle ->
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.displayLarge,
                        color = tokens.colors.textPrimary,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!LocalFullscreenActionInTopBar.current) {
                    FullscreenActionButton(
                        buttonSize = 48.dp,
                        iconSize = 24.dp,
                        contentColor = tokens.colors.textPrimary,
                    )
                }
                actions()
            }
        }
    }
}

@Composable
fun NuvioSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun NuvioActionLabel(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                    .clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
        ),
        style = style,
        color = MaterialTheme.nuvio.colors.accent,
        maxLines = maxLines,
    )
}

@Composable
fun NuvioIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.nuvio.colors.textPrimary,
    onClick: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    IconButton(
        modifier = modifier
            .background(
                color = tokens.colors.background.copy(alpha = 0.001f),
                shape = tokens.shapes.avatar,
            ),
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@Composable
fun NuvioBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.nuvio.shapes.avatar,
    containerColor: Color = MaterialTheme.nuvio.colors.surface,
    showContainerOnDesktop: Boolean = false,
    contentColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    buttonSize: Dp = NuvioTokens.Space.s40,
    iconSize: Dp = NuvioTokens.Icon.md,
    contentDescription: String = stringResource(Res.string.action_back),
) {
    if (LocalUseNativeNavigation.current && !LocalNativeNavigationBarHidden.current) return
    val effectiveContainerColor = if (isDesktop && !showContainerOnDesktop) Color.Transparent else containerColor

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(shape)
            .background(effectiveContainerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun NuvioPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
        enabled = enabled && !loading,
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.accent,
            contentColor = tokens.colors.onAccent,
            disabledContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.disabled),
            disabledContentColor = tokens.colors.onAccent.copy(
                alpha = if (loading) tokens.opacity.strong else tokens.opacity.disabled,
            ),
        ),
    ) {
        if (loading) {
            NuvioLoadingIndicator(
                modifier = Modifier.size(tokens.icons.md),
                color = tokens.colors.onAccent.copy(alpha = tokens.opacity.strong),
                size = tokens.icons.md,
            )
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s8))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(tokens.icons.md))
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s8))
        }
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "buttonText",
        ) { animatedText ->
            Text(
                text = animatedText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The app's text field: card-coloured fill, radius 12, and a default border that turns to the
 * focus colour (or the danger colour when [isError]). [minHeight] is 56 dp by default; search and
 * dialog fields pass 48 dp.
 */
@Composable
fun NuvioInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable (() -> Unit))? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = NuvioTokens.Space.s56,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    containerColor: Color = MaterialTheme.nuvio.colors.surfaceCard,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> tokens.colors.danger
            focused -> tokens.colors.borderFocus
            else -> tokens.colors.borderDefault
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "input_field_border",
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled),
        cursorBrush = SolidColor(if (isError) tokens.colors.danger else tokens.colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(shape)
                    .background(containerColor)
                    .border(if (focused || isError) tokens.borders.medium else tokens.borders.thin, borderColor, shape)
                    .padding(
                        start = if (leadingIcon != null) NuvioTokens.Space.s14 else NuvioTokens.Space.s16,
                        end = if (trailingContent != null) NuvioTokens.Space.s4 else NuvioTokens.Space.s16,
                        top = if (singleLine) NuvioTokens.Space.none else NuvioTokens.Space.s14,
                        bottom = if (singleLine) NuvioTokens.Space.none else NuvioTokens.Space.s14,
                    ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(NuvioTokens.Space.s18),
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = tokens.colors.textMuted,
                            style = textStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                trailingContent?.invoke()
            }
        },
    )
}

@Composable
fun NuvioInfoBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.nuvio.colors.textMuted,
    contentColor: Color = MaterialTheme.nuvio.colors.textMuted,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .background(
                color = tokens.colors.surfaceCard,
                shape = tokens.shapes.chip,
            )
            .padding(
                start = if (icon != null) NuvioTokens.Space.s8 else NuvioTokens.Space.s10,
                end = NuvioTokens.Space.s10,
                top = NuvioTokens.Space.s6,
                bottom = NuvioTokens.Space.s6,
            ),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(NuvioTokens.Space.s14))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NuvioInlineMetadata(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        Spacer(modifier = Modifier.width(NuvioTokens.Space.s6))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
    }
}

@Composable
fun NuvioStatusModal(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    isBusy: Boolean = false,
    destructive: Boolean = false,
    confirmText: String = stringResource(Res.string.action_ok),
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    if (!isVisible) return

    DialogSurface(
        onDismissRequest = {
            if (!isBusy) {
                onDismiss?.invoke() ?: onConfirm()
            }
        },
        modifier = modifier,
        title = title,
        message = message,
    ) {
        DialogButtons {
            if (!isBusy && dismissText != null && onDismiss != null) {
                DialogButton(text = dismissText, onClick = onDismiss)
            }
            DialogButton(
                text = confirmText,
                onClick = onConfirm,
                style = if (destructive) DialogButtonStyle.Destructive else DialogButtonStyle.Primary,
                loading = isBusy,
            )
        }
    }
}

@Composable
fun BoxScope.NuvioToastHost(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val toast by NuvioToastController.currentToast.collectAsState()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val visibilityState = remember { MutableTransitionState(false) }
    var renderedToast by remember { mutableStateOf<NuvioToastMessage?>(null) }

    LaunchedEffect(toast?.id) {
        val currentToast = toast
        if (currentToast != null) {
            renderedToast = currentToast
            visibilityState.targetState = true
            delay(currentToast.durationMillis)
            NuvioToastController.dismiss(currentToast.id)
        } else {
            visibilityState.targetState = false
        }
    }

    LaunchedEffect(
        visibilityState.currentState,
        visibilityState.targetState,
        visibilityState.isIdle,
    ) {
        if (visibilityState.isIdle && !visibilityState.currentState && !visibilityState.targetState) {
            renderedToast = null
        }
    }

    AnimatedVisibility(
        visibleState = visibilityState,
        modifier = modifier
            .align(if (isDesktop) Alignment.BottomEnd else Alignment.TopCenter)
            .zIndex(20f),
        enter = fadeIn() + slideInVertically { if (isDesktop) it else -it },
        exit = fadeOut() + slideOutVertically { if (isDesktop) it else -it },
    ) {
        val currentToast = renderedToast ?: return@AnimatedVisibility
        Box(
            modifier = if (isDesktop) {
                Modifier.padding(NuvioTokens.Space.s16).widthIn(max = 420.dp)
            } else {
                Modifier.fillMaxWidth()
                    .padding(top = statusBarTop + tokens.spacing.listGap)
                    .padding(horizontal = tokens.spacing.screenHorizontal)
            },
            contentAlignment = if (isDesktop) Alignment.BottomEnd else Alignment.TopCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(NuvioTokens.Radius.xl),
                color = tokens.colors.surfacePopover,
                tonalElevation = tokens.elevation.raised,
                shadowElevation = tokens.elevation.overlay,
            ) {
                Text(
                    text = currentToast.message,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = if (isDesktop) 3 else Int.MAX_VALUE,
                    overflow = if (isDesktop) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        }
    }
}

data class NuvioToastMessage(
    val id: Long,
    val message: String,
    val durationMillis: Long,
)

object NuvioToastController {
    private val _currentToast = MutableStateFlow<NuvioToastMessage?>(null)
    val currentToast = _currentToast.asStateFlow()
    private var nextToastId = 0L

    fun show(
        message: String,
        durationMillis: Long = 2500L,
    ) {
        nextToastId += 1L
        _currentToast.value = NuvioToastMessage(
            id = nextToastId,
            message = message,
            durationMillis = durationMillis,
        )
    }

    fun dismiss(id: Long? = null) {
        val activeToast = _currentToast.value ?: return
        if (id == null || activeToast.id == id) {
            _currentToast.value = null
        }
    }
}
