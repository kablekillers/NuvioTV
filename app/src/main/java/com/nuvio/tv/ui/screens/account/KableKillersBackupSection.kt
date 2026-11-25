@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.backup.KableKillersBackupService
import com.nuvio.tv.core.backup.KableKillersBackupState
import com.nuvio.tv.core.backup.KableKillersRestoreSummary
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KableKillersBackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
class KableKillersBackupViewModel @Inject constructor(
    private val service: KableKillersBackupService
) : ViewModel() {
    val enabled: Boolean get() = service.enabled
    val backupState: StateFlow<KableKillersBackupState> = service.state

    private val _ui = MutableStateFlow(KableKillersBackupUiState())
    val ui: StateFlow<KableKillersBackupUiState> = _ui.asStateFlow()

    fun backupNow(doneMessage: String) = launchAction {
        service.backupNow(force = true).map { doneMessage }
    }

    fun restore(describe: (KableKillersRestoreSummary) -> String) = launchAction {
        service.restoreNow().map(describe)
    }

    fun signInWithPin(email: String, pin: String, describe: (KableKillersRestoreSummary) -> String) = launchAction {
        service.signInWithRestorePin(email, pin).getOrThrow()
        service.restoreNow().map(describe)
    }

    /** Backup-only sign-in (no Nuvio account): disconnect so the next sign-in takes a new QR. */
    fun signOutOfDevice() {
        service.forgetDevice()
        _ui.value = KableKillersBackupUiState()
    }

    private fun launchAction(block: suspend () -> Result<String>) {
        if (_ui.value.busy) return
        _ui.value = KableKillersBackupUiState(busy = true)
        viewModelScope.launch {
            val result = runCatching { block().getOrThrow() }
            _ui.value = result.fold(
                onSuccess = { KableKillersBackupUiState(message = it) },
                onFailure = { KableKillersBackupUiState(message = it.message ?: it.javaClass.simpleName, isError = true) }
            )
        }
    }
}

/** Backup rows for the Account settings page. Shows nothing when the backup isn't set up. */
@Composable
internal fun KableKillersBackupSection(
    signedIn: Boolean,
    viewModel: KableKillersBackupViewModel
) {
    if (!viewModel.enabled) return
    val state by viewModel.backupState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    val backedUpText = stringResource(R.string.kk_backup_done)
    val restoredTemplate = stringResource(R.string.kk_backup_restored)
    val describe: (KableKillersRestoreSummary) -> String = { s ->
        String.format(restoredTemplate, s.addons, s.plugins, s.homeRows, s.collections)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.sm))
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.kk_backup_title),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = statusText(state, signedIn),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            when {
                ui.busy -> Text(
                    text = stringResource(R.string.kk_backup_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Primary
                )
                ui.message != null -> Text(
                    text = ui.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.isError) NuvioTheme.colors.Error else NuvioTheme.colors.Secondary
                )
            }
        }

        if (signedIn) {
            SettingsActionButton(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.kk_backup_now_title),
                subtitle = stringResource(R.string.kk_backup_now_subtitle),
                onClick = { viewModel.backupNow(backedUpText) }
            )
        }
        if (state.hasDeviceKey) {
            SettingsActionButton(
                icon = Icons.Default.Restore,
                title = stringResource(R.string.kk_backup_restore_title),
                subtitle = stringResource(R.string.kk_backup_restore_subtitle),
                onClick = { confirmRestore = true }
            )
        }
        if (state.hasDeviceKey && !signedIn) {
            SettingsActionButton(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = stringResource(R.string.kk_backup_sign_out_title),
                subtitle = stringResource(R.string.kk_backup_sign_out_subtitle),
                onClick = { viewModel.signOutOfDevice() }
            )
        }
        if (!state.hasDeviceKey && !signedIn) {
            SettingsActionButton(
                icon = Icons.Default.VpnKey,
                title = stringResource(R.string.kk_backup_pin_title),
                subtitle = stringResource(R.string.kk_backup_pin_subtitle),
                onClick = { showPinDialog = true }
            )
        }

    }

    if (confirmRestore) {
        KableKillersRestoreConfirmDialog(
            onConfirm = {
                confirmRestore = false
                viewModel.restore(describe)
            },
            onDismiss = { confirmRestore = false }
        )
    }
    if (showPinDialog) {
        KableKillersRestorePinDialog(
            onSubmit = { email, pin ->
                showPinDialog = false
                viewModel.signInWithPin(email, pin, describe)
            },
            onDismiss = { showPinDialog = false }
        )
    }
}

@Composable
private fun statusText(state: KableKillersBackupState, signedIn: Boolean): String {
    val lastBackup = state.lastBackupAtMs.takeIf { it > 0 }?.let { relative(it) }
    val lastRestore = state.lastRestoreAtMs.takeIf { it > 0 }?.let { relative(it) }
    return when {
        signedIn && lastBackup != null -> stringResource(R.string.kk_backup_status_signed_in, lastBackup)
        signedIn -> stringResource(R.string.kk_backup_status_signed_in_none)
        state.hasDeviceKey && lastRestore != null ->
            stringResource(R.string.kk_backup_status_connected_restored, state.accountEmail.orEmpty(), lastRestore)
        state.hasDeviceKey -> stringResource(R.string.kk_backup_status_connected, state.accountEmail.orEmpty())
        else -> stringResource(R.string.kk_backup_status_signed_out)
    }
}

private fun relative(timeMs: Long): String =
    DateUtils.getRelativeTimeSpanString(timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
private fun KableKillersRestoreConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.kk_backup_restore_confirm_title),
        subtitle = stringResource(R.string.kk_backup_restore_confirm_subtitle),
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).focusRequester(cancelFocus),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.kk_backup_restore_confirm_action))
            }
        }
    }
}

@Composable
private fun KableKillersRestorePinDialog(onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val emailFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { emailFocus.requestFocus() }
    val canSubmit = email.contains('@') && pin.length >= 6

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.kk_backup_pin_title),
        subtitle = stringResource(R.string.kk_backup_pin_dialog_subtitle),
        suppressFirstKeyUp = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            InputField(
                value = email,
                onValueChange = { email = it.trim() },
                placeholder = stringResource(R.string.kk_backup_pin_email_hint),
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().focusRequester(emailFocus)
            )
            InputField(
                value = pin,
                onValueChange = { value -> pin = value.filter(Char::isDigit).take(12) },
                placeholder = stringResource(R.string.kk_backup_pin_hint),
                keyboardType = KeyboardType.NumberPassword,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = { if (canSubmit) onSubmit(email, pin) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { if (canSubmit) onSubmit(email, pin) },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.kk_backup_pin_action)) }
            }
        }
    }
}
