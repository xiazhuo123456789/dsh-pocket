package dev.dshpocket.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "dsh_pocket_settings")

/** Non-secret user preferences. Secrets live in [CredentialStore]. */
data class AppSettings(
    val notifyOnDone: Boolean = true,
    val notifyOnApproval: Boolean = true,
    val notifyOnDisconnect: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val lastSessionId: String? = null,
)

class SettingsStore(private val context: Context) {

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
    suspend fun setLastSession(id: String?) = put(KEY_LAST_SESSION, id)

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T?) {
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
