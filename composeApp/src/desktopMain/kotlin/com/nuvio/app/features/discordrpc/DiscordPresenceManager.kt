package com.nuvio.app.features.discordrpc

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.settings.DiscordRichPresenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private class DiscordDisconnected : Exception()

private const val ReconnectDelayMs = 15_000L

internal object DiscordPresenceManager {
    private val log = Logger.withTag("DiscordPresenceManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DiscordIpcClient(DiscordConfig.CLIENT_ID)
    private var syncJob: Job? = null
    private var lastActivity: DiscordActivity? = null

    fun start() {
        if (DiscordConfig.CLIENT_ID.isBlank()) return
        DiscordRichPresenceRepository.ensureLoaded()
        scope.launch {
            DiscordRichPresenceRepository.enabled.collectLatest { enabled ->
                if (enabled) startSync() else stopSync()
            }
        }
    }

    fun shutdown() {
        runBlocking { stopSync() }
    }

    private suspend fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                if (client.connect()) {
                    lastActivity = null
                    try {
                        AppPresenceState.current.collectLatest { snapshot ->
                            val activity = snapshot?.toDiscordActivity()
                            if (activity == lastActivity) return@collectLatest
                            if (client.setActivity(activity)) {
                                lastActivity = activity
                            } else {
                                throw DiscordDisconnected()
                            }
                        }
                    } catch (e: DiscordDisconnected) {
                        log.d { "Discord IPC disconnected, retrying" }
                    }
                }
                delay(ReconnectDelayMs)
            }
        }
    }

    private suspend fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        if (lastActivity != null) client.setActivity(null)
        lastActivity = null
        client.disconnect()
    }
}

private fun PresenceSnapshot.toDiscordActivity(): DiscordActivity = when (this) {
    is PresenceSnapshot.Tab -> DiscordActivity(details = "Browsing ${tab.name}")
    is PresenceSnapshot.Details -> DiscordActivity(details = "Viewing $title")
    is PresenceSnapshot.Player -> DiscordActivity(
        details = title,
        state = episodeLabel ?: if (isPlaying) "Watching" else "Paused",
        timestamps = if (isPlaying) {
            DiscordActivityTimestamps(start = System.currentTimeMillis() - positionMs)
        } else {
            null
        },
        assets = posterUrl?.let { DiscordActivityAssets(largeImage = it, largeText = title) },
    )
}
