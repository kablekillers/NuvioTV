package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.launch

/** "Use recommended settings for this TV", with what was detected and an Undo. */
@Composable
internal fun TvRecommendedSetupCard(
    settings: PlayerSettings?,
    viewModel: PlaybackSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val caps = remember { TvRecommendedSetup.capabilities(context) }
    val applied = settings != null && TvRecommendedSetup.isApplied(settings, caps)
    val canUndo = settings != null && TvRecommendedSetup.canUndo(settings, caps)
    SettingsGroupCard(modifier = modifier.fillMaxWidth(), title = stringResource(R.string.tv_setup_title)) {
        SettingsActionRow(
            title = stringResource(R.string.tv_setup_apply_title),
            subtitle = caps.summary + " — " + stringResource(
                if (caps.supports24Hz) R.string.tv_setup_desc_with_afr else R.string.tv_setup_desc_no_afr
            ),
            value = stringResource(
                when {
                    canUndo -> R.string.tv_setup_value_undo
                    applied -> R.string.tv_setup_value_on
                    else -> R.string.tv_setup_value_apply
                }
            ),
            onClick = {
                val current = settings ?: return@SettingsActionRow
                scope.launch {
                    when {
                        canUndo -> viewModel.undoRecommendedSetup()
                        !applied -> viewModel.applyRecommendedSetup(current, caps)
                    }
                }
            },
            enabled = settings != null
        )
    }
}
