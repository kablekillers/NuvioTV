package com.nuvio.tv.data.local

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.householdPinDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "household_profile_pins",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    }
)

/**
 * PINs for household profiles on the shared customer login (kablekillers6).
 *
 * Household profiles exist on one device only, but the cloud stores PINs by
 * profile number for the whole login, so a cloud PIN on one customer's
 * "Profile 2" would lock every customer's Profile 2. These PINs therefore stay
 * on this device: a parent can lock their own profile without touching anyone
 * else's TV. Only a salted PBKDF2 hash is kept, never the PIN itself.
 */
@Singleton
class HouseholdPinStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class VerifyResult(val unlocked: Boolean, val retryAfterSeconds: Int = 0)

    private val dataStore = context.householdPinDataStore
    private val random = SecureRandom()

    // Brief lockout after repeated wrong guesses (per profile, until app restart).
    private val failures = mutableMapOf<Int, Int>()
    private val lockedUntil = mutableMapOf<Int, Long>()

    /** Profile ids on this device that have a household PIN. */
    val lockedProfiles: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs ->
        prefs.asMap().keys
            .filter { it.name.startsWith(KEY_PREFIX) }
            .mapNotNull { it.name.removePrefix(KEY_PREFIX).toIntOrNull() }
            .associateWith { true }
    }

    suspend fun hasPin(profileId: Int): Boolean =
        dataStore.data.first()[key(profileId)] != null

    suspend fun setPin(profileId: Int, pin: String) {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = hash(pin, salt)
        dataStore.edit { prefs ->
            prefs[key(profileId)] = "${encode(salt)}:${encode(hash)}"
        }
        failures.remove(profileId)
        lockedUntil.remove(profileId)
    }

    suspend fun clearPin(profileId: Int) {
        dataStore.edit { prefs -> prefs.remove(key(profileId)) }
        failures.remove(profileId)
        lockedUntil.remove(profileId)
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
        failures.clear()
        lockedUntil.clear()
    }

    suspend fun verify(profileId: Int, pin: String): VerifyResult {
        val now = SystemClock.elapsedRealtime()
        val until = lockedUntil[profileId] ?: 0L
        if (until > now) {
            return VerifyResult(unlocked = false, retryAfterSeconds = ((until - now + 999) / 1000).toInt())
        }
        val stored = dataStore.data.first()[key(profileId)] ?: return VerifyResult(unlocked = true)
        val parts = stored.split(":")
        val ok = parts.size == 2 && runCatching {
            MessageDigest.isEqual(hash(pin, decode(parts[0])), decode(parts[1]))
        }.getOrDefault(false)
        if (ok) {
            failures.remove(profileId)
            lockedUntil.remove(profileId)
            return VerifyResult(unlocked = true)
        }
        val count = (failures[profileId] ?: 0) + 1
        failures[profileId] = count
        if (count >= MAX_ATTEMPTS) {
            failures.remove(profileId)
            lockedUntil[profileId] = now + LOCKOUT_MS
            return VerifyResult(unlocked = false, retryAfterSeconds = (LOCKOUT_MS / 1000).toInt())
        }
        return VerifyResult(unlocked = false)
    }

    private fun key(profileId: Int) = stringPreferencesKey("$KEY_PREFIX$profileId")

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEY_PREFIX = "pin_p"
        const val ITERATIONS = 20_000
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
