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
 * The view model lives here rather than in the activity so that the connection,
 * the `$events` subscription and the pending approvals survive a configuration
 * change and can be driven by the notification action receiver while the UI is
 * closed. Keeping exactly one instance is what makes "approve from the
 * notification" and "the chat screen still knows the task state" the same fact
 * rather than two copies of it.
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

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        credentials = CredentialStore(this)
        settings = SettingsStore(this)
        notifier = Notifier(this)
        viewModel = PocketViewModel(this, credentials, settings, notifier, appScope)
        appScope.launch { notifier.ensureChannels() }
        ApprovalBridge.register { eventId, allow ->
            viewModel.answerApprovalByEventId(eventId, allow)
        }
    }

    companion object {
        lateinit var instance: PocketApp
            private set
    }
}
