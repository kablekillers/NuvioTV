package com.nuvio.tv.core.vpn

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android asks once per TV before an app may start a VPN. When the VPN is on (turned on here, or
 * sent remotely by Kable Killers) and that OK is still missing, this shows Android's prompt once per
 * app start. After the OK it connects on its own from then on.
 */
@Composable
fun KkVpnConsentHost() {
    val context = LocalContext.current
    val state by KkVpn.state.collectAsState()
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        KkVpn.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.enabled, state.hasProfile, state.needsConsent) {
        if (!state.enabled || !state.hasProfile) { asked = false; return@LaunchedEffect }
        if (!state.needsConsent || asked) return@LaunchedEffect
        val intent = VpnService.prepare(context)
        if (intent == null) {
            KkVpn.onConsentResult(true)
        } else {
            asked = true
            runCatching { launcher.launch(intent) }
        }
    }
}
