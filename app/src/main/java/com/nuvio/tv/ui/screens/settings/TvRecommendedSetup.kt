package com.nuvio.tv.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlin.math.abs
import kotlin.math.roundToInt

/** What this TV can do, read once from the system. */
data class TvCapabilities(
    val model: String,
    val ramGb: Int,
    val supports24Hz: Boolean,
    val supports4k: Boolean,
) {
    val summary: String
        get() = buildList {
            add(model)
            if (ramGb > 0) add("$ramGb GB RAM")
            add(if (supports4k) "4K" else "HD")
            add(if (supports24Hz) "24 Hz supported" else "no 24 Hz mode")
        }.joinToString(" · ")
}

/**
 * One-press "recommended for this TV": automatic player switch on errors, and frame-rate matching
 * when the screen has a 24 Hz mode (smoother films, no judder). The replaced values are kept for undo.
 */
object TvRecommendedSetup {
    private data class Values(val autoSwitch: Boolean, val frameRate: FrameRateMatchingMode)

    private var previous: Values? = null

    fun capabilities(context: Context): TvCapabilities {
        val ramBytes = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
        }.getOrDefault(0L)
        val display = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) display?.supportedModes.orEmpty().toList() else emptyList()
        return TvCapabilities(
            model = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ").ifBlank { "This TV" },
            ramGb = (ramBytes / (1024.0 * 1024 * 1024)).roundToInt(),
            supports24Hz = modes.any { abs(it.refreshRate - 24f) < 0.15f || abs(it.refreshRate - 23.976f) < 0.05f || abs(it.refreshRate - 48f) < 0.15f },
            supports4k = modes.any { it.physicalWidth >= 3840 || it.physicalHeight >= 3840 },
        )
    }

    private fun recommended(caps: TvCapabilities) = Values(
        autoSwitch = true,
        frameRate = if (caps.supports24Hz) FrameRateMatchingMode.START_STOP else FrameRateMatchingMode.OFF,
    )

    private fun PlayerSettings.values() = Values(autoSwitchInternalPlayerOnError, frameRateMatchingMode)

    fun isApplied(settings: PlayerSettings, caps: TvCapabilities) = settings.values() == recommended(caps)

    fun canUndo(settings: PlayerSettings, caps: TvCapabilities) = previous != null && isApplied(settings, caps)

    suspend fun apply(store: PlayerSettingsDataStore, settings: PlayerSettings, caps: TvCapabilities) {
        if (isApplied(settings, caps)) return
        previous = settings.values()
        write(store, recommended(caps))
    }

    suspend fun undo(store: PlayerSettingsDataStore) {
        val old = previous ?: return
        previous = null
        write(store, old)
    }

    private suspend fun write(store: PlayerSettingsDataStore, values: Values) {
        store.setAutoSwitchInternalPlayerOnError(values.autoSwitch)
        store.setFrameRateMatchingMode(values.frameRate)
    }
}
