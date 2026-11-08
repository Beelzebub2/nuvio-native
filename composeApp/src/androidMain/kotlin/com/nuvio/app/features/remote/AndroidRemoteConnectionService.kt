package com.nuvio.app.features.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.MainActivity
import com.nuvio.app.R
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StarlitRemoteService"
private const val CHANNEL_ID = "starlit_pc_remote"
private const val NOTIFICATION_ID = 0x5253
private const val ACTION_KEEP_CONNECTED = "com.nuvio.app.remote.KEEP_CONNECTED"
private const val ACTION_DISCONNECT = "com.nuvio.app.remote.DISCONNECT"
internal const val ACTION_OPEN_PC_REMOTE = "com.nuvio.app.remote.OPEN_CONTROLLER"

/** A user-visible foreground service keeps the already-authenticated phone socket alive. */
class AndroidRemoteConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            DesktopRemote.transport.disconnect()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_KEEP_CONNECTED || !AndroidRemoteConnectionLifetime.isRequested) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return try {
            ensureChannel()
            // The type comes from the merged Android manifest (connectedDevice).
            startForeground(NOTIFICATION_ID, remoteNotification(this))
            START_NOT_STICKY // Never create a new authenticated session after process death.
        } catch (error: Exception) {
            Log.w(TAG, "Unable to keep PC control active in background", error)
            AndroidRemoteConnectionLifetime.serviceUnavailable()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.remote_service_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}

/** The connection, not the controller menu or Activity, owns the service lifetime. */
internal object AndroidRemoteConnectionLifetime : RemoteConnectionLifetime {
    @Volatile private var appContext: Context? = null
    private val requested = AtomicBoolean(false)
    val isRequested: Boolean get() = requested.get()
    override val retainWhenUiDetached: Boolean get() = requested.get()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun connecting() {
        val context = appContext ?: return
        if (!requested.compareAndSet(false, true)) return
        val intent = Intent(context, AndroidRemoteConnectionService::class.java)
            .setAction(ACTION_KEEP_CONNECTED)
        val started = runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { error ->
            Log.w(TAG, "Could not start PC remote connected-device service", error)
        }.isSuccess
        if (!started) requested.set(false)
    }

    override fun authenticated(host: Boolean) {
        if (host || !requested.get()) return
        val context = appContext ?: return
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, remoteNotification(context))
        }.onFailure { Log.w(TAG, "Could not update PC remote notification", it) }
    }

    override fun disconnected() {
        requested.set(false)
        appContext?.let { context ->
            runCatching {
                context.stopService(Intent(context, AndroidRemoteConnectionService::class.java))
            }.onFailure { Log.w(TAG, "Could not stop PC remote service", it) }
        }
    }

    fun serviceUnavailable() {
        requested.set(false)
    }
}

private fun remoteNotification(context: Context): Notification {
    val openIntent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .setAction(ACTION_OPEN_PC_REMOTE)
    val open = PendingIntent.getActivity(
        context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stop = PendingIntent.getService(
        context, 1,
        Intent(context, AndroidRemoteConnectionService::class.java).setAction(ACTION_DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val connected = DesktopRemote.connected.value
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(context.getString(R.string.remote_service_title))
        .setContentText(context.getString(
            if (connected) R.string.remote_service_connected else R.string.remote_service_connecting,
        ))
        .setContentIntent(open)
        .addAction(0, context.getString(R.string.remote_service_disconnect), stop)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
