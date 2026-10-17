package dev.dshpocket.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore instance, created once per process by the property delegate.
 *
 * DataStore requires exactly one instance per file per process; declaring it as a
 * top-level delegate is what guarantees that. Constructing `DataStore` manually
 * inside a class would throw on the second instantiation.
 */
private val Context.settingsDataStore by preferencesDataStore(name = "dsh_pocket_settings")

/**
 * Non-secret user preferences.
 *
 * Secrets (the device credential) deliberately live in [CredentialStore] instead,
 * because they need hardware-backed encryption and a different backup policy.
 * Nothing in this class would be damaging if it leaked: the worst case is that an
 * attacker learns which session was open last.
 *
 * @param notifyOnDone post a notification when a turn finishes.
 * @param notifyOnApproval post a notification when the desktop is blocked on an
 *   approval. This one matters most: without it a remote turn silently stalls.
 * @param notifyOnDisconnect post a notification when the connection drops.
 * @param keepScreenAwake hold `FLAG_KEEP_SCREEN_ON` while the app is foregrounded.
 *   Off by default — a control app should not burn battery just by being open.
 * @param lastSessionId the session to reopen on next launch, so a restart does
 *   not dump the user back to the list.
 */
data class AppSettings(
    val notifyOnDone: Boolean = true,
    val notifyOnApproval: Boolean = true,
    val notifyOnDisconnect: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val lastSessionId: String? = null,
)

/**
 * Reads and writes [AppSettings].
 *
 * All writes go through [put], which treats `null` as "remove the key" so the
 * reader's defaults apply again. That keeps a single source of truth for default
 * values: they are written once in [settings], not duplicated at each call site.
 */
class SettingsStore(private val context: Context) {

    /**
     * Live settings. Emits once immediately with stored values (or defaults) and
     * again after every write, which is what lets the UI stay in sync without
     * manual refresh calls.
     */
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            notifyOnDone = p[KEY_NOTIFY_DONE] ?: true,
            notifyOnApproval = p[KEY_NOTIFY_APPROVAL] ?: true,
            notifyOnDisconnect = p[KEY_NOTIFY_DISCONNECT] ?: true,
            keepScreenAwake = p[KEY_KEEP_AWAKE] ?: false,
            lastSessionId = p[KEY_LAST_SESSION],
        )
    }

    suspend fun setNotifyOnDone(value: Boolean) = put(KEY_NOTIFY_DONE, value)
    suspend fun setNotifyOnApproval(value: Boolean) = put(KEY_NOTIFY_APPROVAL, value)
    suspend fun setNotifyOnDisconnect(value: Boolean) = put(KEY_NOTIFY_DISCONNECT, value)
    suspend fun setKeepScreenAwake(value: Boolean) = put(KEY_KEEP_AWAKE, value)

    /** Passing `null` clears the remembered session. */
    suspend fun setLastSession(id: String?) = put(KEY_LAST_SESSION, id)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T?) {
        context.settingsDataStore.edit { prefs ->
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    private companion object {
        val KEY_NOTIFY_DONE = booleanPreferencesKey("notify_done")
        val KEY_NOTIFY_APPROVAL = booleanPreferencesKey("notify_approval")
        val KEY_NOTIFY_DISCONNECT = booleanPreferencesKey("notify_disconnect")
        val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val KEY_LAST_SESSION = stringPreferencesKey("last_session")
    }
}
