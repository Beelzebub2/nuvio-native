package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.app.features.trailer.TrailerHttpRangeMediaData
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import com.nuvio.app.features.trailer.createTrailerMediaData
import com.nuvio.app.features.trailer.requiresBoundedRanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.source.MediaData

private const val TrailerFillFrameScale = 1.35f

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMillis: Long,
    fillFrame: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val player = rememberMediampPlayer()
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    var mediaPrepared by remember(player, sourceUrl, sourceAudioUrl, startPositionMillis) {
        mutableStateOf(false)
    }
    var terminalReported by remember(player, sourceUrl, sourceAudioUrl, startPositionMillis) {
        mutableStateOf(false)
    }
    var activeMediaData by remember(player, sourceUrl, sourceAudioUrl, startPositionMillis) {
        mutableStateOf<MediaData?>(null)
    }
    var registeredAudioInput by remember(player) {
        mutableStateOf<String?>(null)
    }
    val latestRegisteredAudioInput = rememberUpdatedState(registeredAudioInput)

    DisposableEffect(player) {
        onDispose {
            latestRegisteredAudioInput.value?.let { uri ->
                runCatching { (player.impl as? MPVHandle)?.unregisterSeekableInput(uri) }
            }
        }
    }

    LaunchedEffect(player, sourceUrl, sourceAudioUrl, startPositionMillis) {
        player.events.collect { event ->
            when (event) {
                is PlaybackEvent.MediaEnded -> {
                    if (event.mediaData === activeMediaData && !terminalReported) {
                        terminalReported = true
                        mediaPrepared = false
                        TrailerExtractionPlatform.diagnostic("mediamp ended")
                        latestOnEnded.value()
                    }
                }

                is PlaybackEvent.ErrorOccurred -> {
                    if (!terminalReported) {
                        terminalReported = true
                        mediaPrepared = false
                        TrailerExtractionPlatform.diagnostic(
                            "blocked stage=mediamp error=${event.error::class.simpleName} detail=${event.error}",
                        )
                        latestOnError.value()
                    }
                }

                else -> Unit
            }
        }
    }

    LaunchedEffect(player, sourceUrl, sourceAudioUrl, startPositionMillis) {
        mediaPrepared = false
        terminalReported = false
        val handle = player.impl as? MPVHandle
            ?: error("MediaMP desktop backend did not provide an MPV handle")
        registeredAudioInput?.let { uri ->
            runCatching { handle.unregisterSeekableInput(uri) }
            registeredAudioInput = null
        }
        val mediaData = createTrailerMediaData(sourceUrl, "video")
        activeMediaData = mediaData
        TrailerExtractionPlatform.diagnostic(
            "mediamp open ${TrailerExtractionPlatform.describeUrl(sourceUrl)} " +
                "separateAudio=${!sourceAudioUrl.isNullOrBlank()} startMs=$startPositionMillis",
        )

        try {
            player.setMediaData(
                data = mediaData,
                playWhenReady = false,
                startPositionMillis = startPositionMillis,
            )
            handle.setPropertyString("loop-file", "no")
            if (!sourceAudioUrl.isNullOrBlank()) {
                TrailerExtractionPlatform.diagnostic(
                    "mediamp audio attach ${TrailerExtractionPlatform.describeUrl(sourceAudioUrl)}",
                )
                if (sourceAudioUrl.requiresBoundedRanges()) {
                    val audioData = TrailerHttpRangeMediaData(sourceAudioUrl, "audio")
                    val audioInput = audioData.createInput(currentCoroutineContext())
                    val audioTarget =
                        "mediamp://trailer_audio/${sourceAudioUrl.hashCode().toUInt().toString(16)}-${System.nanoTime()}"
                    val registered = try {
                        handle.registerSeekableInput(audioInput, audioTarget)
                    } catch (error: Throwable) {
                        audioInput.close()
                        throw error
                    }
                    registeredAudioInput = registered
                    if (!handle.command("audio-add", registered, "select", "Trailer audio")) {
                        handle.unregisterSeekableInput(registered)
                        registeredAudioInput = null
                        error("MPV rejected the separate trailer audio track")
                    }
                } else {
                    check(handle.command("audio-add", sourceAudioUrl, "select", "Trailer audio")) {
                        "MPV rejected the separate trailer audio track"
                    }
                }
                TrailerExtractionPlatform.diagnostic("mediamp audio attached")
            }
            handle.setPropertyBoolean("mute", muted)
            mediaPrepared = true
            if (playWhenReady) {
                player.play()
            }
            TrailerExtractionPlatform.diagnostic("mediamp ready playing=$playWhenReady")
            latestOnReady.value()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mediaPrepared = false
            if (!terminalReported) {
                terminalReported = true
                TrailerExtractionPlatform.diagnostic(
                    "blocked stage=mediamp_open error=${error::class.simpleName} detail=$error",
                )
                latestOnError.value()
            }
        }
    }

    LaunchedEffect(player, playWhenReady, mediaPrepared) {
        if (mediaPrepared) {
            if (playWhenReady) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    LaunchedEffect(player, muted, mediaPrepared) {
        if (mediaPrepared) {
            (player.impl as? MPVHandle)?.setPropertyBoolean("mute", muted)
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        MediampPlayerSurface(
            mediampPlayer = player,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (fillFrame) {
                        scaleX = TrailerFillFrameScale
                        scaleY = TrailerFillFrameScale
                    }
                },
        )
    }
}
