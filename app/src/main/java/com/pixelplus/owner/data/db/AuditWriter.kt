package com.pixelplus.owner.data.db

import android.database.sqlite.SQLiteConstraintException
import com.pixelplus.owner.audit.AuditCanonical
import com.pixelplus.owner.audit.AuditDraft
import com.pixelplus.owner.audit.AuditHash
import com.pixelplus.owner.audit.AuditRedaction
import com.pixelplus.owner.audit.AuditText
import com.pixelplus.owner.audit.AuditVocab
import com.pixelplus.owner.audit.ChainLink
import com.pixelplus.owner.audit.ChainReport
import com.pixelplus.owner.audit.JsonValue
import com.pixelplus.owner.domain.CairoClock
import com.pixelplus.owner.domain.Undo

/**
 * Appends one safe bilingual audit entry inside the caller's transaction.
 * The sequence is read and written here, so two writers cannot both take
 * the same link. The canonical body is sealed before it is stored.
 */
internal data class PendingUndo(
    val undoClass: String,
    val expectedRevision: Long,
    val inverse: Map<String, JsonValue>,
)

internal data class AuditSpec(
    val screen: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val workspaceId: String,
    val hubProfileId: String?,
    val nowUtcMs: Long,
    val before: Map<String, JsonValue>,
    val after: Map<String, JsonValue>,
    val result: String,
    val reasonCode: String? = null,
    val undo: PendingUndo? = null,
    val originalEventId: String? = null,
    val correlationId: String? = null,
)

internal class AuditWriter(private val database: OwnerDatabase) {
    private val dao get() = database.auditDao()

    fun verify(): ChainReport {
        val chain = dao.chain() ?: return ChainReport(emptyList(), null)
        val runtime = database.auditRuntime
        val links = dao.entries(chain.chainId).map { row ->
            val opened = runCatching {
                check(row.keyAliasVersion == runtime.sealer.keyAliasVersion)
                runtime.sealer.open(row.canonicalCiphertext)
            }.getOrDefault(ByteArray(0))
            ChainLink(row.previousHash, row.entryHash, opened)
        }
        return AuditCanonical.verify(links)
    }

