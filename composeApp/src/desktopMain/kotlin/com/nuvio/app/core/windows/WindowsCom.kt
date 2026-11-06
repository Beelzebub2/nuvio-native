package com.nuvio.app.core.windows

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * Loads a Windows DLL from System32 by absolute path, so a same-named DLL next to
 * the app or in the working directory can never be picked up instead.
 */
internal fun <T : StdCallLibrary> loadSystemLibrary(name: String, type: Class<T>): T =
    Native.load(File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\$name.dll").absolutePath, type)

internal class ComException(val operation: String, val hresult: Int) :
    RuntimeException("$operation failed (0x%08X)".format(hresult))

internal fun checkHresult(operation: String, hresult: Int) {
    if (hresult < 0) throw ComException(operation, hresult)
}

/**
 * Minimal COM access over JNA. Starlit talks to Windows components in its own
 * process instead of launching hidden PowerShell: antivirus engines score hidden
 * or encoded PowerShell started by an app as trojan behaviour.
 */
internal object WindowsCom {
    private const val COINIT_MULTITHREADED = 0x0
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val CLSCTX_LOCAL_SERVER = 0x4

    const val VT_EMPTY = 0
    const val VT_NULL = 1
    const val VT_UNKNOWN = 13
    const val VT_DISPATCH = 9
    val variantSize: Long = if (Native.POINTER_SIZE == 8) 24L else 16L
    val variantValueOffset: Long = 8L

    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, result: PointerByReference): Int
    }

    private interface OleAut32 : StdCallLibrary {
        fun SysAllocString(value: WString): Pointer?
        fun SysFreeString(value: Pointer?)
        fun VariantClear(variant: Pointer): Int
    }

    private val ole32 by lazy { loadSystemLibrary("ole32", Ole32::class.java) }
    private val oleAut32 by lazy { loadSystemLibrary("oleaut32", OleAut32::class.java) }

    /** Runs [block] with COM initialized on the current thread, balancing the init it made. */
    fun <T> withApartment(singleThreaded: Boolean = false, block: () -> T): T {
        val hr = ole32.CoInitializeEx(null, if (singleThreaded) COINIT_APARTMENTTHREADED else COINIT_MULTITHREADED)
        // A thread that already joined another apartment can still make the calls.
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) throw ComException("CoInitializeEx", hr)
        try {
            return block()
        } finally {
            if (hr >= 0) ole32.CoUninitialize()
        }
    }

    fun create(clsid: String, iid: String): ComObject {
        val out = PointerByReference()
        val hr = ole32.CoCreateInstance(guid(clsid), null, CLSCTX_INPROC_SERVER or CLSCTX_LOCAL_SERVER, guid(iid), out)
        checkHresult("CoCreateInstance $clsid", hr)
        return ComObject(checkNotNull(out.value) { "CoCreateInstance returned no object" })
    }

    fun <T> withBstr(value: String, block: (Pointer) -> T): T {
        val bstr = oleAut32.SysAllocString(WString(value)) ?: throw OutOfMemoryError("SysAllocString")
        try {
            return block(bstr)
        } finally {
            oleAut32.SysFreeString(bstr)
        }
    }

    /** Reads and frees a BSTR the callee handed over. */
    fun takeBstr(bstr: Pointer?): String? {
        if (bstr == null) return null
        try {
            return bstr.getWideString(0)
        } finally {
            oleAut32.SysFreeString(bstr)
        }
    }

    fun clearVariant(variant: Pointer) {
        oleAut32.VariantClear(variant)
    }

    /** A GUID in its in-memory layout, from the usual "8-4-4-4-12" text form. */
    fun guid(text: String): Memory {
        val hex = text.trim('{', '}').replace("-", "")
        require(hex.length == 32) { "Invalid GUID: $text" }
        return Memory(16).apply {
            setInt(0, hex.substring(0, 8).toLong(16).toInt())
            setShort(4, hex.substring(8, 12).toInt(16).toShort())
            setShort(6, hex.substring(12, 16).toInt(16).toShort())
            for (index in 0 until 8) {
                setByte(8L + index, hex.substring(16 + index * 2, 18 + index * 2).toInt(16).toByte())
            }
        }
    }
}

/** An owned COM interface pointer. Vtable indexes come from the Windows SDK headers. */
internal class ComObject(pointer: Pointer) : AutoCloseable {
    private var current: Pointer? = pointer
    val pointer: Pointer get() = checkNotNull(current) { "COM object already released" }

    fun call(index: Int, vararg args: Any?): Int = invoke(pointer, index, args)

    fun queryInterface(iid: String): ComObject? = queryInterface(pointer, iid)

    fun getString(index: Int, name: String): String? {
        val out = PointerByReference()
        val hr = call(index, out)
        if (hr < 0) {
            WindowsCom.takeBstr(out.value)
            throw ComException(name, hr)
        }
        return WindowsCom.takeBstr(out.value)
    }

    fun getInt(index: Int, name: String, vararg args: Any?): Int {
        val out = IntByReference()
        checkHresult(name, call(index, *args, out))
        return out.value
    }

    fun getBoolean(index: Int, name: String, vararg args: Any?): Boolean {
        // VARIANT_BOOL is 16 bits; keep a full word so a sloppy callee can't overrun.
        val out = Memory(4).apply { setInt(0, 0) }
        checkHresult(name, call(index, *args, out))
        return out.getShort(0).toInt() != 0
    }

    fun getObject(index: Int, name: String): ComObject {
        val out = PointerByReference()
        checkHresult(name, call(index, out))
        return ComObject(checkNotNull(out.value) { "$name returned no object" })
    }

    fun putString(index: Int, name: String, value: String) {
        WindowsCom.withBstr(value) { checkHresult(name, call(index, it)) }
    }

    fun putInt(index: Int, name: String, value: Int) = checkHresult(name, call(index, value))

    fun putBoolean(index: Int, name: String, value: Boolean) =
        checkHresult(name, call(index, if (value) (-1).toShort() else 0.toShort()))

    override fun close() {
        val pointer = current ?: return
        current = null
        invoke(pointer, 2, emptyArray())
    }

    companion object {
        private fun invoke(self: Pointer, index: Int, args: Array<out Any?>): Int {
            val vtable = self.getPointer(0)
            val function = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            return function.invokeInt(arrayOf<Any?>(self, *args))
        }

        /** QueryInterface on a pointer this code does not own (for example inside a VARIANT). */
        fun queryInterface(self: Pointer, iid: String): ComObject? {
            val out = PointerByReference()
            val hr = invoke(self, 0, arrayOf(WindowsCom.guid(iid), out))
            return if (hr >= 0 && out.value != null) ComObject(out.value) else null
        }
    }
}
