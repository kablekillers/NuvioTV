package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.ThemeSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "theme_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val themeKey = stringPreferencesKey("selected_theme")
    /** Set once this profile picks a theme in a Cinema Black build; until then a saved White shows as Cinema Black. */
    private val themeChosenByUserKey = booleanPreferencesKey("theme_chosen_by_user")
    private val customThemeColorsKey = stringPreferencesKey("custom_theme_colors")
    private val fontKey = stringPreferencesKey("selected_font")
    private val amoledModeKey = booleanPreferencesKey("amoled_mode")
    private val amoledSurfacesModeKey = booleanPreferencesKey("amoled_surfaces_mode")
    private val settingsUiStyleKey = stringPreferencesKey("settings_ui_style")

    val themeSelection: Flow<ThemeSelection> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            ThemeSelection(
                theme = storedTheme(prefs),
                customColors = CustomThemeColors.decode(prefs[customThemeColorsKey])
            )
        }
    }

    val selectedFont: Flow<AppFont> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val fontName = prefs[fontKey] ?: AppFont.INTER.name
            try {
                AppFont.valueOf(fontName)
            } catch (e: IllegalArgumentException) {
                AppFont.INTER
            }
        }
    }

    val amoledMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledModeKey] ?: false
        }
    }

    val amoledSurfacesMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledSurfacesModeKey] ?: false
        }
    }

    val settingsUiStyle: Flow<SettingsUiStyle> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val styleName = prefs[settingsUiStyleKey] ?: SettingsUiStyle.CLASSIC.name
            try {
                SettingsUiStyle.valueOf(styleName)
            } catch (e: IllegalArgumentException) {
                SettingsUiStyle.CLASSIC
            }
        }
    }

    suspend fun setTheme(theme: AppTheme) {
        store().edit { prefs ->
            prefs[themeKey] = theme.name
            prefs[themeChosenByUserKey] = true
        }
    }

    suspend fun setCustomTheme(colors: CustomThemeColors) {
        store().edit { prefs ->
            prefs[customThemeColorsKey] = colors.encode()
            prefs[themeKey] = AppTheme.CUSTOM.name
            prefs[themeChosenByUserKey] = true
        }
    }

    suspend fun setFont(font: AppFont) {
        store().edit { prefs ->
            prefs[fontKey] = font.name
        }
    }

    suspend fun setAmoledMode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[amoledModeKey] = enabled
            if (!enabled) {
                prefs[amoledSurfacesModeKey] = false
            }
        }
    }

    suspend fun setAmoledSurfacesMode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[amoledSurfacesModeKey] = enabled
        }
    }

    suspend fun setSettingsUiStyle(style: SettingsUiStyle) {
        store().edit { prefs ->
            prefs[settingsUiStyleKey] = style.name
        }
    }

    suspend fun getThemeForProfile(profileId: Int): AppTheme? {
        val prefs = factory.get(profileId, FEATURE).data.first()
        return storedTheme(prefs)
    }

    fun observeThemeForProfile(profileId: Int): Flow<AppTheme?> =
        factory.get(profileId, FEATURE).data.map { prefs ->
            storedTheme(prefs)
        }

    /** White was the old default, so a saved White the profile never picked here shows as Cinema Black. */
    private fun storedTheme(prefs: androidx.datastore.preferences.core.Preferences): AppTheme? {
        val theme = prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
        return if (theme == AppTheme.WHITE && prefs[themeChosenByUserKey] != true) AppTheme.CINEMA_BLACK else theme
    }
}
