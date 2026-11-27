package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.nuvio.tv.core.vpn.KkVpn
import com.nuvio.tv.core.vpn.VpnProfilePhoneServer
import com.nuvio.tv.ui.components.NuvioDialog

/** Settings > VPN: the Kable Killers VPN (IPVanish WireGuard), on/off for the customer. */
@Composable
internal fun VpnSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val state by KkVpn.state.collectAsState()
    var showPhone by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = "VPN",
            subtitle = "Kable Killers VPN (IPVanish). It only covers this app; other apps on the TV are untouched."
        )
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsGroupCard(title = "Connection") {
                if (state.hasProfile) {
                    SettingsToggleRow(
                        title = "Use the VPN",
                        subtitle = when {
                            state.busy -> "Connecting…"
                            state.connected -> "Connected" + (state.server?.let { " to $it" } ?: "")
                            state.needsConsent && state.enabled -> "Press OK on the TV's VPN prompt to connect."
                            state.error != null -> state.error
                            state.enabled -> "On"
                            else -> "Off"
                        },
                        checked = state.enabled,
                        onToggle = { KkVpn.setEnabled(!state.enabled) },
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier,
                    )
                } else {
                    SettingsNote(text = "No VPN set up on this TV yet. Kable Killers can send it to this TV, or add it from your phone below.")
                }
            }
            SettingsGroupCard(title = "VPN profile") {
                SettingsActionRow(
                    title = if (state.hasProfile) "Replace from phone" else "Add from phone",
                    subtitle = "Scan a QR code with a phone on the same Wi-Fi and send your IPVanish WireGuard (.conf) file.",
                    leadingIcon = Icons.Default.PhoneAndroid,
                    onClick = { showPhone = true },
                    modifier = if (!state.hasProfile && initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier,
                )
                if (state.hasProfile) {
                    SettingsActionRow(
                        title = "Remove VPN profile",
                        subtitle = if (state.source == "remote") "Sent by Kable Killers." else "Added from a phone.",
                        leadingIcon = Icons.Default.Delete,
                        onClick = { confirmRemove = true },
                    )
                }
            }
        }
    }

    if (showPhone) VpnFromPhoneDialog(onDismiss = { showPhone = false })
    if (confirmRemove) {
        NuvioDialog(onDismiss = { confirmRemove = false }, title = "Remove the VPN?") {
            Text("The VPN turns off and its profile is deleted from this TV.", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            SettingsActionRow(title = "Remove", subtitle = null, leadingIcon = Icons.Default.Delete, onClick = {
                KkVpn.remove()
                confirmRemove = false
            })
        }
    }
}

@Composable
private fun VpnFromPhoneDialog(onDismiss: () -> Unit) {
    var handle by remember { mutableStateOf<VpnProfilePhoneServer.Handle?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val tvName = Build.MODEL ?: "TV"
        handle = VpnProfilePhoneServer.start(
            VpnProfilePhoneServer.Texts(connectedTo = "Connected to $tvName"),
            accent = "#d4af37",
        ) { text ->
            val error = KkVpn.importProfile(text, source = "phone")
            status = error ?: "VPN profile saved. Connecting…"
            error ?: "Sent. Check your TV."
        }
        onDispose { handle?.stop() }
    }
    val url = handle?.url
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    NuvioDialog(onDismiss = onDismiss, title = "Add VPN from your phone") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (url == null) {
                Text("This TV isn't on a home network right now.", color = Color.White, fontSize = 16.sp)
            } else {
                qr?.let {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
                        Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(220.dp))
                    }
                }
                Text("Scan with your phone (same Wi-Fi), or open:", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                Text(url, color = Color.White, fontSize = 16.sp)
            }
            status?.let { Text(it, color = Color(0xFFD4AF37), fontSize = 15.sp) }
        }
    }
}

private fun qrBitmap(value: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
