package dev.dshpocket

import android.app.Application
import dev.dshpocket.core.ApprovalBridge
import dev.dshpocket.core.PocketViewModel
import dev.dshpocket.data.CredentialStore
import dev.dshpocket.data.SettingsStore
import dev.dshpocket.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide singletons.
 *
 * ### Why the ViewModel lives here and not in an Activity
 *
 * The app's valuable state is not UI state — it is a live WebSocket, a `$events`
 * subscription and a set of pending approvals. All three must outlive a
 * configuration change (rotation) and must be reachable from a notification action
 * while no Activity exists at all.
 *
 * Two concrete consequences:
 *
 * 1. **Rotation must not drop the carrier.** An Activity-scoped ViewModel is
 *    recreated on a configuration change, which would tear down the mux socket and
 *    the followed session stream mid-turn.
 * 2. **Approval from the notification must be the same decision as approval in
 *    the UI.** [dev.dshpocket.notify.ApprovalActionReceiver] runs with no Activity;
 *    it reaches this single instance through [ApprovalBridge]. If there were two
 *    ViewModel instances, the receiver could update one while the screen showed the
 *    other — the classic "I approved it but the card is still there" bug.
 *
 * This is a deliberate singleton, not an accident of convenience. It is safe
 * because the object holds no Activity or Context beyond the `Application` itself,
 * so it cannot leak an Activity.
 *
 * ### Why `appScope` is a SupervisorJob
 *
 * One failing child (say, a reconnect attempt that throws) must not cancel the
 * scope and kill every other long-lived job — including the `$events` collector
 * that approvals depend on.
 */
class PocketApp : Application() {

    lateinit var credentials: CredentialStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var viewModel: PocketViewModel
        private set

    /**
     * Scope for work that should live as long as the process.
     *
     * `Dispatchers.Default` rather than `Main`: this scope hosts network
     * collectors, not UI work. Anything that must touch the main thread does so
     * through the state flow, which the UI collects on the main dispatcher.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        credentials = CredentialStore(this)
        settings = SettingsStore(this)
        notifier = Notifier(this)
        viewModel = PocketViewModel(this, credentials, settings, notifier, appScope)
        // Channels are created off the main thread because channel creation is a
        // binder call and has no reason to delay first frame.
        appScope.launch { notifier.ensureChannels() }
        // The bridge is how a notification action reaches the live gateway. It is
        // registered here, once, so there is exactly one answerer per process.
        ApprovalBridge.register { eventId, allow ->
            viewModel.answerApprovalByEventId(eventId, allow)
        }
    }

    companion object {
        /**
         * Set in [onCreate], which always runs before any Activity.
         *
         * `lateinit` rather than nullable so call sites do not have to handle a
         * "no application" case that cannot happen in a real process.
         */
        lateinit var instance: PocketApp
            private set
    }
}
