package dev.dshpocket.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.dshpocket.R
import dev.dshpocket.core.ApprovalBridge

/**
 * Handles the "允许一次" / "拒绝" buttons on an approval notification.
 *
 * The decision is *not* stored here: it is forwarded to the live view model,
 * which owns the `$events` subscription and therefore the `clientId` the Host
 * requires. If the app process is gone there is nobody to answer, and the user
 * is told to open the app instead of the button silently doing nothing.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(Notifier.EXTRA_EVENT_ID) ?: return
        val allow = when (intent.action) {
            Notifier.ACTION_APPROVE -> true
            Notifier.ACTION_REJECT -> false
            else -> return
        }

        val delivered = ApprovalBridge.answer(eventId, allow)
        // Clear the notification either way; it is no longer actionable.
        NotificationManagerCompat.from(context)
            .cancel(Notifier.ID_TASK_BASE + (eventId.hashCode() and 0xFF))

        if (!delivered) {
            val app = dev.dshpocket.PocketApp.instance
            app.notifier.approvalExpired()
        }
    }
}
