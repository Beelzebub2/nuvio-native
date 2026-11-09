package com.nuvio.app

/** Whether this process belongs to the dedicated Android TV package. */
internal object AndroidTelevisionRuntime {
    @Volatile var active: Boolean = false
}
