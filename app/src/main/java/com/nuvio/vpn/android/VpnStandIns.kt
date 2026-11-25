@file:JvmName("VpnStandIns")
package com.nuvio.vpn.android

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL

/*
 * Kable Killers has no built-in VPN. Nuvio One's Live TV (ported here) talks to its VPN
 * through these classes; these stand-ins keep the same calls with protection always off,
 * so every request goes out directly. (Nuvio One's VPN module needs a Go toolchain to build.)
 */

object VpnPlaybackProtection {
    @JvmStatic fun reportLine(): String = ""
    @JvmStatic fun activeUserProfile(): Int = 1
    @JvmStatic fun isOn(): Boolean = false
    /** Protection off: one direct route for every request. */
    @JvmStatic fun requireUniformHttpRouting(): String = "OFF:0:0"
    @JvmStatic fun initializeActiveUserProfile(id: Int) = Unit
    @JvmStatic fun observeSettings(listener: Runnable): AutoCloseable = AutoCloseable { }
    @JvmStatic fun observeBackupChanges(listener: Runnable): AutoCloseable = AutoCloseable { }
    @JvmStatic fun beforeRequest(source: String) = Unit
    @JvmStatic fun beforeRequest(source: String, cancelled: () -> Boolean) = Unit
}

/** A plain OkHttp call factory (no tunnel). */
class VpnHttpClient private constructor(private val client: OkHttpClient) : Call.Factory {
    override fun newCall(request: Request): Call = client.newCall(request)

    companion object {
        @JvmStatic fun wrap(baseline: OkHttpClient): VpnHttpClient = VpnHttpClient(baseline)
        @JvmStatic fun openConnection(address: URL): HttpURLConnection = address.openConnection() as HttpURLConnection
        @JvmStatic fun openConnection(address: URL, baseline: OkHttpClient): HttpURLConnection =
            address.openConnection() as HttpURLConnection
    }
}

/** Nothing to back up: VPN profiles don't exist here. */
object VpnBackup {
    const val KEY = "vpn_profiles_v1"
    @JvmStatic fun prepareExport(context: Context, legacySnapshot: String?): String = legacySnapshot ?: "{}"
    @JvmStatic fun restoreSetup(context: Context, encoded: String) = Unit
    @JvmStatic fun sealCache(json: String): String = json
    @JvmStatic fun openCache(json: String): String = json
}
