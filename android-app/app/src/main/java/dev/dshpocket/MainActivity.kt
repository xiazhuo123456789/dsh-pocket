package dev.dshpocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshpocket.core.ChatState.Status as ChatStatus
import dev.dshpocket.core.Phase
import dev.dshpocket.core.UiState
import dev.dshpocket.service.ConnectionService
import dev.dshpocket.ui.ChatScreen
import dev.dshpocket.ui.DshPocketTheme
import dev.dshpocket.ui.PairScreen
import dev.dshpocket.ui.SessionsScreen
import dev.dshpocket.ui.SettingsScreen

/**
 * Single activity shell.
 *
 * ### Why navigation is plain state and not a nav graph
 *
 * There are four destinations and one of them (chat) is only valid while a
 * session id is known. The route is *derived* from connection + session state, so
 * a process restart, a deep link and a notification tap all land in the same place
 * without a separate back stack to keep in sync. A nav graph here would add a
 * second source of truth for "where am I" that could disagree with the ViewModel.
 *
 * ### Why the ViewModel lives on the Application
 *
 * [PocketApp.viewModel] owns the connection, the `$events` subscription and any
 * pending approval. If it lived here, a configuration change (rotation) would
 * destroy and rebuild it, dropping the WebSocket and — worse — making an approval
 * notification's action impossible to answer, because answering requires the
 * `clientId` that only exists while the socket is alive. See [ApprovalBridge].
 *
 * ### Foreground service lifetime
 *
 * The service runs only while something is actually in flight. Holding it open
 * permanently would keep a notification in the shade for no reason; releasing it
 * too early would let the OS freeze the process mid-turn.
 */
class MainActivity : ComponentActivity() {

    private val app: PocketApp get() = PocketApp.instance

    /**
     * Registered as a field so the launcher survives a configuration change.
     * Registering inside a method would throw if the link were opened twice.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askForNotificationPermission()

        // The pairing QR/deep link carries the whole invite URL in `pair`.
        handleDeepLink(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Nothing sensitive is shown on the lock screen. An approval prompt on
            // a locked phone would leak what the desktop is about to run.
            setShowWhenLocked(false)
        }

        setContent {
            DshPocketTheme {
                val state by app.viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.settings.keepScreenAwake) {
                    if (state.settings.keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                LaunchedEffect(Unit) { app.viewModel.bootstrap() }
                // Hold the connection open while a turn is actually running, and
                // let it go the moment the Host reports the turn settled.
                //
                // `sessions.any { running }` is included because a turn may be
                // running in a session the user is not currently looking at.
                val busy = state.chat.status == ChatStatus.Running ||
                    state.chat.status == ChatStatus.Queued ||
                    state.chat.status == ChatStatus.AwaitingApproval ||
                    state.sessions.any { it.running }
                LaunchedEffect(busy) {
                    if (busy) ConnectionService.start(this@MainActivity)
                    else ConnectionService.stop(this@MainActivity)
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PocketRoot(state)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Redeem a `dshpocket://pair?...` link.
     *
     * Three payload shapes are accepted because the desktop, a QR scanner app and a
     * chat app all produce slightly different things:
     *  - `dshpocket://pair?url=<encoded desktop link>` — a wrapper,
     *  - `dshpocket://pair?pair=<token>` — a bare token,
     *  - anything else whose full text is a usable invite URL.
     *
     * No de-duplication is attempted here. An earlier version tried to remember the
     * last handled link, which turned out to be worse: the field is cleared on
     * every `onCreate`, so it could swallow a legitimate second pairing attempt
     * after a process restart. A duplicate redemption is harmless anyway — the
     * desktop rejects an already-consumed token with a normal error.
     */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "dshpocket") return
        val raw = data.getQueryParameter("url")
            ?: data.getQueryParameter("pair")
            ?: data.toString()
        if (raw.isNotBlank()) app.viewModel.pairWithInvite(raw)
    }

    /**
     * Ask for the Android 13+ notification permission.
     *
     * Only asked once at startup, and a refusal is not fatal: the app still works,
     * the user simply will not be told when the desktop is blocked on an approval.
     * Re-prompting would be hostile, and the setting is one tap away in system
     * settings.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        // Re-establish the carrier when coming back to the foreground; the mux
        // socket is closed by the OS when the process is backgrounded for long.
        //
        // Only when a credential exists and we are not already connected: an
        // unconditional reconnect on every `onStart` would tear down a healthy
        // socket each time the user glanced at a notification.
        val s = app.viewModel.state.value
        if (s.paired != null && s.connection.phase != Phase.Connected) {
            app.viewModel.reconnect()
        }
    }
}

/**
 * Route selection.
 *
 * Landing rules, in order: no credential -> pairing; settings requested ->
 * settings; a session opened -> chat; otherwise the workspace list. The order
 * matters — pairing must win over everything, because without a credential none
 * of the other screens can do anything.
 *
 * ### Why the open session is read from [UiState] and not held here
 *
 * It used to be `rememberSaveable` local state in this composable, with a
 * separate `LaunchedEffect` that tried to "resume the last session once" on
 * startup. That was the source of two of the worst bugs in the app:
 *
 * 1. The one-shot guard was a `remember` **inside the `else` branch**, so every
 *    return to the list destroyed and recreated it, the guard read `false` again,
 *    and the resume fired a second time — the user was shoved back into a session
 *    they had just left, forever. On screen: a list that flashes and a back
 *    gesture that appears to do nothing.
 * 2. Having two candidate sources (`here` and `state.chat.sessionId`) meant one
 *    of them could be non-null while the other was not, so the chat could render
 *    over an empty transcript, or the list could render with a session still
 *    attached.
 *
 * Navigation now reads exactly one field, [UiState.openSessionId], which
 * `openSession`/`closeSession` set. There is no resume-on-start: the user picks a
 * conversation, and a cold start lands on the list — which is predictable, and
 * is what makes the back gesture behave the same way every time.
 *
 * `showSettings` stays local because it is pure view state with no cross-screen
 * consequence, and `rememberSaveable` keeps it across rotation.
 */
