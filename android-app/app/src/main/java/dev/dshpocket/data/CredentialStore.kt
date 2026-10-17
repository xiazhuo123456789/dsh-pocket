package dev.dshpocket.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The paired-device credential.
 *
 * The Harness treats a paired device id as a **full-control credential**, so it
 * is stored in an AES-256-GCM encrypted preference file keyed by the Android
 * Keystore, is excluded from backup (`allowBackup=false` plus
 * `data_extraction_rules.xml`), and is never written to logs.
 */
data class PairedDesktop(
    val baseUrl: String,
    val deviceId: String,
    val pairedAt: Long,
    val label: String? = null,
)

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): PairedDesktop? {
        val base = prefs.getString(KEY_BASE_URL, null) ?: return null
        val device = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        if (base.isBlank() || device.isBlank()) return null
        return PairedDesktop(
            baseUrl = base,
            deviceId = device,
            pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L),
            label = prefs.getString(KEY_LABEL, null),
        )
    }

    fun save(desktop: PairedDesktop) {
        prefs.edit()
            .putString(KEY_BASE_URL, desktop.baseUrl)
            .putString(KEY_DEVICE_ID, desktop.deviceId)
            .putLong(KEY_PAIRED_AT, desktop.pairedAt)
            .putString(KEY_LABEL, desktop.label)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun hasCredential(): Boolean = load() != null

    private companion object {
        const val FILE_NAME = "dsh_pocket_credentials"
        const val KEY_BASE_URL = "base_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PAIRED_AT = "paired_at"
        const val KEY_LABEL = "label"
    }
}
