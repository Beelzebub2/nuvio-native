package com.nuvio.app.features.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android Bluetooth Classic RFCOMM. No commands travel outside the encrypted remote session. */
internal object AndroidBluetoothRemoteClient : RemoteBluetoothClient {
    private var context: Context? = null
    private var discoveryReceiver: BroadcastReceiver? = null

    fun initialize(applicationContext: Context) {
        context = applicationContext.applicationContext
    }

    private fun adapter(): BluetoothAdapter? =
        (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override val supported: Boolean get() = adapter() != null

    private fun permissionsGranted(): Boolean {
        val ctx = context ?: return false
        val required = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    override fun discover(onFound: (RemoteDesktop) -> Unit, onFailure: (String) -> Unit) {
        val ctx = context
        val bt = adapter()
        if (ctx == null || bt == null) {
            onFailure("Bluetooth is unavailable on this phone.")
            return
        }
        if (!permissionsGranted()) {
            onFailure("Allow Nearby devices permission to search for Bluetooth PCs.")
            return
        }
        if (!bt.isEnabled) {
            onFailure("Enable Bluetooth on your phone and PC first.")
            return
        }
        stop()
        var found = false
        fun add(device: BluetoothDevice?) {
            if (device == null) return
            val name = runCatching { device.name }.getOrNull().orEmpty()
            val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            if (majorClass != BluetoothClass.Device.Major.COMPUTER &&
                !name.contains("desktop", true) && !name.contains("laptop", true) &&
                !name.contains("starlit", true) && !name.contains("macbook", true)) return
            found = true
            onFound(RemoteDesktop(device.address, 0, name.ifBlank { "Bluetooth PC" }, bluetooth = true))
        }
        runCatching { bt.bondedDevices.forEach(::add) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        runCatching { add(device) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (!found) onFailure("No Bluetooth PCs found. Pair with the PC in Bluetooth settings and try again.")
                        stop()
                    }
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            discoveryReceiver = receiver
            if (!bt.startDiscovery()) {
                stop()
                if (!found) onFailure("Bluetooth discovery could not start. Check phone Bluetooth settings.")
            }
        } catch (_: Exception) {
            stop()
            if (!found) onFailure("Bluetooth scan unavailable. Check Nearby devices permission.")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String): RemoteBluetoothConnection = withContext(Dispatchers.IO) {
        check(permissionsGranted()) { "Bluetooth permission was revoked." }
        require(BluetoothAdapter.checkBluetoothAddress(address)) { "Invalid Bluetooth address." }
        val bt = checkNotNull(adapter()) { "Bluetooth is unavailable." }
        check(bt.isEnabled) { "Bluetooth is disabled." }
        runCatching { bt.cancelDiscovery() }
        val device = bt.getRemoteDevice(address)
        require(device.bondState == BluetoothDevice.BOND_BONDED) {
            "Pair the PC in Android Bluetooth settings before connecting."
        }
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(REMOTE_BLUETOOTH_SERVICE))
        val watchdog = CoroutineScope(Dispatchers.IO).launch {
            delay(15_000)
            runCatching { socket.close() }
        }
        try {
            socket.connect()
            RemoteBluetoothConnection(socket.inputStream, socket.outputStream, device.name ?: address) {
                runCatching { socket.close() }
            }
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        } finally {
            watchdog.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        discoveryReceiver?.let { receiver ->
            context?.let { ctx -> runCatching { ctx.unregisterReceiver(receiver) } }
        }
        discoveryReceiver = null
        if (permissionsGranted()) runCatching { adapter()?.cancelDiscovery() }
    }
}
