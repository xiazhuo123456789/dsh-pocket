package dev.dshpocket.core

/**
 * Bridge between the notification action receiver and the live view model.
 *
 * Why a bridge instead of doing the work inside the receiver: answering an
 * approval means calling `$events/result` with the `clientId` the Host assigned
 * to *this process's* `$events` subscription. That id only exists while the mux
 * socket is open, so a cold process genuinely cannot answer an old approval.
 * Rather than pretending otherwise, the receiver asks the live view model and,
 * when there is none, tells the user to open the app.
 */
object ApprovalBridge {

    fun interface Handler {
        /** Answer the approval carrying [eventId]; returns false if it is unknown. */
        fun answerByEventId(eventId: String, allow: Boolean): Boolean
    }

    @Volatile
    private var handler: Handler? = null

    fun register(h: Handler) {
        handler = h
    }

    fun unregister(h: Handler) {
        if (handler === h) handler = null
    }

    fun answer(eventId: String, allow: Boolean): Boolean =
        handler?.answerByEventId(eventId, allow) ?: false
}
