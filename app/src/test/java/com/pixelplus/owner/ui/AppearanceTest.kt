package com.pixelplus.owner.ui

import com.pixelplus.owner.ui.theme.Appearance
import com.pixelplus.owner.ui.theme.AppearancePrefs
import com.pixelplus.owner.ui.theme.Contrast
import com.pixelplus.owner.ui.theme.Palettes
import com.pixelplus.owner.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun thereAreEightDistinctPalettes() {
        assertEquals(8, Palettes.all.size)
        assertEquals(8, Palettes.all.map { it.id }.toSet().size)
    }

    @Test fun everyTextPairInEveryPaletteReachesAA() {
        for (p in Palettes.all) for ((name, scheme) in listOf("light" to p.light, "dark" to p.dark)) {
            for ((fg, bg) in scheme.pairs()) {
                val r = Contrast.ratio(fg, bg)
                assertTrue("${p.id} $name ${Integer.toHexString(fg)} on ${Integer.toHexString(bg)} = $r", r >= Contrast.AA)
            }
        }
    }

    @Test fun contrastMathMatchesTheKnownExtremes() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0001)
    }

    @Test fun modeAndDynamicColourResolveSafely() {
        val prefs = AppearancePrefs(mode = ThemeMode.SYSTEM, dynamicColour = true)
        assertTrue(Appearance.resolve(prefs, systemDark = true, systemFontScale = 1f, sdk = 34).dark)
        assertFalse(Appearance.resolve(prefs.copy(mode = ThemeMode.LIGHT), true, 1f, 34).dark)
        assertTrue(Appearance.resolve(prefs.copy(mode = ThemeMode.DARK), false, 1f, 34).dark)
        assertTrue(Appearance.resolve(prefs, false, 1f, 31).useDynamic)
        assertFalse("below Android 12 the chosen palette is used", Appearance.resolve(prefs, false, 1f, 30).useDynamic)
    }

    @Test fun theSliderMultipliesTheSystemSizeAndIsBounded() {
        assertEquals(1.5f, Appearance.resolve(AppearancePrefs(appFontScale = 1.5f), false, 1.0f, 34).fontScale, 0.0001f)
        assertEquals(1.2f * 1.2f, Appearance.resolve(AppearancePrefs(appFontScale = 1.2f), false, 1.2f, 34).fontScale, 0.0001f)
        assertEquals(2.0f, Appearance.resolve(AppearancePrefs(appFontScale = 1.5f), false, 1.8f, 34).fontScale, 0.0001f)
        assertEquals(Appearance.MAX_SCALE, Appearance.sanitize(AppearancePrefs(appFontScale = 9f)).appFontScale, 0f)
        assertEquals(Appearance.MIN_SCALE, Appearance.sanitize(AppearancePrefs(appFontScale = -1f)).appFontScale, 0f)
        assertEquals(1.0f, Appearance.sanitize(AppearancePrefs(appFontScale = Float.NaN)).appFontScale, 0f)
        assertEquals(1.0f, Appearance.resolve(AppearancePrefs(), false, Float.NaN, 34).fontScale, 0f)
    }

    @Test fun anUnknownSavedChoiceFallsBackToDefaults() {
        val s = Appearance.sanitize(AppearancePrefs(paletteId = "neon", arabicFont = "papyrus", englishFont = ""))
        assertEquals(Palettes.DEFAULT_ID, s.paletteId)
        assertEquals(Appearance.DEFAULT_FONT, s.arabicFont)
        assertEquals(Appearance.DEFAULT_FONT, s.englishFont)
        assertEquals(Palettes.DEFAULT_ID, Palettes.byId(null).id)
    }
}
