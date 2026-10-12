
package com.nuvio.tv

import android.content.Context

object CachePreferences {
    private const val PREFS_NAME = "kable_killers_cache_settings"
    private const val AUTO_CLEAR = "auto_clear_on_startup"
    private const val SMART_CLEANUP = "smart_cache_cleanup"

    fun autoClearOnStartup(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CLEAR, false)

    fun setAutoClearOnStartup(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTO_CLEAR, enabled)
            .apply()
    }

    fun smartCleanupEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SMART_CLEANUP, true)

    fun setSmartCleanupEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SMART_CLEANUP, enabled)
            .apply()
    }
}
