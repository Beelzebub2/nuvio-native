package com.nuvio.app.features.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    override fun discover(onFound: (RemoteDesktop) -> Unit, onFinished: (String?) -> Unit) {
        val ctx = context
        val bt = adapter()
        if (ctx == null || bt == null) {
            onFinished("Bluetooth is unavailable on this phone.")
            return
        }
        if (!permissionsGranted()) {
            onFinished("Allow Nearby devices permission to search for Bluetooth PCs.")
            return
        }
        if (!bt.isEnabled) {
            onFinished("Enable Bluetooth on your phone and PC first.")
            return
        }
        stop()
        var finished = false
        // Only inquiry results count as nearby. Android's bondedDevices contains
        // PCs that may have been turned off or out of range for months.
        fun finish(failure: String?) {
            if (finished) return
            finished = true
            onFinished(failure)
        }
        fun add(device: BluetoothDevice?, discoveredName: String?) {
            if (device == null) return
            val address = runCatching { device.address }.getOrNull() ?: return
            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { discoveredName.orEmpty() }
            val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            val savedPc = DesktopRemote.remembered.value.any {
                it.bluetooth && it.address.equals(address, ignoreCase = true)
            }
            val serviceAdvertised = runCatching {
                device.uuids?.any { it.uuid.toString().equals(REMOTE_BLUETOOTH_SERVICE, ignoreCase = true) }
            }.getOrDefault(false) == true
            // Legacy Windows adapters sometimes omit the Bluetooth class/name.
            // Accept an actually discovered, OS-bonded unknown-class device as a
            // candidate, but never add every bonded device without a radio hit.
            val bondedUnknownClass = (majorClass == null ||
                majorClass == BluetoothClass.Device.Major.UNCATEGORIZED) &&
                runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            if (!isBluetoothPcCandidate(
                    name, majorClass == BluetoothClass.Device.Major.COMPUTER,
                    savedPc, serviceAdvertised, bondedUnknownClass,
                )) return
            onFound(RemoteDesktop(address, 0, name.ifBlank { "Bluetooth PC" }, bluetooth = true))
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        }
                        runCatching { add(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME)) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stop()
                        finish(null)
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
                finish("Bluetooth discovery could not start. Check phone Bluetooth settings.")
            }
        } catch (_: Exception) {
            stop()
            finish("Bluetooth scan unavailable. Check Nearby devices permission.")
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
        val service = UUID.fromString(REMOTE_BLUETOOTH_SERVICE)
        try {
            return@withContext connectSocket(device, service, insecure = false)
        } catch (secureError: IOException) {
            currentCoroutineContext().ensureActive()
            // Secure RFCOMM is preferred. Legacy Bluetooth controllers may
            // support only a non-authenticated RFCOMM link; the Starlit
            // connection still requires its own encrypted, verified handshake.
            check(device.bondState == BluetoothDevice.BOND_BONDED) {
                "The PC is no longer paired in Android Bluetooth settings."
            }
            try {
                return@withContext connectSocket(device, service, insecure = true)
            } catch (compatibilityError: IOException) {
                compatibilityError.addSuppressed(secureError)
                throw RemoteBluetoothCompatibilityException(compatibilityError)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectSocket(
        device: BluetoothDevice, service: UUID, insecure: Boolean,
    ): RemoteBluetoothConnection {
        val socket: BluetoothSocket = if (insecure)
            device.createInsecureRfcommSocketToServiceRecord(service)
        else device.createRfcommSocketToServiceRecord(service)
        val watchdog = CoroutineScope(Dispatchers.IO).launch {
            delay(12_000)
            runCatching { socket.close() }
        }
        try {
            socket.connect()
            return RemoteBluetoothConnection(socket.inputStream, socket.outputStream, device.name ?: device.address) {
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
