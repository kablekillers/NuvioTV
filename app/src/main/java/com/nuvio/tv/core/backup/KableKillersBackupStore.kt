package com.nuvio.tv.core.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** App-private storage for this device's backup identity and status. */
@Singleton
class KableKillersBackupStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<KableKillersBackupState> = _state.asStateFlow()

    @get:Synchronized
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    fun deviceKey(): String? = prefs.getString(KEY_DEVICE_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveDeviceKey(key: String, accountEmail: String?) {
        prefs.edit()
            .putString(KEY_DEVICE_KEY, key)
            .apply { if (accountEmail != null) putString(KEY_ACCOUNT, accountEmail) }
            .apply()
        refresh()
    }

    fun saveAccount(accountEmail: String) {
        prefs.edit().putString(KEY_ACCOUNT, accountEmail).apply()
        refresh()
    }

    fun clearDeviceKey() {
        prefs.edit()
            .remove(KEY_DEVICE_KEY)
            .remove(KEY_EDIT_TOKEN)
            .remove(KEY_EDIT_TOKEN_EXPIRES)
            .apply()
        refresh()
    }

    /** Which backup copy this device last applied or sent (so it only applies newer ones). */
    var lastAppliedSnapshotId: Long
        get() = prefs.getLong(KEY_APPLIED_SNAPSHOT, 0L)
        set(value) { prefs.edit().putLong(KEY_APPLIED_SNAPSHOT, value).apply() }

    /** Fingerprint of Profile 1's setup right after the last apply/send; a change means the owner edited it. */
    var localBaseline: String?
        get() = prefs.getString(KEY_LOCAL_BASELINE, null)
        set(value) { prefs.edit().putString(KEY_LOCAL_BASELINE, value).apply() }

    /** Edit token from unlocking Profile 1 with the restore PIN (customer login, Nuvio down). */
    fun editToken(nowSeconds: Long = System.currentTimeMillis() / 1000): String? {
        val expires = prefs.getLong(KEY_EDIT_TOKEN_EXPIRES, 0L)
        if (expires <= nowSeconds + 60) return null
        return prefs.getString(KEY_EDIT_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun saveEditToken(token: String, expiresAtSeconds: Long) {
        prefs.edit().putString(KEY_EDIT_TOKEN, token).putLong(KEY_EDIT_TOKEN_EXPIRES, expiresAtSeconds).apply()
    }

    fun markBackup(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_BACKUP, atMs).apply()
        refresh()
    }

    fun markRestore(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_RESTORE, atMs).apply()
        refresh()
    }

    private fun refresh() {
        _state.value = read()
    }

    private fun read() = KableKillersBackupState(
        hasDeviceKey = !prefs.getString(KEY_DEVICE_KEY, null).isNullOrBlank(),
        accountEmail = prefs.getString(KEY_ACCOUNT, null),
        lastBackupAtMs = prefs.getLong(KEY_LAST_BACKUP, 0L),
        lastRestoreAtMs = prefs.getLong(KEY_LAST_RESTORE, 0L)
    )

    private companion object {
        const val PREFS = "kable_killers_backup"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_KEY = "device_key"
        const val KEY_ACCOUNT = "account_email"
        const val KEY_LAST_BACKUP = "last_backup_at"
        const val KEY_LAST_RESTORE = "last_restore_at"
        const val KEY_APPLIED_SNAPSHOT = "applied_snapshot_id"
        const val KEY_LOCAL_BASELINE = "local_baseline"
        const val KEY_EDIT_TOKEN = "edit_token"
        const val KEY_EDIT_TOKEN_EXPIRES = "edit_token_expires_at"
    }
}
