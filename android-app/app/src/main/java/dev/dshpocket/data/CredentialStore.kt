package dev.dshpocket.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The paired-desktop record.
 *
 * @param baseUrl the desktop's origin, e.g. `https://host.trycloudflare.com`.
 *   A quick-tunnel hostname changes whenever the desktop restarts, so this value
 *   can go stale — that is expected and is surfaced as a connection error rather
 *   than silently retried forever.
 * @param deviceId the credential the desktop issued at pairing time. This is the
 *   secret half of the record.
 * @param pairedAt epoch millis, for display only.
 * @param label a human label, currently unused; kept so a future multi-desktop
 *   version does not need a migration.
 */
data class PairedDesktop(
    val baseUrl: String,
    val deviceId: String,
    val pairedAt: Long,
    val label: String? = null,
)

/**
 * Encrypted storage for the paired-device credential.
 *
 * ### Why this is treated as a secret
 *
 * The desktop treats a paired device id as a **full-control credential**: with it,
 * a caller can create sessions, submit prompts, cancel running turns and answer
 * approval requests. Anyone who reads this value off the device can do all of
 * that. It is therefore:
 *
 *  - stored in an AES-256-GCM encrypted preference file whose key lives in the
 *    Android Keystore (hardware-backed on most devices),
 *  - excluded from cloud backup and device-to-device transfer
 *    (`allowBackup=false` plus `data_extraction_rules.xml`), so it cannot leak
 *    through a backup the user did not think about,
 *  - never logged, never put in an `Intent` extra, and never rendered in the UI.
 *
 * ### Why `EncryptedSharedPreferences`
 *
 * It is the smallest correct thing available without adding a crypto dependency
 * of our own. The alternative — hand-rolled AES with a Keystore key — would mean
 * writing and reviewing IV handling and key rotation, which is exactly the kind
 * of code that should not be custom in a small project.
 *
 * ### Known limitation, stated honestly
 *
 * `security-crypto` is an alpha artifact (`1.1.0-alpha06`) and is in maintenance
 * mode upstream. It is still the pragmatic choice for a single small secret, but
 * this is a real dependency risk and is recorded in `docs/security-model.md`.
 * Root-level compromise of the device defeats this protection regardless of which
 * library is used — that limitation is inherent, not specific to this app.
 */
class CredentialStore(context: Context) {

    /**
     * Created lazily so constructing [CredentialStore] is cheap and cannot throw
     * during `Application.onCreate`. Keystore access can fail on a device whose
     * keystore is temporarily unavailable; deferring it means that failure shows
     * up where it can be reported instead of crashing startup.
     */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            // Key names and values are encrypted with separate schemes: AES-SIV
            // is deterministic (needed to look a key up) while AES-GCM is not
            // (needed so equal values do not look equal).
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Read the stored pairing, or `null` when unpaired.
     *
     * A partially-written record (base URL but no credential, or blank strings)
     * is treated as unpaired rather than as a broken pairing, so the app falls
     * back to the pairing screen instead of failing every request with a 403.
     */
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

    /**
     * Persist a pairing.
     *
     * `apply()` is used rather than `commit()`: the write is idempotent and a
     * crash before it lands simply means the user re-pairs, whereas blocking the
     * calling thread on disk I/O during an interactive pairing flow is worse.
     */
    fun save(desktop: PairedDesktop) {
        prefs.edit()
            .putString(KEY_BASE_URL, desktop.baseUrl)
            .putString(KEY_DEVICE_ID, desktop.deviceId)
            .putLong(KEY_PAIRED_AT, desktop.pairedAt)
            .putString(KEY_LABEL, desktop.label)
            .apply()
    }

    /**
     * Forget the pairing on this phone.
     *
     * **This does not revoke the device on the desktop.** The desktop keeps its
     * own device list, and only the desktop can remove an entry from it — the
     * pairing-management surface is deliberately unreachable from a paired remote
     * device. The UI says so explicitly rather than implying that "unpair" here
     * is a full revocation.
     */
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
