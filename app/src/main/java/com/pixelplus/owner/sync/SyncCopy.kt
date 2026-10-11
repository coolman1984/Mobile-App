package com.pixelplus.owner.sync

/**
 * Both languages are shown. The phone language does not hide one of them.
 * Remote success is a different sentence from a queued intent.
 */
data class BothLines(val arabic: String, val english: String) {
    val display: String get() = arabic + "\n" + english
}

object SyncCopy {
    fun cachedSnapshot(lastSuccessMs: Long): BothLines {
        val stamp = com.pixelplus.owner.domain.CairoClock.stamp(lastSuccessMs).cairoLocalIso
        return BothLines("نسخة محفوظة؛ قد تكون حالة الطلب تغيّرت. آخر اتصال بتوقيت القاهرة: $stamp", "Saved snapshot; request status may have changed. Last connection in Cairo: $stamp")
    }

    fun settingsChanged() = BothLines("تغيّرت إعدادات المركز. راجع الطلب مرة أخرى.", "Hub settings changed. Review the request again.")
    fun queued(action: String): BothLines = when (action) {
        OutboxRules.ACTION_APPROVE -> BothLines(
            "تم تسجيل الموافقة في قائمة الانتظار.",
            "Approval was added to the queue.",
        )
        else -> BothLines(
            "تم تسجيل الرفض في قائمة الانتظار.",
            "Refusal was added to the queue.",
        )
    }

    fun notRemote(): BothLines = BothLines(
        "لم يُؤكَّد على المركز بعد.",
        "Not confirmed on the hub yet.",
    )

    fun existing(status: String): BothLines = when (status) {
        OutboxStatus.CONFIRMED_REMOTE.name -> BothLines(
            "المركز أكّد هذا القرار من قبل. لم يُرسل مرة ثانية.",
            "The hub already confirmed this decision. It was not sent again.",
        )
        else -> BothLines(
            "القرار موجود في قائمة الانتظار. لم يُرسل مرة ثانية.",
            "This decision is already in the queue. It was not sent again.",
        )
    }

    fun queuedDisplay(action: String): String = queued(action).display + "\n" + notRemote().display

    fun queueLine(waiting: Int, oldestAgeMs: Long?): String {
        if (waiting <= 0) return ""
        val age = oldestAgeMs?.let { ageWords(it) }
        val arabic = if (age == null) {
            "في الانتظار على الهاتف: $waiting."
        } else {
            "في الانتظار على الهاتف: $waiting. الأقدم منذ ${age.arabic}."
        }
        val english = if (age == null) {
            "Waiting on this phone: $waiting."
        } else {
            "Waiting on this phone: $waiting. Oldest ${age.english}."
        }
        return arabic + "\n" + english
    }

    private fun ageWords(ageMs: Long): BothLines {
        val minutes = (ageMs.coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            minutes < 1 -> BothLines("أقل من دقيقة", "less than a minute ago")
            minutes < 60 -> BothLines("$minutes د", "$minutes min ago")
            else -> BothLines("${minutes / 60} س", "${minutes / 60} h ago")
        }
    }
}
