package com.example.ui.theme

import androidx.compose.ui.graphics.Color

enum class DarkModePreference(val displayName: String) {
    SYSTEM("System Default"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class ThemePalette(
    val id: String,
    val displayName: String,
    val description: String,
    val previewColor: Color,
    val previewContainerColor: Color
) {
    SLEEK_BLUE(
        id = "sleek_blue",
        displayName = "Sleek Blue",
        description = "Precision Blue & Ice Accents",
        previewColor = Color(0xFF0061A4),
        previewContainerColor = Color(0xFFD1E4FF)
    ),
    EMERALD_BEAM(
        id = "emerald_beam",
        displayName = "Emerald Beam",
        description = "Vibrant Mint & Cyber Teal",
        previewColor = Color(0xFF006C50),
        previewContainerColor = Color(0xFF8EF7CF)
    ),
    SUNSET_AMBER(
        id = "sunset_amber",
        displayName = "Sunset Amber",
        description = "Warm Tangerine & Amber Gold",
        previewColor = Color(0xFF934B00),
        previewContainerColor = Color(0xFFFFDCC3)
    ),
    ELECTRIC_VIOLET(
        id = "electric_violet",
        displayName = "Electric Violet",
        description = "Deep Violet & Neon Lavender",
        previewColor = Color(0xFF6F43C0),
        previewContainerColor = Color(0xFFEADDFF)
    ),
    CRIMSON_NOVA(
        id = "crimson_nova",
        displayName = "Crimson Nova",
        description = "Bold Ruby & Vivid Rose",
        previewColor = Color(0xFFB3261E),
        previewContainerColor = Color(0xFFFFDAD6)
    ),
    OBSIDIAN_STEALTH(
        id = "obsidian_stealth",
        displayName = "Obsidian Stealth",
        description = "Monochrome Slate & AMOLED Black",
        previewColor = Color(0xFF486581),
        previewContainerColor = Color(0xFFDCE3E9)
    );

    companion object {
        fun fromId(id: String): ThemePalette {
            return entries.find { it.id == id } ?: SLEEK_BLUE
        }
    }
}
