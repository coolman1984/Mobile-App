package com.pixelplus.owner.ui.theme

import com.pixelplus.owner.security.LockTimeout

/** What the lock setting saves. No lock timeout was chosen yet: one minute, and the lock is on. */
data class LockPrefs(val enabled: Boolean = true, val timeout: LockTimeout = LockTimeout.ONE_MINUTE)

/**
 * Turns the saved strings back into safe values, and the other way. Plain strings so the Android side is a few lines of SharedPreferences.
 * Anything unreadable falls back to the default (appearance) or to the strictest choice (lock timeout).
 */
object PrefsCodec {
    fun appearanceToMap(p: AppearancePrefs): Map<String, String> = mapOf(
        "mode" to p.mode.name, "palette" to p.paletteId, "dynamic" to p.dynamicColour.toString(),
        "arFont" to p.arabicFont, "enFont" to p.englishFont, "scale" to p.appFontScale.toString(), "motion" to p.reducedMotion.toString(),
    )

    fun appearanceFrom(raw: Map<String, String?>): AppearancePrefs = Appearance.sanitize(
        AppearancePrefs(
            mode = ThemeMode.entries.firstOrNull { it.name == raw["mode"] } ?: ThemeMode.SYSTEM,
            paletteId = raw["palette"] ?: Palettes.DEFAULT_ID,
            dynamicColour = raw["dynamic"] == "true",
            arabicFont = raw["arFont"] ?: Appearance.DEFAULT_FONT,
            englishFont = raw["enFont"] ?: Appearance.DEFAULT_FONT,
            appFontScale = raw["scale"]?.toFloatOrNull() ?: 1.0f,
            reducedMotion = raw["motion"] == "true",
        ),
    )

    fun lockFrom(enabledRaw: String?, timeoutRaw: String?): LockPrefs = LockPrefs(
        enabled = enabledRaw != "false",
        timeout = if (timeoutRaw == null) LockTimeout.ONE_MINUTE else LockTimeout.parse(timeoutRaw),
    )
}
