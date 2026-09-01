package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun getThemeColorScheme(palette: ThemePalette, isDark: Boolean): ColorScheme {
    return when (palette) {
        ThemePalette.SLEEK_BLUE -> if (isDark) {
            darkColorScheme(
                primary = Color(0xFF9ECAFF),
                onPrimary = Color(0xFF003258),
                primaryContainer = Color(0xFF00497D),
                onPrimaryContainer = SleekPrimaryContainer,
                secondary = SleekGreen,
                onSecondary = Color.White,
                secondaryContainer = Color(0xFF00522B),
                onSecondaryContainer = Color(0xFF8CF8AC),
                tertiary = SleekSky,
                onTertiary = Color.White,
                background = DarkBg,
                onBackground = DarkTextPrimary,
                surface = DarkSurface,
                onSurface = DarkTextPrimary,
                surfaceVariant = DarkSurfaceVariant,
                onSurfaceVariant = DarkTextSecondary,
                outline = DarkBorder,
                error = SleekRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = SleekPrimary,
                onPrimary = SleekOnPrimary,
                primaryContainer = SleekPrimaryContainer,
                onPrimaryContainer = SleekOnPrimaryContainer,
                secondary = SleekGreen,
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFD7E3F8),
                onSecondaryContainer = SleekOnPrimaryContainer,
                tertiary = SleekSky,
                onTertiary = Color.White,
                background = SleekBackground,
                onBackground = SleekOnBackground,
                surface = SleekSurface,
                onSurface = SleekOnSurface,
                surfaceVariant = SleekSurfaceVariant,
                onSurfaceVariant = SleekOnSurfaceVariant,
                outline = SleekOutline,
                error = SleekRed,
                onError = Color.White
            )
        }

        ThemePalette.EMERALD_BEAM -> if (isDark) {
            darkColorScheme(
                primary = Color(0xFF70DBB4),
                onPrimary = Color(0xFF003828),
                primaryContainer = Color(0xFF00513B),
                onPrimaryContainer = EmeraldPrimaryContainer,
                secondary = Color(0xFF55D6C2),
                onSecondary = Color(0xFF003730),
                secondaryContainer = Color(0xFF005047),
                onSecondaryContainer = Color(0xFF74F8E3),
                tertiary = SleekSky,
                onTertiary = Color.White,
                background = Color(0xFF0C1613),
                onBackground = Color(0xFFE0EBE6),
                surface = Color(0xFF131F1B),
                onSurface = Color(0xFFE0EBE6),
                surfaceVariant = Color(0xFF1B2C27),
                onSurfaceVariant = Color(0xFF8A9A94),
                outline = Color(0xFF384E46),
                error = SleekRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = EmeraldPrimary,
                onPrimary = EmeraldOnPrimary,
                primaryContainer = EmeraldPrimaryContainer,
                onPrimaryContainer = EmeraldOnPrimaryContainer,
                secondary = Color(0xFF006A5E),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFBCEEE5),
                onSecondaryContainer = Color(0xFF00201B),
                tertiary = SleekSky,
                onTertiary = Color.White,
                background = Color(0xFFF6FBF8),
                onBackground = Color(0xFF161D1A),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF161D1A),
                surfaceVariant = Color(0xFFE4EEE9),
                onSurfaceVariant = Color(0xFF3F4B46),
                outline = Color(0xFFBFCCC6),
                error = SleekRed,
                onError = Color.White
            )
        }

        ThemePalette.SUNSET_AMBER -> if (isDark) {
            darkColorScheme(
                primary = Color(0xFFFFB77C),
                onPrimary = Color(0xFF4F2500),
                primaryContainer = Color(0xFF703800),
                onPrimaryContainer = SunsetPrimaryContainer,
                secondary = Color(0xFFE5BA73),
                onSecondary = Color(0xFF3E2D04),
                secondaryContainer = Color(0xFF584318),
                onSecondaryContainer = Color(0xFFFFDF9E),
                tertiary = SleekGreen,
                onTertiary = Color.White,
                background = Color(0xFF18120D),
                onBackground = Color(0xFFEFE0D7),
                surface = Color(0xFF221A13),
                onSurface = Color(0xFFEFE0D7),
                surfaceVariant = Color(0xFF2E241B),
                onSurfaceVariant = Color(0xFF9E8F84),
                outline = Color(0xFF52443A),
                error = SleekRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = SunsetPrimary,
                onPrimary = SunsetOnPrimary,
                primaryContainer = SunsetPrimaryContainer,
                onPrimaryContainer = SunsetOnPrimaryContainer,
                secondary = Color(0xFF735A2F),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFFFE0A8),
                onSecondaryContainer = Color(0xFF261900),
                tertiary = SleekGreen,
                onTertiary = Color.White,
                background = Color(0xFFFFF8F5),
                onBackground = Color(0xFF221A14),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF221A14),
                surfaceVariant = Color(0xFFF6EDE5),
                onSurfaceVariant = Color(0xFF4F453E),
                outline = Color(0xFFD2C4BA),
                error = SleekRed,
                onError = Color.White
            )
        }

        ThemePalette.ELECTRIC_VIOLET -> if (isDark) {
            darkColorScheme(
                primary = Color(0xFFD0BCFF),
                onPrimary = Color(0xFF381E72),
                primaryContainer = Color(0xFF4F378B),
                onPrimaryContainer = VioletPrimaryContainer,
                secondary = Color(0xFFCCC2DC),
                onSecondary = Color(0xFF332D41),
                secondaryContainer = Color(0xFF4A4458),
                onSecondaryContainer = Color(0xFFE8DEF8),
                tertiary = SleekSky,
                onTertiary = Color.White,
                background = Color(0xFF141218),
                onBackground = Color(0xFFE6E1E5),
                surface = Color(0xFF1D1A22),
                onSurface = Color(0xFFE6E1E5),
                surfaceVariant = Color(0xFF27232E),
                onSurfaceVariant = Color(0xFF9790A0),
                outline = Color(0xFF49454F),
                error = SleekRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = VioletPrimary,
                onPrimary = VioletOnPrimary,
                primaryContainer = VioletPrimaryContainer,
                onPrimaryContainer = VioletOnPrimaryContainer,
                secondary = Color(0xFF625B71),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE8DEF8),
                onSecondaryContainer = Color(0xFF1D192B),
                tertiary = SleekSky,
                onTertiary = Color.White,
                background = Color(0xFFFEF7FF),
                onBackground = Color(0xFF1D1B20),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF1D1B20),
                surfaceVariant = Color(0xFFF2ECF7),
                onSurfaceVariant = Color(0xFF48454E),
                outline = Color(0xFFCAC4D0),
                error = SleekRed,
                onError = Color.White
            )
        }

        ThemePalette.CRIMSON_NOVA -> if (isDark) {
            darkColorScheme(
                primary = Color(0xFFFFB4AB),
                onPrimary = Color(0xFF690005),
                primaryContainer = Color(0xFF93000A),
                onPrimaryContainer = CrimsonPrimaryContainer,
                secondary = Color(0xFFE7BDB8),
                onSecondary = Color(0xFF442926),
                secondaryContainer = Color(0xFF5D3F3C),
                onSecondaryContainer = Color(0xFFFFDAD6),
                tertiary = SleekGreen,
                onTertiary = Color.White,
                background = Color(0xFF181212),
                onBackground = Color(0xFFEDE0DE),
                surface = Color(0xFF221A19),
                onSurface = Color(0xFFEDE0DE),
                surfaceVariant = Color(0xFF2E2322),
                onSurfaceVariant = Color(0xFFA08E8C),
                outline = Color(0xFF534341),
                error = SleekRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = CrimsonPrimary,
                onPrimary = CrimsonOnPrimary,
                primaryContainer = CrimsonPrimaryContainer,
                onPrimaryContainer = CrimsonOnPrimaryContainer,
                secondary = Color(0xFF775652),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFFFDAD6),
                onSecondaryContainer = Color(0xFF2C1513),
                tertiary = SleekGreen,
                onTertiary = Color.White,
                background = Color(0xFFFFF8F7),
                onBackground = Color(0xFF231919),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF231919),
                surfaceVariant = Color(0xFFF6ECEB),
                onSurfaceVariant = Color(0xFF524342),
                outline = Color(0xFFD6C3C1),
                error = SleekRed,
                onError = Color.White
            )
        }

        ThemePalette.OBSIDIAN_STEALTH -> if (isDark) {
            // Pure AMOLED Stealth Mode
            darkColorScheme(
                primary = Color(0xFFB0C7DE),
                onPrimary = Color(0xFF0F2231),
                primaryContainer = Color(0xFF243647),
                onPrimaryContainer = Color(0xFFDCEAF7),
                secondary = Color(0xFF90A4AE),
                onSecondary = Color(0xFF1C2B33),
                secondaryContainer = Color(0xFF2D3C44),
                onSecondaryContainer = Color(0xFFCFD8DC),
                tertiary = SleekGreen,
                onTertiary = Color.White,
                background = AmoledDarkBg,
                onBackground = AmoledDarkTextPrimary,
                surface = AmoledDarkSurface,
                onSurface = AmoledDarkTextPrimary,
                surfaceVariant = AmoledDarkSurfaceVariant,
                onSurfaceVariant = AmoledDarkTextSecondary,
                outline = AmoledDarkBorder,
                error = SleekRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF334E68),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFDCEAF7),
                onPrimaryContainer = Color(0xFF102A43),
                secondary = Color(0xFF486581),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE2E8F0),
                onSecondaryContainer = Color(0xFF1E293B),
                tertiary = SleekGreen,
                onTertiary = Color.White,
                background = Color(0xFFF8FAFC),
                onBackground = Color(0xFF0F172A),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF0F172A),
                surfaceVariant = Color(0xFFF1F5F9),
                onSurfaceVariant = Color(0xFF475569),
                outline = Color(0xFFCBD5E1),
                error = SleekRed,
                onError = Color.White
            )
        }
    }
}

@Composable
fun MyApplicationTheme(
    palette: ThemePalette = ThemePalette.SLEEK_BLUE,
    darkModePreference: DarkModePreference = DarkModePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (darkModePreference) {
        DarkModePreference.SYSTEM -> systemInDark
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }

    val colorScheme = getThemeColorScheme(palette, isDark)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
