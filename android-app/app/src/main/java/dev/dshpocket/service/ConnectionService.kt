package dev.dshpocket.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.dshpocket.PocketApp
import dev.dshpocket.R
import dev.dshpocket.notify.Notifier

/**
 * Foreground service that keeps the mux WebSocket alive while a task runs.
 *
 * ### Why a foreground service at all
 *
 * The Harness pushes every state change — streaming text, `turn/end`, approval
 * requests — over **one long-lived socket**, not a poll. Android's background
 * restrictions would otherwise freeze the process a few minutes after the user
 * backgrounds the app, the socket would go silent, and the phone would show a
 * status that no longer reflects reality (mid-task).
 *
 * ### The lifecycle trade-off
 *
 * The service is started **only** while a turn is in flight and stopped when it
 * settles (see [dev.dshpocket.MainActivity], which computes `busy` from chat status
 * and session running flags):
 *
 *  - Holding it open permanently would leave an "ongoing" notification in the shade
 *    for no reason and consume battery (the OS keeps the process unthrottled).
 *  - Releasing it too early would let the OS freeze the process mid-turn and strand
 *    the task from the phone's point of view.
 *
 * This service does **no work of its own** — no socket, no wakelock. The socket lives
 * in the ViewModel; the service merely raises the process priority and posts a
 * minimal static notification. It holds no wakelock because the socket's own keepalive
 * pings are sufficient to hold the TCP connection, and because a wakelock would be a
 * battery liability for no added reliability.
 *
 * ### Notification type
 *
 * `FOREGROUND_SERVICE_TYPE_DATA_SYNC` is the appropriate declared type for a
 * background network transport, and matches the `foregroundServiceType` declared in
 * the manifest.
 */
class ConnectionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = Notifier.CHANNEL_CONN

        /** Start the service in the foreground-safe way (required from API 26+). */
        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Request the service be stopped; no-op if it is not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }

    // Binder service: no one binds to it, so this stays a plain started service.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // STICKY: if the system reclaims the process for memory, it is restarted with
        // a null intent, and a turn may still be running — better to come back than to
        // silently die mid-task. The ViewModel re-establishes the socket on the next
        // onStart of the app.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val app = PocketApp.instance
        app.notifier.ensureChannels()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notif_conn_title))
            .setContentText(getString(R.string.notif_conn_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // "ongoing" so it cannot be dismissed by a swipe
            .setSilent(true) // no sound/vibration — this is a background caretaker
            .build()
    }
}