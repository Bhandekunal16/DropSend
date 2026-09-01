package com.example.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.ui.theme.DarkModePreference
import com.example.ui.theme.ThemePalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dropsend_theme_prefs"
        private const val KEY_PALETTE = "selected_palette"
        private const val KEY_DARK_MODE = "dark_mode_pref"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentPalette = MutableStateFlow(loadPalette())
    val currentPalette: StateFlow<ThemePalette> = _currentPalette.asStateFlow()

    private val _darkModePreference = MutableStateFlow(loadDarkMode())
    val darkModePreference: StateFlow<DarkModePreference> = _darkModePreference.asStateFlow()

    private fun loadPalette(): ThemePalette {
        val id = prefs.getString(KEY_PALETTE, ThemePalette.SLEEK_BLUE.id) ?: ThemePalette.SLEEK_BLUE.id
        return ThemePalette.fromId(id)
    }

    private fun loadDarkMode(): DarkModePreference {
        val name = prefs.getString(KEY_DARK_MODE, DarkModePreference.SYSTEM.name) ?: DarkModePreference.SYSTEM.name
        return try {
            DarkModePreference.valueOf(name)
        } catch (_: Exception) {
            DarkModePreference.SYSTEM
        }
    }

    fun setPalette(palette: ThemePalette) {
        prefs.edit().putString(KEY_PALETTE, palette.id).apply()
        _currentPalette.value = palette
    }

    fun setDarkModePreference(mode: DarkModePreference) {
        prefs.edit().putString(KEY_DARK_MODE, mode.name).apply()
        _darkModePreference.value = mode
    }
}
