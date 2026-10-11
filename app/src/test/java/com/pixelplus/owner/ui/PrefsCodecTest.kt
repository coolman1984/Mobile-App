package com.pixelplus.owner.ui

import com.pixelplus.owner.security.LockTimeout
import com.pixelplus.owner.ui.theme.AppearancePrefs
import com.pixelplus.owner.ui.theme.LockPrefs
import com.pixelplus.owner.ui.theme.PrefsCodec
import com.pixelplus.owner.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsCodecTest {
    @Test fun appearanceSurvivesARoundTrip() {
        val p = AppearancePrefs(ThemeMode.DARK, "forest", true, "system", "system", 1.25f, true)
        assertEquals(p, PrefsCodec.appearanceFrom(PrefsCodec.appearanceToMap(p)))
    }

    @Test fun unreadableAppearanceFallsBackToSafeDefaults() {
        assertEquals(AppearancePrefs(), PrefsCodec.appearanceFrom(emptyMap()))
        val bad = PrefsCodec.appearanceFrom(mapOf("mode" to "NEON", "palette" to "x", "scale" to "abc", "arFont" to "comic"))
        assertEquals(AppearancePrefs(), bad)
        assertEquals(1.5f, PrefsCodec.appearanceFrom(mapOf("scale" to "99")).appFontScale, 0f)
    }

    @Test fun lockDefaultsAreOnAndOneMinuteAndAnUnknownTimeoutIsStrict() {
        assertEquals(LockPrefs(true, LockTimeout.ONE_MINUTE), PrefsCodec.lockFrom(null, null))
        assertEquals(LockPrefs(false, LockTimeout.FIVE_MINUTES), PrefsCodec.lockFrom("false", "FIVE_MINUTES"))
        assertEquals(LockTimeout.IMMEDIATE, PrefsCodec.lockFrom("true", "SOMETIMES").timeout)
        assertEquals(true, PrefsCodec.lockFrom("garbage", null).enabled)
    }
}
