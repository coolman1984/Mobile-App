package com.pixelplus.owner.audit

/**
 * Safe before/after maps.
 *
 * A sensitive key is kept so the chain still shows that a field changed,
 * and its value is always the marker [REDACTED]. Unknown keys are dropped.
 * Amounts, notes, phones, tokens and exception text cannot be hashed.
 */
object AuditRedaction {
    const val REDACTED = "[redacted]"

    val ALLOWED_KEYS: Set<String> = setOf(
        "revision",
        "deleted",
        "localStatus",
        "fromStatus",
        "toStatus",
        "currency",
        "productCode",
        "displayName",
        "localTrialDays",
        "remoteTrialDays",
        "policyVersion",
        "source",
        "originEpoch",
        "normalizedUrl",
        "isDefault",
        "remoteType",
        "remoteId",
        "customerId",
        "branchId",
        "productId",
        "hubProfileId",
        "linkId",
        "workspaceId",
        "capabilitiesChanged",
        "renamed",
        "tested",
        "reversal",
        "op",
        "status",
        "attempts",
        "credentialEpoch",
        "queuedCount",
        "confirmedCount",
        "blockedCount",
    )

    private val SENSITIVE_KEYS: Set<String> = setOf(
        "name",
        "phone",
        "note",
        "body",
        "text",
        "reference",
        "amount",
        "amountminor",
        "token",
        "secret",
        "password",
        "bearer",
        "cookie",
        "session",
        "credential",
        "authorization",
        "code",
        "licencecode",
        "licensecode",
        "key",
        "privatekey",
        "exception",
        "stack",
        "message",
        "email",
        "address",
        "searchtoken",
        "normalizedsearchtoken",
        "ciphertext",
    )

    fun redact(input: Map<String, JsonValue>): Map<String, JsonValue> {
        val kept = linkedMapOf<String, JsonValue>()
        input.forEach { (key, value) ->
            val normal = key.trim()
            when {
                normal.lowercase() in SENSITIVE_KEYS -> kept[normal] = JsonValue.Text(REDACTED)
                normal in ALLOWED_KEYS -> kept[normal] = cleanValue(value)
            }
        }
        return kept
    }

    fun objectValue(fields: Map<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(redact(fields))

    /**
     * What a posted reversal may say in the chain.
     * The amount and the reference stay in the cash book, which is not this chain.
     */
    fun postedReversal(currency: String): Map<String, JsonValue> {
        val clean = currency.trim()
        val shown = if (clean.length == 3 && clean.all { it in 'A'..'Z' }) clean else REDACTED
        return linkedMapOf(
            "currency" to JsonValue.Text(shown),
            "reversal" to JsonValue.Bool(true),
        )
    }

    private fun cleanValue(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Text -> if (sensitiveText(value.value)) JsonValue.Text(REDACTED) else value
        is JsonValue.Obj -> JsonValue.Text(REDACTED)
        is JsonArray -> JsonValue.Text(REDACTED)
        else -> value
    }

    private fun sensitiveText(value: String): Boolean {
        val lower = value.lowercase()
        if ("bearer " in lower || "begin private" in lower || "private key" in lower) return true
        if (value.length >= 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return true
        return false
    }
}
