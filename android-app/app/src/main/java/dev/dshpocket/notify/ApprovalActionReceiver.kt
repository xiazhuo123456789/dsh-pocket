package dev.dshpocket.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.dshpocket.core.ApprovalBridge

/**
 * Handles the "允许一次" / "拒绝" buttons on an approval notification.
 *
 * ### What this receiver is — and, just as importantly, is not
 *
 * A notification *action* is a `PendingIntent.getBroadcast`, so it lands here with
 * no Activity and no access to the app's live ViewModel. What it does have is the
 * `eventId` (in [Notifier.EXTRA_EVENT_ID]) and the user's choice (the action name).
 *
 * This receiver does **not** decide anything itself. An approval reply must go to
 * `$events/result` and it must name the `clientId` the Host issued to *this process's*
 * `$events` subscription. That id exists only while the mux socket is open, and the
 * socket lives in the ViewModel. So the receiver's only honest job is:
 *
 *  1. turn the action name back into a boolean (`allow`),
 *  2. hand that to [dev.dshpocket.core.ApprovalBridge], which forwards to the live
 *     ViewModel,
 *  3. if there is no live handler (process was killed and restarted cold), tell the
 *     user the approval can no longer be answered instead of letting the button
 *     silently do nothing.
 *
 * ### Why the notification is always cancelled
 *
 * Whether delivery succeeded or not, after this tap the notification is no longer
 * actionable: a delivered decision should not invite a second contradictory tap, and
 * an undelivered one is replaced by the explanatory [dev.dshpocket.notify.Notifier.approvalExpired]
 * notification, so keeping the stale card would only confuse the user.
 *
 * ### Why it goes through a Bridge rather than touching the ViewModel directly
 *
 * The receiver lives in `dev.dshpocket.notify`; the ViewModel in `dev.dshpocket.core`.
 * Routing through [ApprovalBridge] keeps the dependency one-way (receiver -> bridge)
 * and lets a test register a fake handler.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A missing/unknown event id is not actionable — bail out silently, because
        // there is nothing specific to tell the user (it is not this app's event).
        val eventId = intent.getStringExtra(Notifier.EXTRA_EVENT_ID) ?: return
        val allow = when (intent.action) {
            Notifier.ACTION_APPROVE -> true
            Notifier.ACTION_REJECT -> false
            // Any other action (e.g. a stale broadcast or a different app) → drop.
            else -> return
        }

        // `delivered == true` only if a live handler accepted the event id. It never
        // means "the desktop saw it" — that confirmation comes later via the turn
        // state. It only means *we* are still able to send.
        val delivered = ApprovalBridge.answer(eventId, allow)

        // Cancel the card either way; it is no longer actionable (see class doc).
        NotificationManagerCompat.from(context)
            .cancel(Notifier.ID_TASK_BASE + (eventId.hashCode() and 0xFF))

        if (!delivered) {
            // No live handler → the process restarted after the approval was raised.
            // Be honest: tell the user to open the app rather than pretending.
            dev.dshpocket.PocketApp.instance.notifier.approvalExpired()
        }
    }
}