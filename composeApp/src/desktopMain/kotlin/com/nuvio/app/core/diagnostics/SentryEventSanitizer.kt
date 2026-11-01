package com.nuvio.app.core.diagnostics

import io.sentry.SentryEvent

internal object SentryEventSanitizer {
    val ignoredIssueText = listOf(
        "Large HTTP payload",
        "File IO on Main Thread",
    )

    fun sanitize(event: SentryEvent): SentryEvent? {
        val drop = shouldDrop(event)
        event.request = null
        event.user = null
        event.serverName = null
        event.message = null
        event.transaction = null
        event.exceptions?.forEach { exception -> exception.value = null }
        return if (drop) null else event
    }

    private fun shouldDrop(event: SentryEvent): Boolean =
        eventText(event).any { text ->
            ignoredIssueText.any { ignored ->
                text.contains(ignored, ignoreCase = true)
            }
        }

    private fun eventText(event: SentryEvent): List<String> {
        val values = mutableListOf<String>()
        event.message?.formatted?.let(values::add)
        event.message?.message?.let(values::add)
        event.logger?.let(values::add)
        event.transaction?.let(values::add)
        event.exceptions?.forEach { exception ->
            exception.type?.let(values::add)
            exception.value?.let(values::add)
        }
        return values
    }
}
