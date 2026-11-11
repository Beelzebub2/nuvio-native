package com.nuvio.app

/** Resolve TV identity before any background service or update checker starts. */
internal fun isTelevisionAppPackage(packageName: String): Boolean =
    packageName == "com.starlit.tv" || packageName == "com.starlit.tv.debug"

/** Whether this process belongs to the dedicated Android TV package. */
internal object AndroidTelevisionRuntime {
    @Volatile var active: Boolean = false

    fun initialize(packageName: String) {
        active = isTelevisionAppPackage(packageName)
    }
}
