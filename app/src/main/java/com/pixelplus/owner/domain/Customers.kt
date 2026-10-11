package com.pixelplus.owner.domain

import java.text.Normalizer
import java.time.LocalDateTime

/**
 * M11: customer search, follow-up times and the links a customer card may open. Pure.
 *
 * Nothing here sends a message. WhatsApp automation is forbidden by the factory's MSG-01; a card may only *offer* a phone call or a web
 * page, and the owner taps it.
 */
object ArabicSearch {
    private val TASHKEEL = Regex("[ً-ٰٟـ]")   // vowel marks, dagger alef, tatweel
    private val ARABIC_INDIC = '٠'..'٩'
    private val EXTENDED_INDIC = '۰'..'۹'

    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = TASHKEEL.replace(s, "")
        val out = StringBuilder(s.length)
        for (c in s) {
            out.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    in ARABIC_INDIC -> '0' + (c - '٠')
                    in EXTENDED_INDIC -> '0' + (c - '۰')
                    else -> c.lowercaseChar()
                },
            )
        }
        return out.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** Every word the owner typed must start a word of the name, or appear inside it. An empty query matches everything. */
    fun matches(query: String, name: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val target = normalize(name)
        return words.all { target.contains(it) }
    }

    fun <T> filterSorted(items: List<T>, query: String, nameOf: (T) -> String): List<T> =
        items.filter { matches(query, nameOf(it)) }.sortedWith(compareBy({ normalize(nameOf(it)) }))
}

data class FollowUpTime(val dueUtcMs: Long, val remindUtcMs: Long, val shiftedBecauseSkipped: Boolean)

object FollowUps {
    const val MAX_OFFSET_MIN = 7 * 24 * 60

    /**
     * A time the clock skips on the day summer time starts moves to the first valid minute after the gap, and says so. A time that
     * happens twice when it ends uses the first. The reminder is [offsetMin] before, never before the epoch.
     */
    fun schedule(local: LocalDateTime, offsetMin: Int): FollowUpTime? {
        if (offsetMin !in 0..MAX_OFFSET_MIN) return null
        val strict = CairoClock.resolveUtcMs(local, GapPolicy.REJECT, OverlapPolicy.EARLIER)
        val due = strict ?: CairoClock.resolveUtcMs(local, GapPolicy.SHIFT_FORWARD, OverlapPolicy.EARLIER) ?: return null
        return FollowUpTime(due, (due - offsetMin * 60_000L).coerceAtLeast(0L), shiftedBecauseSkipped = strict == null)
    }
}

object IntentUri {
    private val PHONE = Regex("^tel:\\+?[0-9]{5,15}$")
    private val HOST = Regex("^[a-z0-9.-]{1,253}$")
    private val FORBIDDEN_HOSTS = setOf("wa.me", "api.whatsapp.com", "web.whatsapp.com", "whatsapp.com", "chat.whatsapp.com")

    /** A call or a web page, never a messenger, a file, an app link or a script. Returns the cleaned uri or null. */
    fun safe(raw: String): String? {
        val uri = raw.trim()
        if (uri.length > 300 || uri.any { it.isISOControl() || it.isWhitespace() }) return null
        if (PHONE.matches(uri)) return uri
        if (!uri.startsWith("https://")) return null
        val rest = uri.removePrefix("https://")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        if (host.contains('@') || host.contains(':') || !HOST.matches(host) || !host.contains('.')) return null
        if (host in FORBIDDEN_HOSTS || FORBIDDEN_HOSTS.any { host.endsWith(".$it") }) return null
        return uri
    }
}
