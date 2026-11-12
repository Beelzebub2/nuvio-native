package com.nuvio.app.features.player

import android.widget.FrameLayout
import com.nuvio.app.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.type.AssRenderType
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLibassCompatTest {
    @Test
    fun everySubtitleModeCanOpenAndCloseWithoutRetainingThePlayerHandler() {
        val context = RuntimeEnvironment.getApplication()
        LibassRenderType.entries.forEach { mode ->
            val player = ExoPlayer.Builder(context).buildWithAssSupportCompat(
                context = context,
                renderType = mode.toAssRenderType(),
            )
            try {
                val handler = assertNotNull(player.getAssHandlerCompat())
                assertEquals(mode.toAssRenderType(), handler.renderType)
                assertEquals(0, handler.config.maxRenderPixels)
                assertNotNull(
                    player.createAssMediaSourceFactoryCompat(
                        DefaultDataSource.Factory(context),
                        DefaultExtractorsFactory(),
                    ),
                    "Split audio/video playback must keep the ASS-aware extractor",
                )
            } finally {
                player.releaseWithAssSupportCompat()
            }
            assertNull(player.getAssHandlerCompat())
        }
    }

    @Test
    fun standardPlayerStillClosesWithoutAnAssHandler() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        assertNull(player.getAssHandlerCompat())
        assertNull(
            player.createAssMediaSourceFactoryCompat(
                DefaultDataSource.Factory(RuntimeEnvironment.getApplication()),
                DefaultExtractorsFactory(),
            ),
        )
        player.releaseWithAssSupportCompat()
    }

    @Test
    fun cueRenderingKeepsEmbeddedStylesOnlyWhenLibassIsEnabled() {
        val view = PlayerView(RuntimeEnvironment.getApplication())
        val subtitleView = assertNotNull(view.subtitleView)

        view.applySubtitleStyle(SubtitleStyleState.DEFAULT, useLibass = true)
        assertTrue(subtitleView.readEmbeddedStyleFlag("applyEmbeddedStyles"))
        assertTrue(subtitleView.readEmbeddedStyleFlag("applyEmbeddedFontSizes"))

        view.applySubtitleStyle(SubtitleStyleState.DEFAULT, useLibass = false)
        assertFalse(subtitleView.readEmbeddedStyleFlag("applyEmbeddedStyles"))
        assertFalse(subtitleView.readEmbeddedStyleFlag("applyEmbeddedFontSizes"))
    }

    @Test
    fun disablingOverlayCleansBothRenderContainers() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).buildWithAssSupportCompat(
            context,
            renderType = AssRenderType.OVERLAY_CANVAS,
        )
        val view = PlayerView(context)
        try {
            val canvas = assertNotNull(view.findViewById<FrameLayout>(R.id.libass_overlay_container))
            view.syncLibassOverlay(player, enabled = true, renderType = LibassRenderType.OVERLAY_CANVAS)
            assertTrue(canvas.childCount > 0)

            // A mode change used to clean only the newly selected container.
            view.syncLibassOverlay(player, enabled = false, renderType = LibassRenderType.OVERLAY_OPEN_GL)
            assertEquals(0, canvas.childCount)
            assertNull(view.getTag(R.id.libass_overlay_bound_player))
        } finally {
            view.syncLibassOverlay(player, enabled = false, renderType = LibassRenderType.CUES)
            player.releaseWithAssSupportCompat()
        }
    }

    private fun androidx.media3.ui.SubtitleView.readEmbeddedStyleFlag(name: String): Boolean {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(this)
    }
}
