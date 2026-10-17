package dev.dshpocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
 * Navigation is intentionally plain state rather than a nav graph: there are
 * four destinations and one of them (chat) is only valid while a session id is
 * known. The route is derived from the connection + session state so that a
 * process restart, a deep link and a notification tap all land in the same
 * place without a separate back-stack to keep in sync.
 */
class MainActivity : ComponentActivity() {

    private val app: PocketApp get() = PocketApp.instance

    /**
     * Registered as a field so the request survives a configuration change.
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
            // Nothing sensitive is shown on the lock screen.
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

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "dshpocket") return
        // Accept the whole invite URL in `url`, a bare token in `pair`, or a
        // desktop link whose own query survives inside a nested `pair` value.
        val raw = data.getQueryParameter("url")
            ?: data.getQueryParameter("pair")
            ?: data.toString()
        if (raw.isNotBlank()) app.viewModel.pairWithInvite(raw)
    }

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
        val s = app.viewModel.state.value
        if (s.paired != null && s.connection.phase != Phase.Connected) {
            app.viewModel.reconnect()
        }
    }
}

@Composable
private fun PocketRoot(state: UiState) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var openSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    // Landing rules, in order: no credential -> pairing; a session chosen ->
    // chat; otherwise the session list.
    if (state.paired == null) {
        PairScreen(
            connection = state.connection,
            onPair = { app().viewModel.pairWithInvite(it) },
        )
        return
    }

    if (showSettings) {
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
                openSessionId = null
                app().viewModel.unpair()
            },
        )
        return
    }

    val currentSession = openSessionId ?: state.chat.sessionId
    if (currentSession != null && state.chat.sessionId == currentSession) {
        ChatScreen(
            state = state.chat,
            connection = state.connection,
            onBack = {
                openSessionId = null
                app().viewModel.closeSession()
            },
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
            onOpen = { id ->
                openSessionId = id
                app().viewModel.openSession(id)
            },
            onNew = { app().viewModel.newSession() },
            onRefresh = app().viewModel::refreshSessions,
            onSettings = { showSettings = true },
        )
        LaunchedEffect(Unit) {
            // Honour the last session only on a cold start of the list.
            if (openSessionId == null && state.chat.sessionId == null) {
                app().viewModel.resumeLastSession(state.settings.lastSessionId)
            }
        }
    }
}

private fun app(): PocketApp = PocketApp.instance
