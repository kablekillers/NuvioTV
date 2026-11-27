package com.nuvio.tv.core.vpn

import android.content.Context
import android.net.VpnService
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the VPN is doing right now. */
data class KkVpnState(
    /** A WireGuard profile (e.g. IPVanish) is saved on this TV. */
    val hasProfile: Boolean = false,
    /** Where the profile came from: "remote" (sent by Kable Killers) or "phone". */
    val source: String? = null,
    /** The customer wants it on. */
    val enabled: Boolean = false,
    val connected: Boolean = false,
    /** Android still needs the one-time "allow VPN" confirmation on this TV. */
    val needsConsent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** The server the profile connects to (host only), for the status line. */
    val server: String? = null,
)

/**
 * Kable Killers VPN (IPVanish WireGuard profiles).
 *
 * The tunnel only carries this app's traffic (other apps on the TV are untouched). The profile is
 * encrypted with an Android Keystore key and stored on the TV only. It arrives either from the
 * owner's backup page (sent remotely to this device) or from a phone on the same Wi-Fi.
 */
object KkVpn {
    private const val TAG = "KkVpn"
    private const val PREFS = "kk_vpn"
    private const val KEY_ALIAS = "kk_vpn_profile_key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(KkVpnState())
    val state: StateFlow<KkVpnState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "kablekillers"
        override fun onStateChange(newState: Tunnel.State) {
            _state.update { it.copy(connected = newState == Tunnel.State.UP) }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        val profile = prefs.getString("profile", null)
        _state.value = KkVpnState(
            hasProfile = profile != null,
            source = prefs.getString("source", null),
            enabled = prefs.getBoolean("enabled", false),
            server = prefs.getString("server", null),
        )
        if (_state.value.enabled && profile != null) connect()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Checks and saves a WireGuard .conf. Returns an error message, or null when it was saved. */
    fun importProfile(text: String, source: String, enable: Boolean = true): String? {
        val config = runCatching { parse(text) }.getOrElse {
            return "That isn't a WireGuard configuration file (${it.message ?: "couldn't read it"})."
        }
        val server = config.peers.firstOrNull()?.endpoint?.orElse(null)?.host
        val encrypted = runCatching { encrypt(text) }.getOrElse { return "Couldn't store the VPN profile securely on this TV." }
        prefs()?.edit()
            ?.putString("profile", encrypted)
            ?.putString("source", source)
            ?.putString("server", server)
            ?.putBoolean("enabled", enable)
            ?.apply()
        _state.update { it.copy(hasProfile = true, source = source, server = server, enabled = enable, error = null) }
        if (enable) connect() else disconnect()
        return null
    }

    fun remove() {
        disconnect()
        prefs()?.edit()?.clear()?.apply()
        _state.value = KkVpnState()
    }

    fun setEnabled(on: Boolean) {
        prefs()?.edit()?.putBoolean("enabled", on)?.apply()
        _state.update { it.copy(enabled = on, error = null) }
        if (on) connect() else disconnect()
    }

    /** After the customer allowed the VPN on the TV's confirmation screen. */
    fun onConsentResult(granted: Boolean) {
        if (granted) connect() else {
            _state.update { it.copy(needsConsent = true, error = "The VPN needs your OK on the TV to connect.") }
        }
    }

    fun connect() {
        val context = appContext ?: return
        scope.launch {
            mutex.withLock {
                val text = prefs()?.getString("profile", null)?.let { runCatching { decrypt(it) }.getOrNull() }
                if (text == null) {
                    _state.update { it.copy(hasProfile = false, error = "No VPN profile on this TV yet.") }
                    return@withLock
                }
                // Android asks once per TV before an app may start a VPN.
                if (VpnService.prepare(context) != null) {
                    _state.update { it.copy(needsConsent = true, connected = false) }
                    return@withLock
                }
                _state.update { it.copy(busy = true, needsConsent = false, error = null) }
                try {
                    val config = appOnly(parse(text), context.packageName)
                    val b = backend ?: GoBackend(context).also { backend = it }
                    b.setState(tunnel, Tunnel.State.UP, config)
                    _state.update { it.copy(connected = true, busy = false) }
                } catch (e: Exception) {
                    Log.w(TAG, "VPN connect failed", e)
                    _state.update { it.copy(connected = false, busy = false, error = "Couldn't connect the VPN: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock {
                runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
                _state.update { it.copy(connected = false, busy = false) }
            }
        }
    }

    private fun parse(text: String): Config = text.byteInputStream().use { Config.parse(it) }

    /** The same profile, limited to Kable Killers so other apps on the TV keep their normal connection. */
    private fun appOnly(config: Config, packageName: String): Config {
        val i = config.`interface`
        val builder = Interface.Builder()
            .setKeyPair(i.keyPair)
            .addAddresses(i.addresses)
            .addDnsServers(i.dnsServers)
            .includeApplication(packageName)
        i.listenPort.ifPresent { builder.setListenPort(it) }
        i.mtu.ifPresent { builder.setMtu(it) }
        return Config.Builder().setInterface(builder.build()).addPeers(config.peers).build()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}
