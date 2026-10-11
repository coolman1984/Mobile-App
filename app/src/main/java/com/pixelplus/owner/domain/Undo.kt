package com.pixelplus.owner.domain

/**
 * Local undo and cash reversal.
 *
 * A snackbar may undo for ten seconds. After that, the activity log may still
 * undo when nothing else has changed the record. A decision that already left
 * the phone cannot be undone. Cash is corrected by a new reversing entry.
 * The original amount stays where it was. Trash does not change a total.
 *
 * No Android types.
 */
enum class UndoChannel {
    SNACKBAR,
    ACTIVITY,
}

enum class DispatchPoint {
    QUEUED,
    LEASED,
    SENT,
    UNKNOWN,
}

data class UndoRequest(
    val undoClass: String,
    val expectedRevision: Long,
    val currentRevision: Long?,
    val expiresAtUtcMs: Long,
    val nowUtcMs: Long,
    val channel: UndoChannel,
    val dependentChange: Boolean,
    val used: Boolean,
    val invalidated: Boolean,
)

sealed class UndoDecision {
    data object Apply : UndoDecision()
    data object AlreadyUsed : UndoDecision()
    data object Invalidated : UndoDecision()
    data object MissingRecord : UndoDecision()
    data class Refused(val arabic: String, val english: String, val reason: String) : UndoDecision()
}

/** What the notebook did with one undo request. */
sealed class UndoOutcome {
    data class Done(val originalEventId: String) : UndoOutcome()
    data class Blocked(val arabic: String, val english: String, val reason: String) : UndoOutcome()
}

sealed class CancelDecision {
    data class Cancelled(val arabic: String, val english: String) : CancelDecision()
    data class Refused(val arabic: String, val english: String) : CancelDecision()
    data class Unknown(val arabic: String, val english: String) : CancelDecision()
}

data class CashPosting(
    val id: String,
    val customerId: String,
    val amountMinor: Long,
    val currency: String,
    val reversalOfId: String? = null,
    val correctionGroupId: String? = null,
)

data class LedgerView(
    val postings: List<CashPosting>,
    val hiddenCustomerIds: Set<String> = emptySet(),
)

sealed class ReversalResult {
    data class Posted(val reversal: CashPosting, val original: CashPosting) : ReversalResult()
    data class Rejected(val reason: String, val arabic: String, val english: String) : ReversalResult()
}

object Undo {
    const val WINDOW_MS: Long = 10_000L

    const val SENT_AR: String = "لا يمكن التراجع بعد الإرسال"
    const val SENT_EN: String = "Cannot undo after sending"

    const val CLASS_LOCAL: String = "local"
    const val CLASS_SENT: String = "sent"

    fun decide(request: UndoRequest): UndoDecision {
        if (request.used) return UndoDecision.AlreadyUsed
        if (request.invalidated) return UndoDecision.Invalidated
        if (request.undoClass == CLASS_SENT) {
            return UndoDecision.Refused(SENT_AR, SENT_EN, "sent")
        }
        if (request.undoClass != CLASS_LOCAL) {
            return UndoDecision.Refused(
                "تعذر التراجع.",
                "Undo cannot proceed.",
                "class",
            )
        }
        if (request.currentRevision == null) return UndoDecision.MissingRecord
        if (request.currentRevision != request.expectedRevision) {
            return UndoDecision.Refused(
                "تعذر التراجع لأن السجل تغيّر.",
                "Undo cannot proceed because the record changed.",
                "revision",
            )
        }
        if (request.channel == UndoChannel.SNACKBAR && request.nowUtcMs >= request.expiresAtUtcMs) {
            return UndoDecision.Refused(
                "انتهت مهلة التراجع.",
                "The undo window has ended.",
                "expired",
            )
        }
        if (request.channel == UndoChannel.ACTIVITY && request.dependentChange) {
            return UndoDecision.Refused(
                "تعذر التراجع لأن سجلًا تابعًا تغيّر.",
                "Undo cannot proceed because a related record changed.",
                "dependent",
            )
        }
        return UndoDecision.Apply
    }

    /**
     * Cancelling a queued send is not the inverse of the decision.
     * Once a worker has leased it, or the network result is unknown, leave it.
     */
    fun cancelUnsent(point: DispatchPoint): CancelDecision = when (point) {
        DispatchPoint.QUEUED -> CancelDecision.Cancelled(
            "تم إلغاء الإرسال قبل أن يخرج.",
            "The send was cancelled before it left the phone.",
        )
        DispatchPoint.LEASED -> CancelDecision.Refused(
            "تعذر الإلغاء لأن الإرسال بدأ.",
            "Cancellation was refused because sending has started.",
        )
        DispatchPoint.SENT -> CancelDecision.Refused(SENT_AR, SENT_EN)
        DispatchPoint.UNKNOWN -> CancelDecision.Unknown(
            "النتيجة غير معروفة. لا تتراجع.",
            "The outcome is unknown. Do not undo.",
        )
    }

    fun postReversal(
        original: CashPosting,
        reversalId: String,
        groupId: String,
        existing: List<CashPosting>,
    ): ReversalResult {
        if (!CURRENCY.matches(original.currency)) {
            return ReversalResult.Rejected(
                "currency",
                "العملة غير مقبولة.",
                "The currency is not accepted.",
            )
        }
        if (original.amountMinor == 0L || original.amountMinor == Long.MIN_VALUE) {
            return ReversalResult.Rejected(
                "amount",
                "المبلغ غير مقبول.",
                "The amount is not accepted.",
            )
        }
        if (reversalId.isBlank() || reversalId == original.id || existing.any { it.id == reversalId }) {
            return ReversalResult.Rejected(
                "id",
                "تعذر تسجيل القيد العكسي.",
                "The reversing entry could not be recorded.",
            )
        }
        if (groupId.isBlank()) {
            return ReversalResult.Rejected(
                "group",
                "تعذر تسجيل القيد العكسي.",
                "The reversing entry could not be recorded.",
            )
        }
        if (existing.any { it.reversalOfId == original.id }) {
            return ReversalResult.Rejected(
                "already_reversed",
                "هذا القيد له عكس بالفعل.",
                "This entry already has a reversal.",
            )
        }
        val reversal = CashPosting(
            id = reversalId,
            customerId = original.customerId,
            amountMinor = -original.amountMinor,
            currency = original.currency,
            reversalOfId = original.id,
            correctionGroupId = groupId,
        )
        return ReversalResult.Posted(reversal, original)
    }

    /**
     * Posted cash stays in the total after a customer is hidden.
     * [LedgerView.hiddenCustomerIds] is not a filter.
     */
    fun totalMinor(view: LedgerView, currency: String): Long {
        if (view.hiddenCustomerIds.isNotEmpty() && view.postings.isEmpty()) return 0L
        if (!CURRENCY.matches(currency)) return 0L
        return view.postings.filter { it.currency == currency }.sumOf { it.amountMinor }
    }

    private val CURRENCY = Regex("^[A-Z]{3}$")
}
