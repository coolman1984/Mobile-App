package com.pixelplus.owner.security

/**
 * M09: when the app locks. Pure, with time passed in from the monotonic clock (`SystemClock.elapsedRealtime`), never the wall clock, so
 * changing the phone's date neither locks nor unlocks anything. The BiometricPrompt call, the credential fallback and the manifest
 * permission are NOT IMPLEMENTED here; no homemade PIN exists.
 *
 * A cold start is always locked. Content is hidden the moment the app leaves the foreground (so it is not in the recents thumbnail),
 * and stays hidden until the next unlock.
 */
enum class LockTimeout(val millis: Long) {
    IMMEDIATE(0L), ONE_MINUTE(60_000L), FIVE_MINUTES(5 * 60_000L), FIFTEEN_MINUTES(15 * 60_000L);

    companion object {
        /** An unknown saved value is the strictest choice. */
        fun parse(raw: String?): LockTimeout = entries.firstOrNull { it.name == raw } ?: IMMEDIATE
    }
}

data class LockState(
    val locked: Boolean = true,
    /** Content must not be drawn. True from the moment of leaving the foreground until an unlock. */
    val contentHidden: Boolean = true,
    val leftAtElapsedMs: Long? = null,
)

object LockPolicy {
    fun coldStart(): LockState = LockState()

    fun onBackground(state: LockState, elapsedMs: Long): LockState =
        if (state.locked) state.copy(contentHidden = true) else state.copy(contentHidden = true, leftAtElapsedMs = elapsedMs)

    /**
     * Coming back. If the monotonic clock went backwards (the phone restarted while the process somehow survived is impossible, but a
     * restored state is not) the answer is to lock.
     */
    fun onForeground(state: LockState, elapsedMs: Long, timeout: LockTimeout): LockState {
        if (state.locked) return state
        val left = state.leftAtElapsedMs ?: return state.copy(locked = true, contentHidden = true)
        val away = elapsedMs - left
        val lock = away < 0 || away >= timeout.millis
        return if (lock) state.copy(locked = true, contentHidden = true, leftAtElapsedMs = null)
        else state.copy(contentHidden = false, leftAtElapsedMs = null)
    }

    fun onUnlockSucceeded(state: LockState): LockState = LockState(locked = false, contentHidden = false, leftAtElapsedMs = null)

    /** A failed or cancelled prompt changes nothing: still locked, still hidden. */
    fun onUnlockFailed(state: LockState): LockState = state.copy(locked = true, contentHidden = true)

    /** The owner turned the lock timeout down or the credential changed: lock now. */
    fun lockNow(state: LockState): LockState = state.copy(locked = true, contentHidden = true, leftAtElapsedMs = null)
}

/** A notification says only how many. It never names a shop, a person or a product, in either language. */
object GenericNotice {
    fun text(requests: Int, events: Int, arabic: Boolean): String? {
        val r = requests.coerceAtLeast(0)
        val e = events.coerceAtLeast(0)
        if (r == 0 && e == 0) return null
        return if (arabic) {
            buildList {
                if (r > 0) add("$r طلب جديد")
                if (e > 0) add("$e تنبيه جديد")
            }.joinToString(" · ")
        } else {
            buildList {
                if (r > 0) add(if (r == 1) "1 new request" else "$r new requests")
                if (e > 0) add(if (e == 1) "1 new alert" else "$e new alerts")
            }.joinToString(" · ")
        }
    }
}
