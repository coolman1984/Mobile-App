package com.pixelplus.owner.ui.theme

import kotlin.math.pow

/**
 * M08: the eight colour sets, the contrast rule and the appearance preferences. Plain ARGB ints, no Compose types, so the rules are
 * testable without a handset. Drawing them (a Material colour scheme, live fonts, a dynamic-colour switch) is NOT IMPLEMENTED here.
 *
 * Every text pair in every palette, light and dark, must reach WCAG AA (4.5 : 1); a test holds all of them to it.
 */
data class Scheme(
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val primary: Int,
    val onPrimary: Int,
    val error: Int,
    val onError: Int,
) {
    /** The text-on-colour pairs that must stay readable. */
    fun pairs(): List<Pair<Int, Int>> = listOf(
        onBackground to background, onSurface to surface, onPrimary to primary, onError to error,
        primary to background, primary to surface,
    )
}

data class Palette(val id: String, val light: Scheme, val dark: Scheme)

object Palettes {
    private fun light(primary: Int) = Scheme(
        background = 0xFFFFFFFF.toInt(), onBackground = 0xFF1A1A1A.toInt(), surface = 0xFFF5F5F5.toInt(), onSurface = 0xFF1A1A1A.toInt(),
        primary = primary, onPrimary = 0xFFFFFFFF.toInt(), error = 0xFFB00020.toInt(), onError = 0xFFFFFFFF.toInt(),
    )

    private fun dark(primary: Int) = Scheme(
        background = 0xFF121212.toInt(), onBackground = 0xFFEDEDED.toInt(), surface = 0xFF1E1E1E.toInt(), onSurface = 0xFFEDEDED.toInt(),
        primary = primary, onPrimary = 0xFF000000.toInt(), error = 0xFFFFB4AB.toInt(), onError = 0xFF690005.toInt(),
    )

    // light primaries are dark enough for white text and for text on white/grey; dark primaries are light enough for black text
    val all: List<Palette> = listOf(
        Palette("ocean", light(0xFF0B5C8A.toInt()), dark(0xFF7FC4EB.toInt())),
        Palette("forest", light(0xFF1B6B3A.toInt()), dark(0xFF8FD6A4.toInt())),
        Palette("sand", light(0xFF7A5200.toInt()), dark(0xFFE6C27A.toInt())),
        Palette("rose", light(0xFF9C1F4A.toInt()), dark(0xFFF2A3BF.toInt())),
        Palette("violet", light(0xFF5B3A9E.toInt()), dark(0xFFC9B3F2.toInt())),
        Palette("slate", light(0xFF37474F.toInt()), dark(0xFFB0BEC5.toInt())),
        Palette("teal", light(0xFF00695C.toInt()), dark(0xFF80CBC4.toInt())),
        Palette("ink", light(0xFF1A1A1A.toInt()), dark(0xFFEDEDED.toInt())),
    )

    const val DEFAULT_ID = "ocean"
    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}

object Contrast {
    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xff) + 0.7152 * channel((argb shr 8) and 0xff) + 0.0722 * channel(argb and 0xff)

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    const val AA = 4.5
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What is saved. Every field is a safe value; anything unreadable falls back to a default (see [Appearance.sanitize]). */
data class AppearancePrefs(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val paletteId: String = Palettes.DEFAULT_ID,
    val dynamicColour: Boolean = false,
    val arabicFont: String = Appearance.DEFAULT_FONT,
    val englishFont: String = Appearance.DEFAULT_FONT,
    val appFontScale: Float = 1.0f,
    val reducedMotion: Boolean = false,
)

data class ResolvedAppearance(val dark: Boolean, val scheme: Scheme, val useDynamic: Boolean, val fontScale: Float, val palette: Palette, val bundledFont: String? = null)

object Appearance {
    const val DEFAULT_FONT = "system"
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.5f

    const val FONT_CAIRO = "cairo"

    /** Fonts that may be picked: the phone's own, and Cairo (SIL OFL 1.1, bundled with its licence; see assets/licences/SOURCES.md). */
    val FONTS = setOf(DEFAULT_FONT, FONT_CAIRO)

    fun sanitize(raw: AppearancePrefs): AppearancePrefs = raw.copy(
        paletteId = Palettes.byId(raw.paletteId).id,
        arabicFont = if (raw.arabicFont in FONTS) raw.arabicFont else DEFAULT_FONT,
        englishFont = if (raw.englishFont in FONTS) raw.englishFont else DEFAULT_FONT,
        appFontScale = if (raw.appFontScale.isFinite()) raw.appFontScale.coerceIn(MIN_SCALE, MAX_SCALE) else 1.0f,
    )

    /**
     * The app's slider multiplies the phone's own font size; it never replaces it. The product is capped so an already large system
     * size cannot be pushed past 2x.
     */
    fun resolve(raw: AppearancePrefs, systemDark: Boolean, systemFontScale: Float, sdk: Int): ResolvedAppearance {
        val prefs = sanitize(raw)
        val dark = when (prefs.mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val palette = Palettes.byId(prefs.paletteId)
        val system = if (systemFontScale.isFinite() && systemFontScale > 0f) systemFontScale else 1.0f
        return ResolvedAppearance(
            dark = dark,
            scheme = if (dark) palette.dark else palette.light,
            useDynamic = prefs.dynamicColour && sdk >= 31,   // below Android 12 it falls back to the chosen palette
            fontScale = (system * prefs.appFontScale).coerceAtMost(2.0f),
            palette = palette,
            bundledFont = prefs.arabicFont.takeIf { it != DEFAULT_FONT },
        )
    }
}
