package com.pixelplus.owner.audit

import java.security.MessageDigest

/**
 * One link in the local audit chain, and the checks that keep the chain honest.
 *
 * entryHash is SHA-256 of the previous hash's 32 raw bytes followed by the
 * canonical entry bytes. The hash fields themselves are not inside those bytes.
 * A chain can show that a retained head was edited. It cannot stop a program
 * that rewrites every row and throws the old head away.
 */
data class AuditDraft(
    val schemaVersion: Int,
    val eventId: String,
    val chainId: String,
    val sequence: Long,
    val deviceInstanceId: String,
    val actorType: String,
    val workspaceId: String,
    val hubProfileId: String?,
    val occurredAtUtcMs: Long,
    val cairoLocalIso: String,
    val cairoOffsetSeconds: Int,
    val zoneId: String,
    val screen: String,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val beforeSafe: Map<String, JsonValue>,
    val afterSafe: Map<String, JsonValue>,
    val result: String,
    val reasonCode: String?,
    val explanationAr: String,
    val explanationEn: String,
    val explanationTemplateVersion: Int,
    val correlationId: String,
    val originalEventId: String?,
    val operationId: String?,
    val canonicalVersion: Int,
)

data class ChainLink(
    val previousHash: String,
    val entryHash: String,
    val canonicalBytes: ByteArray,
)

enum class ChainFault {
    TAMPER,
    GAP,
    REORDER,
    REPLAY,
    GENESIS,
    PREDECESSOR,
    SHAPE,
}

data class ChainReport(
    val faults: List<ChainFault>,
    val lastSequence: Long?,
) {
    val ok: Boolean get() = faults.isEmpty()
}

object AuditHash {
    const val GENESIS: String = "0000000000000000000000000000000000000000000000000000000000000000"
    const val MAX_CANONICAL_BYTES: Int = 16 * 1024

