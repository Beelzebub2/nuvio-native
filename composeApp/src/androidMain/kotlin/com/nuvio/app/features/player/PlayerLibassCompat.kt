package com.nuvio.app.features.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.util.Collections
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())

@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    renderType: AssRenderType = AssRenderType.CUES,
    dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context)
): ExoPlayer {
    val assHandler = AssHandler(renderType)
    var player: ExoPlayer? = null
    try {
        val mediaSourceFactory = createAssMediaSourceFactoryCompat(
            assHandler = assHandler,
            dataSourceFactory = dataSourceFactory,
            extractorsFactory = extractorsFactory,
        )
        val createdPlayer = this
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory.withAssSupport(assHandler))
            .build()
        player = createdPlayer
        assHandler.init(createdPlayer)
        assHandlersByPlayer[createdPlayer] = assHandler
        return createdPlayer
    } catch (failure: Throwable) {
        player?.let { runCatching { it.release() } }
        runCatching { assHandler.release() }
        throw failure
    }
}

/** Split video/audio playback must use the same ASS-aware subtitle parser. */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.createAssMediaSourceFactoryCompat(
    dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
): DefaultMediaSourceFactory? {
    val handler = getAssHandlerCompat() ?: return null
    return createAssMediaSourceFactoryCompat(handler, dataSourceFactory, extractorsFactory)
}

@OptIn(UnstableApi::class)
private fun createAssMediaSourceFactoryCompat(
    assHandler: AssHandler,
    dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
): DefaultMediaSourceFactory {
    val parser = CompatAssSubtitleParserFactory(assHandler)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(parser, assHandler)
    return DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
        setSubtitleParserFactory(parser)
    }
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

@OptIn(UnstableApi::class)
internal fun ExoPlayer.releaseWithAssSupportCompat() {
    val assHandler = assHandlersByPlayer.remove(this)
    try {
        release()
    } finally {
        assHandler?.release()
    }
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        return delegate.create(normalizeSsaFormat(format))
    }

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        if (isSsaByCodecs && !isSsaByMime) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build()
        }
        return format
    }
}

@OptIn(UnstableApi::class)
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    return ExtractorsFactory {
        val extractors = createExtractors()
        extractors.forEachIndexed { index, extractor ->
            if (extractor is MatroskaExtractor) {
                extractors[index] = AssMatroskaExtractor(subtitleParserFactory, assHandler)
            }
        }
        extractors
    }
}