    fun append(spec: AuditSpec) {
        require(spec.screen in AuditVocab.SCREENS) { "audit screen" }
        require(spec.action in AuditVocab.ACTIONS) { "audit action" }
        require(spec.entityType in AuditVocab.ENTITIES) { "audit entity" }
        require(spec.result in AuditVocab.RESULTS) { "audit result" }
        require(RecordRules.validId(spec.entityId)) { "audit entity id" }
        require(RecordRules.validId(spec.workspaceId)) { "audit workspace" }
        require(spec.hubProfileId == null || RecordRules.validId(spec.hubProfileId)) { "audit hub" }
        require(spec.reasonCode == null || REASON.matches(spec.reasonCode)) { "audit reason" }
        val runtime = database.auditRuntime
        val chain = ensureChain(spec.nowUtcMs)
        val head = dao.head(chain.chainId)
        val sequence = (head?.sequence ?: 0L) + 1L
        val previous = head?.entryHash ?: AuditHash.GENESIS
        val eventId = runtime.ids.next()
        require(RecordRules.validId(eventId)) { "audit event id" }
        val correlationId = spec.correlationId ?: eventId
        require(RecordRules.validId(correlationId)) { "audit correlation" }
        val recordUndo = spec.undo != null && spec.result == AuditVocab.RESULT_SUCCESS
        val operationId = if (recordUndo) runtime.ids.next() else null
        if (operationId != null) require(RecordRules.validId(operationId)) { "audit operation" }
        val stamp = CairoClock.stamp(spec.nowUtcMs)
        val text = AuditText.explain(spec.action, spec.entityType, spec.result, spec.reasonCode)
        val before = AuditRedaction.redact(spec.before)
        val after = AuditRedaction.redact(spec.after)
        val draft = AuditDraft(
            schemaVersion = AuditCanonical.SCHEMA_VERSION,
            eventId = eventId,
            chainId = chain.chainId,
            sequence = sequence,
            deviceInstanceId = chain.deviceInstanceId,
            actorType = chain.actorType,
            workspaceId = spec.workspaceId,
            hubProfileId = spec.hubProfileId,
            occurredAtUtcMs = stamp.occurredAtUtcMs,
            cairoLocalIso = stamp.cairoLocalIso,
            cairoOffsetSeconds = stamp.cairoOffsetSeconds,
            zoneId = stamp.zoneId,
            screen = spec.screen,
            action = spec.action,
            entityType = spec.entityType,
            entityId = spec.entityId,
            beforeSafe = before,
            afterSafe = after,
            result = spec.result,
            reasonCode = spec.reasonCode,
            explanationAr = text.arabic,
            explanationEn = text.english,
            explanationTemplateVersion = text.templateVersion,
            correlationId = correlationId,
            originalEventId = spec.originalEventId,
            operationId = operationId,
            canonicalVersion = AuditCanonical.CANONICAL_VERSION,
        )
        val link = AuditCanonical.link(draft, previous)
        val sealed = runtime.sealer.seal(link.canonicalBytes)
        dao.insertEntry(
            AuditEntryEntity(
                eventId = eventId,
                schemaVersion = draft.schemaVersion,
                chainId = chain.chainId,
                sequence = sequence,
                deviceInstanceId = chain.deviceInstanceId,
                actorType = chain.actorType,
                workspaceId = spec.workspaceId,
                hubProfileId = spec.hubProfileId,
                occurredAtUtcMs = stamp.occurredAtUtcMs,
                cairoLocalIso = stamp.cairoLocalIso,
                cairoOffsetSeconds = stamp.cairoOffsetSeconds,
                zoneId = stamp.zoneId,
                screen = spec.screen,
                action = spec.action,
                entityType = spec.entityType,
                entityId = spec.entityId,
                beforeSafe = AuditCanonical.objectText(before),
                afterSafe = AuditCanonical.objectText(after),
                result = spec.result,
                reasonCode = spec.reasonCode,
                explanationAr = text.arabic,
                explanationEn = text.english,
                explanationTemplateVersion = text.templateVersion,
                correlationId = correlationId,
                originalEventId = spec.originalEventId,
                operationId = operationId,
                previousHash = previous,
                entryHash = link.entryHash,
                canonicalVersion = draft.canonicalVersion,
                canonicalCiphertext = sealed,
                keyAliasVersion = runtime.sealer.keyAliasVersion,
            ),
        )
        val undo = spec.undo
        if (recordUndo) {
            val pending = checkNotNull(undo)
            val operation = checkNotNull(operationId)
            require(pending.undoClass in AuditVocab.UNDO_CLASSES) { "undo class" }
            require(pending.expectedRevision >= 1L) { "undo revision" }
            val inverse = runtime.sealer.seal(AuditCanonical.objectBytes(pending.inverse))
            dao.insertUndo(
                UndoRecordEntity(
                    operationId = operation,
                    eventId = eventId,
                    workspaceId = spec.workspaceId,
                    entityType = spec.entityType,
                    entityId = spec.entityId,
                    inverseCiphertext = inverse,
                    expectedRevision = pending.expectedRevision,
                    expiresAtUtcMs = spec.nowUtcMs + Undo.WINDOW_MS,
                    usedAtUtcMs = null,
                    invalidatedAtUtcMs = null,
                    undoClass = pending.undoClass,
                    createdAtUtcMs = spec.nowUtcMs,
                    keyAliasVersion = runtime.sealer.keyAliasVersion,
                ),
            )
        }
    }

    private fun ensureChain(nowUtcMs: Long): AuditChainEntity {
        dao.chain()?.let { return it }
        val runtime = database.auditRuntime
        val chainId = runtime.ids.next()
        val deviceId = runtime.ids.next()
        require(RecordRules.validId(chainId) && RecordRules.validId(deviceId)) { "audit chain id" }
        val row = AuditChainEntity(
            singleton = 1,
            chainId = chainId,
            deviceInstanceId = deviceId,
            actorType = AuditVocab.ACTOR_OWNER,
            createdAtUtcMs = nowUtcMs,
        )
        dao.insertChain(row)
        return row
    }

    companion object {
        private val REASON = Regex("^[a-z0-9_]{1,40}$")

        fun isChainRace(error: SQLiteConstraintException): Boolean {
            val message = error.message.orEmpty()
            if ("bad_audit" in message || "audit_link" in message || "audit_immutable" in message) return false
            if ("bad_chain" in message || "bad_undo" in message || "undo_frozen" in message) return false
            val auditUnique = "UNIQUE" in message && "audit_entries" in message
            val chainPrimary = ("PRIMARY KEY" in message || "UNIQUE" in message) && "audit_chain" in message
            return auditUnique || chainPrimary
        }
    }
}