@Composable
private fun PocketRoot(state: UiState) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (state.paired == null) {
        PairScreen(
            connection = state.connection,
            onPair = { app().viewModel.pairWithInvite(it) },
        )
        return
    }

    if (showSettings) {
        // Intercept the system back key: the user expects it to leave settings,
        // not to quit the app. Without this handler the back key propagates to
        // the Activity and exits, which looks like a crash from the user's view.
        BackHandler { showSettings = false }
        SettingsScreen(
            connection = state.connection,
            paired = state.paired,
            settings = state.settings,
            onBack = { showSettings = false },
            onNotifyDone = app().viewModel::setNotifyOnDone,
            onNotifyApproval = app().viewModel::setNotifyOnApproval,
            onNotifyDisconnect = app().viewModel::setNotifyOnDisconnect,
            onReconnect = app().viewModel::reconnect,
            onUnpair = {
                showSettings = false
                app().viewModel.unpair()
            },
        )
        return
    }

    // One field decides. No fallback to `state.chat.sessionId`: that produced a
    // frame where navigation said "list" but data said "session", and whichever
    // won visually produced either a stuck chat or a chat over an empty list.
    val openSessionId = state.openSessionId
    if (openSessionId != null) {
        // Same fix for the chat screen: the system back key must return to the
        // session list, not exit the app. The app-bar back arrow already does
        // this, but the gesture/physical back key bypassed it.
        BackHandler { app().viewModel.closeSession() }
        ChatScreen(
            state = state.chat,
            connection = state.connection,
            onBack = { app().viewModel.closeSession() },
            onSend = { app().viewModel.send(it) },
            onStop = { app().viewModel.stop() },
            onApprove = { approval, allow -> app().viewModel.answerApproval(approval, allow) },
            onDismissError = app().viewModel::dismissChatError,
        )
    } else {
        SessionsScreen(
            sessions = state.sessions,
            loading = state.sessionsLoading,
            connection = state.connection,
            onOpen = { id -> app().viewModel.openSession(id) },
            onNew = { cwd -> app().viewModel.newSession(cwd) },
            onRefresh = app().viewModel::refreshSessions,
            onSettings = { showSettings = true },
        )
    }
}

/**
 * Shortcut to the application-scoped [PocketApp].
 *
 * Safe to call from any composable: `PocketApp` is created by the framework before
 * any Activity exists, so the `lateinit` cannot be observed uninitialised here.
 */
private fun app(): PocketApp = PocketApp.instance
