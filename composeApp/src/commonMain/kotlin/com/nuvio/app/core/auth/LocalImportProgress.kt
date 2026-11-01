package com.nuvio.app.core.auth

internal enum class LocalImportStage {
    Preparing, Preferences, AddonsAndPlugins, Downloads, LibraryAndHistory, CollectionsAndLists,
    SyncSettings, SyncAddonsAndPlugins, SyncLibraryAndHistory,
}

internal enum class LocalImportOutcome { Synced, Pending, Failed }

/** Counts only: never put imported settings, credentials or raw failures in the dialog state. */
internal data class LocalImportSummary(
    val addons: Int = 0,
    val plugins: Int = 0,
    val downloads: Int = 0,
    val library: Int = 0,
    val history: Int = 0,
    val collections: Int = 0,
    val savedLists: Int = 0,
)

internal data class LocalImportProgress(
    val scopeKey: String,
    val stage: LocalImportStage = LocalImportStage.Preparing,
    val summary: LocalImportSummary? = null,
    val outcome: LocalImportOutcome? = null,
) {
    val isRunning: Boolean get() = outcome == null
}

internal typealias LocalImportReporter = (LocalImportStage, LocalImportSummary?) -> Unit
