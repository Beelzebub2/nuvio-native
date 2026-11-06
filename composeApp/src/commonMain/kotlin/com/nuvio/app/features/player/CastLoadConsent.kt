package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Consent belongs to one source and receiver, and never survives this panel. */
internal class CastLoadConsent(private val sourceUrl: String) {
    var receiverId: String? by mutableStateOf<String?>(null)
        private set
    var rightsConfirmed: Boolean by mutableStateOf(false)
    var loading: Boolean by mutableStateOf(false)
        private set
    private var active = true
    private var generation = 0

    fun updateReceiver(id: String?) {
        if (receiverId == id) return
        receiverId = id
        rightsConfirmed = false
        loading = false
        generation++
    }

    fun beginLoad(currentReceiverId: String?): Int? {
        if (!active || loading || !rightsConfirmed || currentReceiverId == null ||
            currentReceiverId != receiverId || eligibleCastMimeType(sourceUrl) == null) return null
        loading = true
        return ++generation
    }

    /** A stale success must never pause a different source or close a new panel. */
    fun completeLoad(request: Int, currentReceiverId: String?): Boolean {
        if (!active || !loading || request != generation) return false
        loading = false
        return rightsConfirmed && currentReceiverId != null && currentReceiverId == receiverId
    }

    fun dispose() {
        active = false
        rightsConfirmed = false
        loading = false
        generation++
    }
}
