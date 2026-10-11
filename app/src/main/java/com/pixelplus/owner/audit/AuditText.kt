package com.pixelplus.owner.audit

/**
 * Both sentences are stored on every entry. The phone language does not choose one.
 * Templates are fixed so a later export hashes the same words.
 */
data class AuditExplanation(
    val arabic: String,
    val english: String,
    val templateVersion: Int = AuditText.TEMPLATE_VERSION,
)

object AuditVocab {
    const val ACTOR_OWNER = "owner"

    const val SCREEN_NOTEBOOK = "notebook"
    const val SCREEN_CUSTOMERS = "customers"
    const val SCREEN_PRODUCTS = "products"
    const val SCREEN_HUB = "hub"
    const val SCREEN_MONEY = "money"
    const val SCREEN_ACTIVITY = "activity"
    const val SCREEN_REQUESTS = "requests"

    const val ACTION_CREATE = "create"
    const val ACTION_UPDATE = "update"
    const val ACTION_SOFT_DELETE = "soft_delete"
    const val ACTION_RESTORE = "restore"
    const val ACTION_UNDO = "undo"
    const val ACTION_REVERSAL = "reversal"
    const val ACTION_CANCEL = "cancel"

    const val ENTITY_WORKSPACE = "workspace"
    const val ENTITY_CUSTOMER = "customer"
    const val ENTITY_BRANCH = "branch"
    const val ENTITY_PRODUCT = "product"
    const val ENTITY_HUB = "hub"
    const val ENTITY_LINK = "link"
    const val ENTITY_PAYMENT = "payment"
    const val ENTITY_DECISION = "decision"

    const val RESULT_INTENT = "intent"
    const val RESULT_SUCCESS = "success"
    const val RESULT_FAILURE = "failure"
    const val RESULT_CANCELLED = "cancelled"
    const val RESULT_CONFLICT = "conflict"

    const val UNDO_LOCAL = "local"
    const val UNDO_SENT = "sent"

    val SCREENS = setOf(
        SCREEN_NOTEBOOK,
        SCREEN_CUSTOMERS,
        SCREEN_PRODUCTS,
        SCREEN_HUB,
        SCREEN_MONEY,
        SCREEN_ACTIVITY,
        SCREEN_REQUESTS,
    )
    val ACTIONS = setOf(
        ACTION_CREATE,
        ACTION_UPDATE,
        ACTION_SOFT_DELETE,
        ACTION_RESTORE,
        ACTION_UNDO,
        ACTION_REVERSAL,
        ACTION_CANCEL,
    )
    val ENTITIES = setOf(
        ENTITY_WORKSPACE,
        ENTITY_CUSTOMER,
        ENTITY_BRANCH,
        ENTITY_PRODUCT,
        ENTITY_HUB,
        ENTITY_LINK,
        ENTITY_PAYMENT,
        ENTITY_DECISION,
    )
    val RESULTS = setOf(
        RESULT_INTENT,
        RESULT_SUCCESS,
        RESULT_FAILURE,
        RESULT_CANCELLED,
        RESULT_CONFLICT,
    )
    val UNDO_CLASSES = setOf(UNDO_LOCAL, UNDO_SENT)
}

object AuditText {
    const val TEMPLATE_VERSION: Int = 1

