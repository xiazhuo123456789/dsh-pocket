package dev.dshpocket.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
 * @param simplifiedChinese append a "reply in Simplified Chinese" directive to
 *   every prompt.
 *
 *   On by default because the failing case is the common one: the desktop model
 *   otherwise sometimes answers in Traditional Chinese, and the user reads that as
 *   a defect in this app. It is a *preference about the model's output*, not a
 *   locale switch — this app still renders whatever text comes back, unchanged.
 *   Turning it off restores byte-exact passthrough with no directive at all.
 */
data class AppSettings(
    val notifyOnDone: Boolean = true,
    val notifyOnApproval: Boolean = true,
    val notifyOnDisconnect: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val lastSessionId: String? = null,
    val simplifiedChinese: Boolean = true,
    /** 最近用过的电脑地址，最新的在前；只用于「修改地址」时一键填回。 */
    val recentAddresses: List<String> = emptyList(),
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
            simplifiedChinese = p[KEY_SIMPLIFIED_CHINESE] ?: true,
            recentAddresses = p[KEY_RECENT_ADDRESSES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    suspend fun setNotifyOnDone(value: Boolean) = put(KEY_NOTIFY_DONE, value)
    suspend fun setNotifyOnApproval(value: Boolean) = put(KEY_NOTIFY_APPROVAL, value)
    suspend fun setNotifyOnDisconnect(value: Boolean) = put(KEY_NOTIFY_DISCONNECT, value)
    suspend fun setKeepScreenAwake(value: Boolean) = put(KEY_KEEP_AWAKE, value)
    suspend fun setSimplifiedChinese(value: Boolean) = put(KEY_SIMPLIFIED_CHINESE, value)

    /**
     * 记住用过的电脑地址。
     *
     * 桌面端的 `trycloudflare` 域名每次重启都变，所以「上一次能用」不等于「现在能用」，
     * 但**同一台电脑的几个历史地址**是这个 app 唯一可能替用户想起来的东西。没有它，
     * 换域名后用户只能凭空手打一串随机英文，这是修改地址这件事最难用的地方。
     *
     * 只保留最近 [MAX_RECENT_ADDRESSES] 条：这是个便利功能，不是审计日志，
     * 无限增长会让设置文件变成一份域名历史记录。
     */
    suspend fun addRecentAddress(address: String) {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        val stored = context.settingsDataStore.data.first()[KEY_RECENT_ADDRESSES].orEmpty()
        val current = stored.split("\n").filter { it.isNotBlank() }
        val next = (listOf(trimmed) + current.filter { it != trimmed }).take(MAX_RECENT_ADDRESSES)
        context.settingsDataStore.edit { it[KEY_RECENT_ADDRESSES] = next.joinToString("\n") }
    }

    /** Passing `null` clears the remembered session. */
    suspend fun setLastSession(id: String?) = put(KEY_LAST_SESSION, id)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T?) {
        context.settingsDataStore.edit { prefs ->
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    private companion object {
        const val MAX_RECENT_ADDRESSES = 5
        val KEY_NOTIFY_DONE = booleanPreferencesKey("notify_done")
        val KEY_NOTIFY_APPROVAL = booleanPreferencesKey("notify_approval")
        val KEY_NOTIFY_DISCONNECT = booleanPreferencesKey("notify_disconnect")
        val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val KEY_LAST_SESSION = stringPreferencesKey("last_session")
        val KEY_SIMPLIFIED_CHINESE = booleanPreferencesKey("simplified_chinese")
        val KEY_RECENT_ADDRESSES = stringPreferencesKey("recent_addresses")
    }
}
