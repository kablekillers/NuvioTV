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
        prefs.edit().remove(KEY_DEVICE_KEY).apply()
        refresh()
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
    }
}
