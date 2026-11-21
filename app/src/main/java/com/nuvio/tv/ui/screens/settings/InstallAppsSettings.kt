@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.installapps.InstallAppsViewModel
import com.nuvio.tv.installapps.URL_INSTALL_ID
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun InstallAppsSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: InstallAppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    val busy = state.activeDownloadId != null

    // Coming back from Android's "install unknown apps" screen finishes the pending install.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.retryPendingInstall()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    fun progressLabel(): String = state.downloadProgress
        ?.let { context.getString(R.string.install_apps_downloading_percent, (it * 100).toInt()) }
        ?: context.getString(R.string.install_apps_downloading)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.install_apps_title),
            subtitle = stringResource(R.string.install_apps_subtitle)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsGroupCard(title = stringResource(R.string.install_apps_link_section)) {
                SettingsActionRow(
                    title = stringResource(R.string.install_apps_link_title),
                    subtitle = stringResource(R.string.install_apps_link_subtitle),
                    value = if (state.activeDownloadId == URL_INSTALL_ID) progressLabel() else null,
                    leadingIcon = Icons.Default.Link,
                    enabled = !busy,
                    onClick = { showUrlDialog = true },
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }

            SettingsGroupCard(title = stringResource(R.string.install_apps_store_section)) {
                when {
                    state.isLoading && state.apps.isEmpty() ->
                        SettingsNote(text = stringResource(R.string.install_apps_loading))
                    state.loadFailed && state.apps.isEmpty() -> SettingsActionRow(
                        title = stringResource(R.string.install_apps_load_failed),
                        subtitle = stringResource(R.string.install_apps_retry),
                        trailingIcon = Icons.Default.Refresh,
                        onClick = viewModel::refresh
                    )
                    state.apps.isEmpty() ->
                        SettingsNote(text = stringResource(R.string.install_apps_empty))
                    else -> state.apps.forEach { app ->
                        SettingsActionRow(
                            title = app.name,
                            subtitle = app.description.ifEmpty { null },
                            value = if (state.activeDownloadId == app.id) {
                                progressLabel()
                            } else {
                                stringResource(R.string.install_apps_install)
                            },
                            trailingIcon = Icons.Default.Download,
                            enabled = !busy,
                            onClick = { viewModel.install(app) }
                        )
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        InstallFromUrlDialog(
            onInstall = { url ->
                showUrlDialog = false
                viewModel.installFromUrl(url)
            },
            onDismiss = { showUrlDialog = false }
        )
    }

    if (state.showUnknownSourcesDialog) {
        NuvioDialog(
            onDismiss = viewModel::dismissUnknownSourcesDialog,
            title = stringResource(R.string.install_apps_permission_title),
            subtitle = stringResource(R.string.install_apps_permission_body),
            width = 600.dp
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.install_apps_cancel),
                    onClick = viewModel::dismissUnknownSourcesDialog
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.install_apps_open_settings),
                    primary = true,
                    onClick = {
                        viewModel.dismissUnknownSourcesDialog()
                        viewModel.openUnknownSourcesSettings()
                    }
                )
            }
        }
    }
}

@Composable
private fun InstallFromUrlDialog(
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        if (value.isNotBlank()) onInstall(value)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.install_apps_link_title),
        subtitle = stringResource(R.string.install_apps_link_dialog_subtitle),
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Card(
            onClick = {
                inputFocusRequester.requestFocus()
                keyboardController?.show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if ((native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                native.action == KeyEvent.ACTION_DOWN
                            ) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                        textDirection = TextDirection.Content
                    ),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent
                    ),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.install_apps_link_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        inner()
                    }
                )
            }
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.install_apps_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.install_apps_install),
                primary = true,
                enabled = value.isNotBlank(),
                onClick = submit
            )
        }
    }
}
