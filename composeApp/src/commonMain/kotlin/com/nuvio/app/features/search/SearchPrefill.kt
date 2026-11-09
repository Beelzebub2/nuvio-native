package com.nuvio.app.features.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A one-shot, local-only title search request. It never alters watch history. */
internal object SearchPrefill {
    private val mutable = MutableStateFlow<String?>(null)
    val pending = mutable.asStateFlow()

    fun request(title: String) {
        mutable.value = title.trim().take(180).takeIf(String::isNotEmpty)
    }

    fun consume(title: String) {
        if (mutable.value == title) mutable.value = null
    }
}
