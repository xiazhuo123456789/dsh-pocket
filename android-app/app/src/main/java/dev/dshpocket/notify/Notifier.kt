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
 * All user-visible notifications.
 *
 * ### Why two channels
 *
 * `task_status` and `connection` are separated so a user can silence "the link
 * dropped" without also silencing "the desktop is waiting for your approval".
 * The second category is the one that actually matters: a remote turn blocked on
 * an approval will sit there indefinitely, so missing that notification is the
 * difference between a working app and a stuck one.
 *
 * ### Why approval actions are broadcast receivers, not activities
 *
 * Tapping "allow once" from the notification shade must not require opening the
 * app. The action therefore targets [ApprovalActionReceiver], which asks
 * `ApprovalBridge` to submit the decision through the live gateway. The action
 * does **not** decide anything on its own — if the gateway subscription is gone,
 * the receiver says so ([approvalExpired]) rather than pretending the tap worked.
 * That distinction is the whole point: a button that silently does nothing is
 * worse than a button that is absent.
 *
 * ### Honest limitations
 *
 *  - The decision is delivered over the network, so it can still fail after the
 *    user taps; the app reports that failure rather than swallowing it.
 *  - Notification action reliability varies by OEM battery policy. This is
 *    documented in `docs/troubleshooting.md` instead of being papered over.
 *  - Only `allowed-once` and `rejected` exist, so there is deliberately no
 *    "always allow" action to offer.
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_TASK = "task_status"
        const val CHANNEL_CONN = "connection"

        /** Stable id for the single connection-health notification. */
        const val ID_CONNECTION = 1001

        /**
         * Base id for per-task notifications.
         *
         * Per-task ids are `ID_TASK_BASE + (hash and 0xFF)`, which keeps them out
         * of the connection notification's range while allowing a task
         * notification to replace its own earlier copy instead of stacking.
         */
        const val ID_TASK_BASE = 2000

        const val ACTION_APPROVE = "dev.dshpocket.APPROVE"
        const val ACTION_REJECT = "dev.dshpocket.REJECT"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SESSION_ID = "session_id"
    }

    /**
     * Create both channels. Safe to call repeatedly; Android ignores a duplicate
     * channel creation with the same id.
     *
     * Called from `Application.onCreate` rather than from an Activity so a
     * notification posted by a background path still has a channel to land in.
     */
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
                // LOW: a dropped connection is informative, not urgent. Making it
                // DEFAULT would let a flaky tunnel buzz the phone repeatedly.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_conn_desc) }
        )
    }

    /**
     * Whether posting is permitted.
     *
     * Checked before every post rather than cached, because the user can revoke
     * the permission at any time from system settings and a stale cache would
     * throw a `SecurityException` at the worst possible moment.
     */
    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Tap target that opens the app at a given session.
     *
     * `FLAG_IMMUTABLE` is required from API 31 and correct everywhere: nothing
     * needs to mutate these intents, and a mutable one would be a spoofing
     * surface.
     */
    private fun contentIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            // Request code must differ per session, or the extras of the first
            // notification would be reused for every later one.
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Report a finished turn.
     *
     * @param success `true` only when the desktop reported a completed turn;
     *   a cancelled or failed turn passes `false`. The caller is responsible for
     *   that distinction — this method does not infer it.
     */
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

    /**
     * Ask the user to approve an escalated tool call.
     *
     * @param eventId the waterfall event id; the reply must echo it back, which
     *   is why it is carried in the action's extras.
     * @param description the human summary from [dev.dshpocket.protocol.ApprovalRequest.summary].
     *
     * The action order is reject-then-allow on purpose: the destructive choice
     * should not be the one closest to where a thumb lands when dismissing.
     */
    fun approvalNeeded(sessionId: String, eventId: String, description: String) {
        if (!canPost()) return
        val approve = PendingIntent.getBroadcast(
            context, eventId.hashCode(), approvalIntent(eventId, sessionId, ACTION_APPROVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getBroadcast(
            // Offset the request code so the two actions do not collide in the
            // PendingIntent cache.
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

    /**
     * Build the broadcast that carries one button press back into the app.
     *
     * The action string, event id and session id are the whole payload. Note what is
     * **not** here: the approval's arguments, the tool name and the reason stay out
     * of the Intent, because a broadcast Intent can be observed within the app's own
     * process and there is no need to carry sensitive detail through it. The
     * receiver only has to know *which* event and *which* decision.
     *
     * The session id is included so the receiver can deep-link the user to the right
     * conversation when delivery fails.
     */
    private fun approvalIntent(eventId: String, sessionId: String, action: String) =
        Intent(context, ApprovalActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }

    /**
     * Report a dropped connection.
     *
     * Uses the low-importance channel and a fixed id, so repeated drops replace
     * one another instead of stacking a wall of notifications.
     */
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
     * the `$events` subscription is gone.
     *
     * Saying so is better than a button that appears to work. Answering an
     * approval requires the `clientId` that only exists while the mux socket is
     * alive, so a cold process genuinely cannot answer an old request — see
     * [dev.dshpocket.core.ApprovalBridge].
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

    /** Remove the connection notification, e.g. after a successful reconnect. */
    fun cancelConnection() {
        NotificationManagerCompat.from(context).cancel(ID_CONNECTION)
    }
}
