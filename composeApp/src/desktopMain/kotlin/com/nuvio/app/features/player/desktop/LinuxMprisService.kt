package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * Optional Linux user-session MPRIS service. Bus method handlers run on dbus-java
 * worker threads; all player commands are delegated to the Swing event thread.
 * The existing player snapshot loop owns updates, so no native player polling is added.
 */
internal object LinuxMprisService : LinuxMprisRoot, LinuxMprisPlayer {
    private val log = Logger.withTag("LinuxMpris")
    private val lifecycleLock = Any()
    private val signalWorker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "starlit-mpris-signals").apply { isDaemon = true }
    }

    @Volatile
    private var bus: DBusConnection? = null
    @Volatile
    private var closed = false
    @Volatile
    private var player: BoundPlayer? = null
    @Volatile
    private var playback = LinuxMprisState()
    @Volatile
    private var raiseWindow: (() -> Unit)? = null

    private var nextTrackNumber = 0L

    private class BoundPlayer(
        val controller: NativePlayerController,
        val onStop: () -> Unit,
        val onNext: () -> Unit,
        var trackIdentity: String? = null,
    )

    fun start() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX || closed || bus != null) return
        val environmentAddress = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        val runtimeDirectory = System.getenv("XDG_RUNTIME_DIR")
        val address = linuxMprisSessionBusAddress(environmentAddress, runtimeDirectory)
        if (address == null) {
            log.d { "No Linux session bus address; MPRIS unavailable" }
            return
        }
        if (environmentAddress.isNullOrBlank() &&
            !File(runtimeDirectory.orEmpty(), "bus").exists()
        ) {
            log.d { "No Linux session bus socket; MPRIS unavailable" }
            return
        }

        val opened = runCatching {
            val connection = if (!environmentAddress.isNullOrBlank()) {
                DBusConnectionBuilder.forSessionBus().withShared(false).build()
            } else {
                DBusConnectionBuilder.forAddress(address).withShared(false).build()
            }
            try {
                connection.requestBusName(LinuxMprisBusName)
                connection.exportObject(LinuxMprisObjectPath, this)
                connection
            } catch (error: Exception) {
                runCatching { connection.releaseBusName(LinuxMprisBusName) }
                runCatching { connection.close() }
                throw error
            }
        }.onFailure { error ->
            log.w(error) { "Unable to register Linux MPRIS session service" }
        }.getOrNull() ?: return

        val retained = synchronized(lifecycleLock) {
            if (closed || bus != null) false else {
                bus = opened
                true
            }
        }
        if (!retained) closeConnection(opened)
    }

    fun bindPlayer(controller: NativePlayerController, onStop: () -> Unit, onNext: () -> Unit) {
        if (DesktopHostOs.current != DesktopHostOs.LINUX || closed) return
        player = BoundPlayer(controller, onStop, onNext)
        updateState(LinuxMprisState(trackId = newTrackId(), attached = true))
    }

    fun unbindPlayer(controller: NativePlayerController) {
        if (player?.controller !== controller) return
        player = null
        updateState(LinuxMprisState())
    }

    fun updatePlayer(
        controller: NativePlayerController,
        sourceIdentity: String,
        controls: PlayerControlsState,
        snapshot: PlayerPlaybackSnapshot,
        presence: PresenceSnapshot.Player?,
    ) {
        val bound = player?.takeIf { it.controller === controller } ?: return
        val title = presence?.title?.takeIf(String::isNotBlank)
            ?: controls.title.takeIf(String::isNotBlank)
            ?: ""
        val episodeLabel = presence?.episodeLabel?.takeIf(String::isNotBlank)
            ?: controls.episodeText
        val artworkUrl = linuxMprisPublicArtworkUrl(presence?.posterUrl).orEmpty()
        // One identity per source/title/episode. Never expose the internal source URI
        // to D-Bus metadata, and reject SetPosition for a former track ID.
        val identity = listOf(sourceIdentity, title, episodeLabel).joinToString("\\u0000")
        val previous = playback
        val trackId = if (bound.trackIdentity == null || bound.trackIdentity == identity) {
            previous.trackId
        } else {
            newTrackId()
        }
        bound.trackIdentity = identity
        updateState(
            LinuxMprisState(
                trackId = trackId,
                title = title,
                episodeLabel = episodeLabel,
                artworkUrl = artworkUrl,
                attached = true,
                isLoading = snapshot.isLoading,
                isPlaying = snapshot.isPlaying,
                isEnded = snapshot.isEnded,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                durationMs = snapshot.durationMs.coerceAtLeast(0L),
                rate = snapshot.playbackSpeed.toDouble().takeIf {
                    it.isFinite() && it > 0.0
                } ?: 1.0,
                volume = controller.getRemoteVolume().toDouble().takeIf {
                    it.isFinite()
                }?.coerceIn(0.0, 2.0) ?: 1.0,
                nextEpisodePlayable = controls.nextEpisodePlayable,
            ),
        )
    }

    fun setWindowRaiseHandler(onRaise: (() -> Unit)?) {
        raiseWindow = onRaise
    }

    fun shutdown() {
        val connection = synchronized(lifecycleLock) {
            closed = true
            val current = bus
            bus = null
            current
        }
        player = null
        raiseWindow = null
        playback = LinuxMprisState()
        signalWorker.shutdown()
        connection?.let(::closeConnection)
    }

    private fun closeConnection(connection: DBusConnection) {
        runCatching { connection.unExportObject(LinuxMprisObjectPath) }
        runCatching { connection.releaseBusName(LinuxMprisBusName) }
        runCatching { connection.close() }
    }

    private fun newTrackId(): String =
        "$LinuxMprisObjectPath/Track/track_${++nextTrackNumber}"

    private fun updateState(next: LinuxMprisState) {
        val previous = playback
        playback = next
        val changed = linuxMprisChangedStateFields(previous, next)
        if (changed.isEmpty()) return
        signalAsync {
            val properties = linkedMapOf<String, Variant<*>>()
            changed.forEach { key ->
                when (key) {
                    "PlaybackStatus" -> properties[key] = Variant(next.playbackStatus)
                    "Metadata" -> properties[key] = Variant(metadataFor(next), "a{sv}")
                    "Rate" -> properties[key] = Variant(next.rate)
                    "Volume" -> properties[key] = Variant(next.volume)
                    "CanPlay" -> properties[key] = Variant(next.canPlay)
                    "CanPause" -> properties[key] = Variant(next.canPause)
                    "CanSeek" -> properties[key] = Variant(next.canSeek)
                    "CanGoNext" -> properties[key] = Variant(next.canGoNext)
                }
            }
            bus?.sendMessage(
                Properties.PropertiesChanged(
                    LinuxMprisObjectPath,
                    "org.mpris.MediaPlayer2.Player",
                    properties,
                    emptyList(),
                ),
            )
        }
    }

    private fun signalAsync(block: () -> Unit) {
        if (bus == null || closed) return
        runCatching {
            signalWorker.execute {
                runCatching(block).onFailure { error ->
                    log.w(error) { "Unable to publish MPRIS property change" }
                }
            }
        }
    }

    private fun metadataFor(state: LinuxMprisState): Map<String, Variant<*>> {
        if (!state.attached) return emptyMap()
        return linkedMapOf<String, Variant<*>>().apply {
            put("mpris:trackid", Variant(DBusPath(state.trackId)))
            put("xesam:title", Variant(state.title.ifBlank { "Starlit" }))
            if (state.episodeLabel.isNotBlank()) {
                put("xesam:album", Variant(state.episodeLabel))
            }
            if (state.artworkUrl.isNotBlank()) {
                put("mpris:artUrl", Variant(state.artworkUrl))
            }
            if (state.durationMs > 0L) {
                put("mpris:length", Variant(state.durationMs.millisecondsToMicroseconds()))
            }
        }
    }

    override fun isRemote(): Boolean = false

    override fun getObjectPath(): String = LinuxMprisObjectPath

    override fun Raise() {
        SwingUtilities.invokeLater { raiseWindow?.invoke() }
    }

    override fun Quit() = Unit // The application does not grant remote shutdown.
    override fun getCanQuit(): Boolean = false
    override fun getCanRaise(): Boolean = raiseWindow != null
    override fun getHasTrackList(): Boolean = false
    override fun getIdentity(): String = "Starlit"
    override fun getDesktopEntry(): String = "starlit"
    override fun getSupportedUriSchemes(): List<String> = emptyList()
    override fun getSupportedMimeTypes(): List<String> = emptyList()

    override fun Next() {
        onEventThread { bound ->
            if (playback.canGoNext) bound.onNext()
        }
    }

    // Switching to an earlier episode can require an interactive source chooser.
    override fun Previous() = Unit

    override fun Pause() {
        onEventThread { bound ->
            if (playback.canPause) bound.controller.pause()
        }
    }

    override fun PlayPause() {
        onEventThread { bound ->
            when {
                playback.isPlaying && playback.canPause -> bound.controller.pause()
                playback.canPlay -> bound.controller.play()
            }
        }
    }

    override fun Stop() {
        onEventThread { bound -> bound.onStop() }
    }

    override fun Play() {
        onEventThread { bound ->
            if (playback.canPlay) bound.controller.play()
        }
    }

    override fun Seek(offset: Long) = postSeek(offset, relative = true)

    override fun SetPosition(trackId: DBusPath, position: Long) {
        postSeek(position, relative = false, trackId = trackId.path)
    }

    override fun OpenUri(uri: String) = Unit // No external source schemes are advertised.

    private fun postSeek(positionUs: Long, relative: Boolean, trackId: String? = null) {
        onEventThread { bound ->
            val requested = linuxMprisSeekTargetMs(
                state = playback,
                microseconds = positionUs,
                relative = relative,
                trackId = trackId,
            ) ?: return@onEventThread
            if (bound.controller.trySeekTo(requested)) {
                playback = playback.copy(positionMs = requested)
                signalAsync {
                    bus?.sendMessage(
                        LinuxMprisPlayer.Seeked(
                            LinuxMprisObjectPath,
                            requested.millisecondsToMicroseconds(),
                        ),
                    )
                }
            }
        }
    }

    override fun getPlaybackStatus(): String = playback.playbackStatus
    override fun getRate(): Double = playback.rate
    override fun getMinimumRate(): Double = 0.5
    override fun getMaximumRate(): Double = 2.0
    override fun getMetadata(): Map<String, Variant<*>> = metadataFor(playback)
    override fun getVolume(): Double = playback.volume
    override fun getPosition(): Long = playback.positionUs
    override fun getCanGoNext(): Boolean = playback.canGoNext
    override fun getCanGoPrevious(): Boolean = false
    override fun getCanPlay(): Boolean = playback.canPlay
    override fun getCanPause(): Boolean = playback.canPause
    override fun getCanSeek(): Boolean = playback.canSeek
    override fun getCanControl(): Boolean = true

    override fun setRate(value: Double) {
        if (!value.isFinite()) return
        if (value == 0.0) {
            Pause()
            return
        }
        if (value !in getMinimumRate()..getMaximumRate()) return
        onEventThread { bound ->
            if (playback.canPlay) {
                bound.controller.setPlaybackSpeed(value.toFloat())
                updateState(playback.copy(rate = value))
            }
        }
    }

    override fun setVolume(value: Double) {
        if (!value.isFinite()) return
        onEventThread { bound ->
            if (playback.canPlay) {
                val level = value.coerceIn(0.0, 2.0)
                bound.controller.setRemoteVolume(level.toFloat())
                updateState(playback.copy(volume = level))
            }
        }
    }

    private inline fun onEventThread(crossinline action: (BoundPlayer) -> Unit) {
        if (DesktopHostOs.current != DesktopHostOs.LINUX || closed) return
        SwingUtilities.invokeLater {
            player?.let { bound -> if (!closed) action(bound) }
        }
    }
}
