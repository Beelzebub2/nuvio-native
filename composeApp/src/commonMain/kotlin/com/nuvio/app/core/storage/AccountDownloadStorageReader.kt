package com.nuvio.app.core.storage

/** Reads device-local download records for inactive profiles before an account storage wipe. */
internal expect object AccountDownloadStorageReader {
    fun loadPayload(profileId: Int): String?
    /** Only an explicitly chosen destination, without creating an implicit default directory. */
    fun loadSelectedFolder(): String?
    /** Check before consuming a saved folder-only backup. */
    fun isUsableFolder(path: String): Boolean
}