    fun of(previousHex: String, canonical: ByteArray): String {
        val previous = decodeHex(previousHex)
        require(previous.size == 32) { "previous hash" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(previous)
        digest.update(canonical)
        return digest.digest().toHex()
    }

    fun decodeHex(hex: String): ByteArray {
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw IllegalArgumentException("hash")
        }
        return ByteArray(32) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

object AuditCanonical {
    const val SCHEMA_VERSION: Int = 1
    const val CANONICAL_VERSION: Int = 1
    const val FIELD_COUNT: Int = 27

    fun bytes(draft: AuditDraft): ByteArray {
        val array = JsonArray(
            listOf(
                JsonValue.Int64(draft.schemaVersion.toLong()),
                JsonValue.Text(draft.eventId),
                JsonValue.Text(draft.chainId),
                JsonValue.Int64(draft.sequence),
                JsonValue.Text(draft.deviceInstanceId),
                JsonValue.Text(draft.actorType),
                JsonValue.Text(draft.workspaceId),
                draft.hubProfileId.toJson(),
                JsonValue.Int64(draft.occurredAtUtcMs),
                JsonValue.Text(draft.cairoLocalIso),
                JsonValue.Int64(draft.cairoOffsetSeconds.toLong()),
                JsonValue.Text(draft.zoneId),
                JsonValue.Text(draft.screen),
                JsonValue.Text(draft.action),
                JsonValue.Text(draft.entityType),
                draft.entityId.toJson(),
                JsonValue.Obj(draft.beforeSafe),
                JsonValue.Obj(draft.afterSafe),
                JsonValue.Text(draft.result),
                draft.reasonCode.toJson(),
                JsonValue.Text(draft.explanationAr),
                JsonValue.Text(draft.explanationEn),
                JsonValue.Int64(draft.explanationTemplateVersion.toLong()),
                JsonValue.Text(draft.correlationId),
                draft.originalEventId.toJson(),
                draft.operationId.toJson(),
                JsonValue.Int64(draft.canonicalVersion.toLong()),
            ),
        )
        return CanonicalJson.bytes(array)
    }

    fun text(draft: AuditDraft): String = bytes(draft).toString(Charsets.UTF_8)

    fun objectText(fields: Map<String, JsonValue>): String = CanonicalJson.text(JsonValue.Obj(fields))

    fun objectBytes(fields: Map<String, JsonValue>): ByteArray = objectText(fields).toByteArray(Charsets.UTF_8)

    fun parseObject(bytes: ByteArray): Map<String, JsonValue> {
        val value = CanonicalJson.parse(bytes)
        return (value as? JsonValue.Obj)?.fields ?: throw IllegalArgumentException("bad json: object")
    }

    fun link(draft: AuditDraft, previousHash: String): ChainLink {
        val canonical = bytes(draft)
        require(canonical.size <= AuditHash.MAX_CANONICAL_BYTES) { "audit entry is too large" }
        return ChainLink(
            previousHash = previousHash,
            entryHash = AuditHash.of(previousHash, canonical),
            canonicalBytes = canonical,
        )
    }

    /**
     * Check one presented order. Sorting is used only to name a gap.
     * A list that is not already in sequence order is a reorder, even when
     * the same rows would verify after a sort.
     */
    fun verify(links: List<ChainLink>): ChainReport {
        if (links.isEmpty()) return ChainReport(emptyList(), null)
        val faults = linkedSetOf<ChainFault>()
        val drafts = links.map { link ->
            val parsed = runCatching { draftOf(link.canonicalBytes) }.getOrNull()
            if (parsed == null) {
                faults += ChainFault.SHAPE
                null
            } else {
                val again = bytes(parsed)
                if (!again.contentEquals(link.canonicalBytes)) faults += ChainFault.SHAPE
                val hash = runCatching { AuditHash.of(link.previousHash, link.canonicalBytes) }.getOrNull()
                if (hash == null || hash != link.entryHash) faults += ChainFault.TAMPER
                parsed
            }
        }
        val eventIds = drafts.mapNotNull { it?.eventId }
        if (eventIds.size != eventIds.toSet().size) faults += ChainFault.REPLAY
        val sequences = drafts.map { it?.sequence }
        val known = drafts.filterNotNull()
        if (known.size == links.size && known.map { it.sequence } != known.sortedBy { it.sequence }.map { it.sequence }) {
            faults += ChainFault.REORDER
        }
        val ordered = known.sortedBy { it.sequence }
        if (ordered.isNotEmpty()) {
            val first = ordered.first()
            val firstLink = links[drafts.indexOfFirst { it?.eventId == first.eventId && it.sequence == first.sequence }]
            if (first.sequence != 1L || firstLink.previousHash != AuditHash.GENESIS) faults += ChainFault.GENESIS
        }
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            if (current.sequence != previous.sequence + 1L) faults += ChainFault.GAP
            val previousLink = linkFor(links, drafts, previous)
            val currentLink = linkFor(links, drafts, current)
            if (previousLink != null && currentLink != null && currentLink.previousHash != previousLink.entryHash) {
                faults += ChainFault.PREDECESSOR
            }
        }
        if (sequences.filterNotNull().let { it.size != it.toSet().size }) faults += ChainFault.GAP
        return ChainReport(faults.toList(), ordered.lastOrNull()?.sequence)
    }

    private fun linkFor(links: List<ChainLink>, drafts: List<AuditDraft?>, draft: AuditDraft): ChainLink? {
        val index = drafts.indexOfFirst { it?.eventId == draft.eventId && it.sequence == draft.sequence }
        return links.getOrNull(index)
    }

    fun draftOf(bytes: ByteArray): AuditDraft {
        val items = CanonicalJson.parse(bytes).arrayItems()
        if (items.size != FIELD_COUNT) throw IllegalArgumentException("bad json: width")
        return AuditDraft(
            schemaVersion = items[0].long().toIntChecked(),
            eventId = items[1].text(),
            chainId = items[2].text(),
            sequence = items[3].long(),
            deviceInstanceId = items[4].text(),
            actorType = items[5].text(),
            workspaceId = items[6].text(),
            hubProfileId = items[7].textOrNull(),
            occurredAtUtcMs = items[8].long(),
            cairoLocalIso = items[9].text(),
            cairoOffsetSeconds = items[10].long().toIntChecked(),
            zoneId = items[11].text(),
            screen = items[12].text(),
            action = items[13].text(),
            entityType = items[14].text(),
            entityId = items[15].textOrNull(),
            beforeSafe = items[16].obj(),
            afterSafe = items[17].obj(),
            result = items[18].text(),
            reasonCode = items[19].textOrNull(),
            explanationAr = items[20].text(),
            explanationEn = items[21].text(),
            explanationTemplateVersion = items[22].long().toIntChecked(),
            correlationId = items[23].text(),
            originalEventId = items[24].textOrNull(),
            operationId = items[25].textOrNull(),
            canonicalVersion = items[26].long().toIntChecked(),
        )
    }

    private fun String?.toJson(): JsonValue = if (this == null) JsonValue.Null else JsonValue.Text(this)

    private fun JsonValue.long(): Long = (this as? JsonValue.Int64)?.value
        ?: throw IllegalArgumentException("bad json: int")

    private fun JsonValue.text(): String = (this as? JsonValue.Text)?.value
        ?: throw IllegalArgumentException("bad json: text")

    private fun JsonValue.textOrNull(): String? = when (this) {
        is JsonValue.Null -> null
        is JsonValue.Text -> value
        else -> throw IllegalArgumentException("bad json: optional")
    }

    private fun JsonValue.obj(): Map<String, JsonValue> = (this as? JsonValue.Obj)?.fields
        ?: throw IllegalArgumentException("bad json: object")

    private fun Long.toIntChecked(): Int {
        if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) throw IllegalArgumentException("bad json: int width")
        return toInt()
    }
}
