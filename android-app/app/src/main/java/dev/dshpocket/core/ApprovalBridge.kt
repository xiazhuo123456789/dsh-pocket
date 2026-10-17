package dev.dshpocket.core

/**
 * Bridge between the notification action receiver and the live view model.
 *
 * ### The problem this exists to solve
 *
 * Answering an approval means sending `$events/result` with three fields:
 * `{clientId, eventId, outcome}`. The `eventId` is carried in the notification's
 * extras, so a cold process has it. The `clientId` is **not** — the desktop assigns
 * it in the `{type:"ready"}` opening frame of *this process's* `$events`
 * subscription, and the gateway rejects a reply that names an unknown client with
 * *"Remote event result identifies no active event stream"*.
 *
 * So a freshly started process genuinely cannot answer an approval that was raised
 * before it started. That is a property of the protocol, not a bug in this app.
 *
 * ### The three options, and why this one
 *
 * 1. *Let the receiver open a socket and subscribe, then answer.* It would have no
 *    `eventId` correlation — the request is not replayed to a new subscriber, so it
 *    would wait forever for an event that already happened.
 * 2. *Persist the client id.* It is per-connection and dies with the socket; a
 *    stored one is guaranteed stale and the desktop would reject it.
 * 3. *Ask the live process, and say so honestly when there is none.* This is the
 *    only option that can both succeed (when the process is alive) and fail
 *    truthfully (when it is not).
 *
 * Option 3 is what this object implements. [dev.dshpocket.notify.ApprovalActionReceiver]
 * calls [answer]; a `false` return makes it post
 * [dev.dshpocket.notify.Notifier.approvalExpired], which tells the user to open the
 * app rather than leaving a button that silently did nothing.
 *
 * ### Why an interface instead of a direct reference
 *
 * The receiver has no dependency on `PocketViewModel`, so the bridge can be
 * registered in tests with a fake handler, and the dependency direction stays
 * one-way (receiver -> bridge <- application).
 */
object ApprovalBridge {

    fun interface Handler {
        /**
         * Answer the approval carrying [eventId].
         *
         * @return `false` when no pending approval matches — which includes the
         *   case where the process restarted and the approval is unknown to it.
         *   Callers must treat `false` as "could not deliver", never as "rejected".
         */
        fun answerByEventId(eventId: String, allow: Boolean): Boolean
    }

    /**
     * The single registered handler.
     *
     * `@Volatile` because registration happens on the main thread while a
     * broadcast receiver may read it on the main thread of a different task; the
     * field must be visible across those.
     */
    @Volatile
    private var handler: Handler? = null

    /** Register the process's answerer. Called once from `Application.onCreate`. */
    fun register(h: Handler) {
        handler = h
    }

    /**
     * Remove [h] if it is still the registered handler.
     *
     * Identity-checked so a late unregister from a stale owner cannot clear a
     * handler that has since replaced it.
     */
    fun unregister(h: Handler) {
        if (handler === h) handler = null
    }

    /**
     * Deliver a decision.
     *
     * @return `true` only if a live handler accepted the event id. A `false` result
     *   is surfaced to the user; it is never swallowed.
     */
    fun answer(eventId: String, allow: Boolean): Boolean =
        handler?.answerByEventId(eventId, allow) ?: false
}
