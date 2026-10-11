package com.pixelplus.owner.ui

import com.pixelplus.owner.ui.theme.Appearance
import com.pixelplus.owner.ui.theme.AppearancePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The bundled font is the real, unmodified file, shipped with its licence. */
class FontAssetTest {
    private fun file(relative: String): File =
        listOf("src/main/$relative", "app/src/main/$relative").map(::File).first { it.isFile }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun cairoIsTheRecordedFileAndARealTrueTypeFont() {
        val font = file("res/font/cairo.ttf")
        assertEquals("667c987182391c91f4e57a2f455b1794fb5e3ee6ca4ef3383e86bb690fa9c964", sha(font))
        val head = font.readBytes().take(4).map { it.toInt() and 0xff }
        assertEquals(listOf(0, 1, 0, 0), head)   // the TrueType scaler type
        assertTrue(font.length() in 400_000..900_000)
    }

    @Test fun itsLicenceTravelsWithIt() {
        val licence = file("assets/licences/OFL-Cairo.txt").readText()
        assertTrue(licence.contains("SIL Open Font License, Version 1.1"))
        assertTrue(licence.contains("Cairo Project Authors"))
        val sources = file("assets/licences/SOURCES.md").readText()
        assertTrue(sources.contains("res/font/cairo.ttf") && sources.contains("google/fonts"))
    }

    @Test fun theChoiceIsKnownAndOnlyKnownFontsAreAccepted() {
        assertEquals(setOf("system", "cairo"), Appearance.FONTS)
        assertEquals("cairo", Appearance.sanitize(AppearancePrefs(arabicFont = "cairo")).arabicFont)
        assertEquals("system", Appearance.sanitize(AppearancePrefs(arabicFont = "comic-sans")).arabicFont)
        assertEquals("cairo", Appearance.resolve(AppearancePrefs(arabicFont = "cairo", englishFont = "cairo"), false, 1f, 34).bundledFont)
        assertEquals(null, Appearance.resolve(AppearancePrefs(), false, 1f, 34).bundledFont)
    }
}
