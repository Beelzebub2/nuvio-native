package com.nuvio.app.core.windows

import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

/** Enumerates installed display adapters in-process through User32, without a shell. */
internal object WindowsDisplayAdapters {
    private const val MAX_ADAPTERS = 64

    // WinUser.h: DISPLAY_DEVICEW (840 bytes, including the null-terminated WCHAR arrays).
    @Structure.FieldOrder("cb", "deviceName", "deviceString", "stateFlags", "deviceId", "deviceKey")
    internal class DisplayDevice : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var deviceName = CharArray(32)
        @JvmField var deviceString = CharArray(128)
        @JvmField var stateFlags: Int = 0
        @JvmField var deviceId = CharArray(128)
        @JvmField var deviceKey = CharArray(128)
    }

    private interface User32 : StdCallLibrary {
        fun EnumDisplayDevicesW(
            deviceName: WString?,
            deviceNumber: Int,
            device: DisplayDevice,
            flags: Int,
        ): Boolean
    }

    private val user32 by lazy { loadSystemLibrary("user32", User32::class.java) }

    /** Returns null when native enumeration fails, preserving the distinction from no matches. */
    fun names(): List<String>? = runCatching {
        buildList {
            for (index in 0 until MAX_ADAPTERS) {
                val adapter = DisplayDevice().apply { cb = size() }
                if (!user32.EnumDisplayDevicesW(null, index, adapter, 0)) break
                val name = adapter.deviceString.toNullTerminatedName()
                if (name.isNotEmpty()) add(name)
            }
        }.distinct()
    }.getOrNull()
}

internal fun CharArray.toNullTerminatedName(): String =
    concatToString().substringBefore('\u0000').trim()
