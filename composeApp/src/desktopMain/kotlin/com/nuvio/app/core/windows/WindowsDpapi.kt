package com.nuvio.app.core.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows DPAPI (CryptProtectData) for the current Windows user, called in-process.
 * Output is the same blob .NET's ProtectedData produced, so values sealed by older
 * Starlit builds still open.
 */
internal object WindowsDpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(
            dataIn: DataBlob, description: WString?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, dataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            dataIn: DataBlob, description: Pointer?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, dataOut: DataBlob,
        ): Boolean
    }

    private interface Kernel32 : StdCallLibrary {
        fun LocalFree(memory: Pointer?): Pointer?
    }

    private val crypt32 by lazy { loadSystemLibrary("crypt32", Crypt32::class.java) }
    private val kernel32 by lazy { loadSystemLibrary("kernel32", Kernel32::class.java) }

    fun protect(plaintext: ByteArray): ByteArray = transform(plaintext) { input, output ->
        crypt32.CryptProtectData(input, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
    }

    fun unprotect(sealed: ByteArray): ByteArray = transform(sealed) { input, output ->
        crypt32.CryptUnprotectData(input, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
    }

    private fun transform(data: ByteArray, operation: (DataBlob, DataBlob) -> Boolean): ByteArray {
        val buffer = Memory(maxOf(1, data.size).toLong())
        buffer.write(0, data, 0, data.size)
        val input = DataBlob().apply {
            cbData = data.size
            pbData = buffer
        }
        val output = DataBlob()
        try {
            if (!operation(input, output)) {
                error("Windows data protection failed (" + Native.getLastError() + ")")
            }
            output.read()
            if (output.cbData == 0) return ByteArray(0)
            val result = output.pbData ?: error("Windows data protection returned no data")
            return result.getByteArray(0, output.cbData).also {
                // Don't leave plaintext or tokens behind in freed native memory.
                result.setMemory(0, output.cbData.toLong(), 0)
            }
        } finally {
            buffer.clear()
            output.pbData?.let { kernel32.LocalFree(it) }
        }
    }
}
