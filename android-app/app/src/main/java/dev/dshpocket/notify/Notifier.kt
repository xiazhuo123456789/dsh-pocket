package dev.dshpocket.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dshpocket.MainActivity
import dev.dshpocket.R

/**
 * All user-visible notifications. Two channels: task outcomes (done/failed/
 * approval-needed) and connection health. Approval notifications carry inline
 * actions so the user can answer without opening the app, but the decision is
 * still delivered through the gateway — the action only pre-loads the intent.
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_TASK = "task_status"
        const val CHANNEL_CONN = "connection"
        const val ID_CONNECTION = 1001
        const val ID_TASK_BASE = 2000

        const val ACTION_APPROVE = "dev.dshpocket.APPROVE"
        const val ACTION_REJECT = "dev.dshpocket.REJECT"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SESSION_ID = "session_id"
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASK,
                context.getString(R.string.notif_channel_task),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_task_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONN,
                context.getString(R.string.notif_channel_conn),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_conn_desc) }
        )
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun contentIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun taskFinished(sessionId: String, title: String, success: Boolean) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (success) "任务已完成" else "任务失败")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(sessionId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(ID_TASK_BASE + (sessionId.hashCode() and 0xFF), n)
    }

    fun approvalNeeded(sessionId: String, eventId: String, description: String) {
        if (!canPost()) return
        val approve = PendingIntent.getBroadcast(
            context, eventId.hashCode(), approvalIntent(eventId, sessionId, ACTION_APPROVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getBroadcast(
            context, eventId.hashCode() + 1, approvalIntent(eventId, sessionId, ACTION_REJECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(sessionId))
            .addAction(0, context.getString(R.string.approval_reject), reject)
            .addAction(0, context.getString(R.string.approval_allow_once), approve)
            .build()
        NotificationManagerCompat.from(context)
            .notify(ID_TASK_BASE + (eventId.hashCode() and 0xFF), n)
    }

    private fun approvalIntent(eventId: String, sessionId: String, action: String) =
        Intent(context, ApprovalActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }

    fun connectionLost(reason: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_CONN)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.status_disconnected))
            .setContentText(reason)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(null))
            .build()
        NotificationManagerCompat.from(context).notify(ID_CONNECTION, n)
    }

    /**
     * An approval action could not be delivered because the process that owned
     * the `$events` subscription is gone. Saying so is better than a button that
     * appears to work.
     */
    fun approvalExpired() {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(context.getString(R.string.approval_expired))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(null))
            .build()
        NotificationManagerCompat.from(context).notify(ID_TASK_BASE + 999, n)
    }

    fun cancelConnection() {
        NotificationManagerCompat.from(context).cancel(ID_CONNECTION)
    }
}