    fun explain(action: String, entityType: String, result: String, reasonCode: String? = null): AuditExplanation {
        if (entityType == AuditVocab.ENTITY_DECISION && result == AuditVocab.RESULT_INTENT && reasonCode == "approve") {
            return AuditExplanation(
                "تم تسجيل الموافقة في قائمة الانتظار.",
                "Approval was added to the queue.",
            )
        }
        if (entityType == AuditVocab.ENTITY_DECISION && result == AuditVocab.RESULT_INTENT && reasonCode == "refuse") {
            return AuditExplanation(
                "تم تسجيل الرفض في قائمة الانتظار.",
                "Refusal was added to the queue.",
            )
        }
        if (entityType == AuditVocab.ENTITY_DECISION && result == AuditVocab.RESULT_CONFLICT && reasonCode == "duplicate") {
            return AuditExplanation(
                "القرار موجود في قائمة الانتظار. لم يُضف مرة ثانية.",
                "This decision is already in the queue. It was not added again.",
            )
        }
        if (entityType == AuditVocab.ENTITY_DECISION && reasonCode == "remote_confirmed") {
            return AuditExplanation("أكّد المركز حالة القرار. الموافقة لا تعني إصدار كود.", "The hub confirmed the decision state. Approval does not mean a code was issued.")
        }
        if (entityType == AuditVocab.ENTITY_DECISION && reasonCode == "queue_state") {
            return AuditExplanation("تم تحديث حالة القرار في قائمة الانتظار على الهاتف.", "The decision state in this phone's queue was updated.")
        }
        if (entityType == AuditVocab.ENTITY_DECISION && reasonCode == "sync_run") {
            return AuditExplanation(
                "انتهت محاولة الإرسال. الأرقام هي النجاح والتعثر والانتظار فقط.",
                "The send attempt finished. The figures are only success, blocks and waiting.",
            )
        }
        if (entityType == AuditVocab.ENTITY_HUB && reasonCode == "credential") {
            return AuditExplanation(
                "تم استبدال رمز الدخول. القيمة غير محفوظة في السجل.",
                "The sign-in credential was replaced. The value is not in the record.",
            )
        }
        if (entityType == AuditVocab.ENTITY_HUB && reasonCode == "resume") {
            return AuditExplanation(
                "تمت مراجعة الاتصال. قائمة الانتظار يمكن أن تكمل لنفس المركز.",
                "The connection was checked. The queue may continue for the same hub.",
            )
        }
        if (entityType == AuditVocab.ENTITY_HUB && reasonCode == "origin_changed") {
            return AuditExplanation(
                "تغيّر عنوان المركز. القرارات القديمة لا تُرسل إلى العنوان الجديد.",
                "The hub address changed. Older decisions are not sent to the new address.",
            )
        }
        if (result == AuditVocab.RESULT_CONFLICT) {
            return AuditExplanation(
                "تعذر الحفظ لأن السجل موجود.",
                "The save was refused because the record already exists.",
            )
        }
        if (result == AuditVocab.RESULT_FAILURE) {
            return AuditExplanation(
                "تعذر تنفيذ الإجراء.",
                "The action could not be completed.",
            )
        }
        if (result == AuditVocab.RESULT_CANCELLED) {
            return AuditExplanation(
                "تم إلغاء الإجراء قبل إتمامه.",
                "The action was cancelled before it finished.",
            )
        }
        return when (action) {
            AuditVocab.ACTION_SOFT_DELETE -> AuditExplanation(
                "تم نقل السجل إلى المحذوفات.",
                "Record was moved to Trash.",
            )
            AuditVocab.ACTION_RESTORE -> AuditExplanation(
                "تمت استعادة السجل.",
                "Record was restored.",
            )
            AuditVocab.ACTION_UNDO -> AuditExplanation(
                "تم التراجع عن التغيير المحلي.",
                "The local change was undone.",
            )
            AuditVocab.ACTION_REVERSAL -> AuditExplanation(
                "تم تسجيل قيد عكسي. المبلغ الأصلي بقي كما هو.",
                "A reversing entry was recorded. The original amount was kept.",
            )
            AuditVocab.ACTION_CANCEL -> AuditExplanation(
                "تم إلغاء الإرسال قبل أن يخرج.",
                "The send was cancelled before it left the phone.",
            )
            AuditVocab.ACTION_CREATE -> create(entityType)
            AuditVocab.ACTION_UPDATE -> update(entityType)
            else -> AuditExplanation("تم تسجيل الإجراء.", "The action was recorded.")
        }
    }

    private fun create(entityType: String): AuditExplanation = when (entityType) {
        AuditVocab.ENTITY_WORKSPACE -> AuditExplanation("تم إنشاء مساحة العمل.", "Workspace was created.")
        AuditVocab.ENTITY_CUSTOMER -> AuditExplanation("تم إنشاء سجل العميل.", "Customer record was created.")
        AuditVocab.ENTITY_BRANCH -> AuditExplanation("تم إنشاء الفرع.", "Branch was created.")
        AuditVocab.ENTITY_PRODUCT -> AuditExplanation("تم إنشاء المنتج.", "Product was created.")
        AuditVocab.ENTITY_HUB -> AuditExplanation("تم حفظ عنوان المركز.", "Hub address was saved.")
        AuditVocab.ENTITY_LINK -> AuditExplanation("تم ربط العميل بسجل في المركز.", "Customer was linked to a hub record.")
        AuditVocab.ENTITY_PAYMENT -> AuditExplanation("تم تسجيل دفعة محلية.", "A local payment was recorded.")
        else -> AuditExplanation("تم إنشاء السجل.", "Record was created.")
    }

    private fun update(entityType: String): AuditExplanation = when (entityType) {
        AuditVocab.ENTITY_WORKSPACE -> AuditExplanation("تم تغيير مساحة العمل.", "Workspace was changed.")
        AuditVocab.ENTITY_CUSTOMER -> AuditExplanation("تم تغيير حالة العميل.", "Customer status was changed.")
        AuditVocab.ENTITY_HUB -> AuditExplanation("تم تغيير عنوان المركز.", "Hub address was changed.")
        AuditVocab.ENTITY_PRODUCT -> AuditExplanation("تم تغيير المنتج.", "Product was changed.")
        else -> AuditExplanation("تم تغيير السجل.", "Record was changed.")
    }
}

object AuditExport {
    fun json(links: List<ChainLink>, report: ChainReport): String {
        val rows = links.joinToString(",") { link ->
            link.canonicalBytes.toString(Charsets.UTF_8)
        }
        val faults = report.faults.joinToString(",") { "\"${it.name.lowercase()}\"" }
        return "{\"ok\":${report.ok},\"faults\":[$faults],\"entries\":[$rows]}"
    }

    fun markdown(links: List<ChainLink>, report: ChainReport): String {
        val lines = ArrayList<String>(links.size * 4 + 4)
        lines += "# سجل النشاط / Activity"
        lines += ""
        lines += if (report.ok) "التحقق: سليم / Verification: ok" else "التحقق: غير سليم / Verification: mismatch"
        links.forEach { link ->
            val draft = runCatching { AuditCanonical.draftOf(link.canonicalBytes) }.getOrNull()
            if (draft == null) {
                lines += ""
                lines += "- سطر غير صالح / Unreadable entry"
                return@forEach
            }
            lines += ""
            lines += "- ${draft.cairoLocalIso} · ${draft.screen} · ${draft.action} · ${draft.result}"
            lines += "  - ${draft.explanationAr}"
            lines += "  - ${draft.explanationEn}"
        }
        return lines.joinToString("\n")
    }
}
