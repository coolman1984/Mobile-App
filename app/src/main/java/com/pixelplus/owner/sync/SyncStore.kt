package com.pixelplus.owner.sync

import com.pixelplus.owner.LicenceRequest
import com.pixelplus.owner.OwnerAction
import com.pixelplus.owner.UrlResult
import com.pixelplus.owner.data.db.AuditSpec
import com.pixelplus.owner.audit.AuditVocab
import com.pixelplus.owner.audit.JsonValue
import com.pixelplus.owner.data.db.AuditWriter
import com.pixelplus.owner.data.db.HubEventEntity
import com.pixelplus.owner.data.db.LicenceRequestEntity
import com.pixelplus.owner.data.db.MailboxReceiptEntity
import com.pixelplus.owner.data.db.NotificationReceiptEntity
import com.pixelplus.owner.data.db.OutboxOperationEntity
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.RecordResult
import com.pixelplus.owner.data.db.RecordRules
import com.pixelplus.owner.data.db.SyncCursorEntity
import com.pixelplus.owner.decideBody
import com.pixelplus.owner.normalizeHubUrl
import java.util.concurrent.Callable

internal data class BoundProfile(
    val profileId: String,
    val workspaceId: String,
    val originEpoch: Long,
    val credentialEpoch: Long,
    val paused: Boolean,
    val url: String,
)

internal sealed class QueueOutcome {
    data class Queued(val operationId: String, val display: String) : QueueOutcome()
    data class Existing(val operationId: String, val status: String, val display: String) : QueueOutcome()
    data class Rejected(val reason: String) : QueueOutcome()
}

internal data class CachedRequest(
    val remoteId: String,
    val productCode: String,
    val deviceCode: String,
    val kind: String,
    val serverStatus: String,
    val ownerDecision: String?,
    val ownerDecidedAt: Long?,
    val createdServerAt: Long,
)

internal data class CachedEvent(
    val remoteId: String,
    val requestRemoteId: String?,
    val eventType: String,
    val eventAt: Long,
    val detail: String,
)

internal data class CachedBatch(
    val batchId: String,
    val installRemoteId: String,
    val receivedAt: Long,
    val byteCount: Long,
    val known: Int,
)

/**
 * Writes the queue, the cursors and the cached hub pages.
 * A cursor moves only in the same transaction as the rows it covers.
 */
internal class SyncStore(private val database: OwnerDatabase) {
    private val dao get() = database.syncDao()

    fun ensureProfile(rawUrl: String, nowMs: Long): BoundProfile? {
        val url = when (val parsed = normalizeHubUrl(rawUrl)) {
            is UrlResult.Ok -> parsed.url
            is UrlResult.Reject -> return null
        }
        if (nowMs < 0) return null
        val workspaceId = workspaceId(nowMs) ?: return null
        dao.activeHub(workspaceId, url)?.let { return bind(it.id) }
        val id = database.auditRuntime.ids.next()
        if (!RecordRules.validId(id)) return null
        val created = database.records().createHubProfile(id, workspaceId, url, nowMs)
        if (created != RecordResult.Applied) return dao.activeHub(workspaceId, url)?.let { bind(it.id) }
        return bind(id)
    }

    fun bind(profileId: String): BoundProfile? {
        val row = dao.hub(profileId) ?: return null
        if (row.deletedAtUtcMs != null) return null
        return BoundProfile(
            profileId = row.id,
            workspaceId = row.workspaceId,
            originEpoch = row.originEpoch,
            credentialEpoch = row.credentialEpoch,
            paused = row.dispatchPaused != 0,
            url = row.normalizedUrl,
        )
    }

    fun profileForUrl(rawUrl: String): BoundProfile? {
        val url = when (val parsed = normalizeHubUrl(rawUrl)) {
            is UrlResult.Ok -> parsed.url
            is UrlResult.Reject -> return null
        }
        val workspace = dao.firstWorkspace() ?: return null
        val row = dao.activeHub(workspace.id, url) ?: return null
        return bind(row.id)
    }

    fun pauseCredential(profileId: String, nowMs: Long): Boolean = writeBool {
        val row = dao.hub(profileId) ?: return@writeBool false
        if (row.deletedAtUtcMs != null || nowMs < row.createdAtUtcMs) return@writeBool false
        if (dao.pauseCredential(profileId, nowMs) != 1) return@writeBool false
        note(
            screen = AuditVocab.SCREEN_HUB,
            action = AuditVocab.ACTION_UPDATE,
            entityType = AuditVocab.ENTITY_HUB,
            entityId = profileId,
            workspaceId = row.workspaceId,
            hubProfileId = profileId,
            nowMs = nowMs,
            result = AuditVocab.RESULT_SUCCESS,
            reasonCode = "credential",
            before = mapOf("credentialEpoch" to JsonValue.Int64(row.credentialEpoch)),
            after = mapOf("credentialEpoch" to JsonValue.Int64(row.credentialEpoch + 1)),
        )
        true
    }

    fun changeOrigin(profileId: String, rawUrl: String, nowMs: Long): BoundProfile? {
        val url = when (val parsed = normalizeHubUrl(rawUrl)) {
            is UrlResult.Ok -> parsed.url
            is UrlResult.Reject -> return null
        }
        val ok = writeBool {
            val row = dao.hub(profileId) ?: return@writeBool false
            if (nowMs < row.createdAtUtcMs) return@writeBool false
            val epoch = row.originEpoch + 1
            if (dao.changeOrigin(profileId, url, epoch, nowMs) != 1) return@writeBool false
            dao.openForProfile(profileId).forEach { operation ->
                if (operation.originEpoch == epoch) return@forEach
                val state = operation.toState()
                val moved = OutboxMachine.review(state, "origin_changed")
                if (moved is MachineMove.Ok) {
                    write(operation, moved.state)
                }
            }
            note(
                screen = AuditVocab.SCREEN_HUB,
                action = AuditVocab.ACTION_UPDATE,
                entityType = AuditVocab.ENTITY_HUB,
                entityId = profileId,
                workspaceId = row.workspaceId,
                hubProfileId = profileId,
                nowMs = nowMs,
                result = AuditVocab.RESULT_SUCCESS,
                reasonCode = "origin_changed",
                before = mapOf("originEpoch" to JsonValue.Int64(row.originEpoch)),
                after = mapOf(
                    "originEpoch" to JsonValue.Int64(epoch),
                    "normalizedUrl" to JsonValue.Text(url),
                ),
            )
            true
        }
        return if (ok) bind(profileId) else null
    }

    fun resume(profileId: String, nowMs: Long): Boolean = writeBool {
        val row = dao.hub(profileId) ?: return@writeBool false
        if (row.deletedAtUtcMs != null || nowMs < row.createdAtUtcMs) return@writeBool false
        if (row.dispatchPaused != 0 && dao.setPaused(profileId, 0, nowMs) != 1) return@writeBool false
        dao.openForProfile(profileId).forEach { operation ->
            if (operation.originEpoch != row.originEpoch) return@forEach
            val moved = OutboxMachine.rebind(operation.toState(), row.credentialEpoch)
            if (moved is MachineMove.Ok) write(operation, moved.state)
        }
        note(
            screen = AuditVocab.SCREEN_HUB,
            action = AuditVocab.ACTION_UPDATE,
            entityType = AuditVocab.ENTITY_HUB,
            entityId = profileId,
            workspaceId = row.workspaceId,
            hubProfileId = profileId,
            nowMs = nowMs,
            result = AuditVocab.RESULT_SUCCESS,
            reasonCode = "resume",
            after = mapOf(
                "credentialEpoch" to JsonValue.Int64(row.credentialEpoch),
                "originEpoch" to JsonValue.Int64(row.originEpoch),
            ),
        )
        true
    }

    fun enqueue(
        profile: BoundProfile,
        request: LicenceRequest,
        action: OwnerAction,
        nowMs: Long,
    ): QueueOutcome {
        val verb = if (action == OwnerAction.APPROVE) OutboxRules.ACTION_APPROVE else OutboxRules.ACTION_REFUSE
        if (!RecordRules.validId(request.id) || nowMs < 0) return QueueOutcome.Rejected("bad_id")
        if (RecordRules.productCode(request.product) == null) return QueueOutcome.Rejected("bad_product")
        if (request.device.isBlank() || request.device.length > 20) return QueueOutcome.Rejected("bad_device")
        if (request.kind.isBlank() || request.kind.length > 20) return QueueOutcome.Rejected("bad_kind")
        if (request.createdAt < 0) return QueueOutcome.Rejected("bad_time")
        val body = decideBody(request.id, action) ?: return QueueOutcome.Rejected("bad_id")
        if (body.contains("\"code\"")) return QueueOutcome.Rejected("code")
        val prefix = decisionPrefix(request.id, verb) + "%"
        val activePrefix = "decide:${request.id}:%"
        val active = dao.activeDecision(
            profile.profileId,
            request.id,
            OutboxRules.TYPE_DECIDE,
            (OutboxRules.ACTIVE + OutboxStatus.AUTH_BLOCKED.name).toList(),
            activePrefix,
        )
        if (active != null) {
            noteDuplicate(profile, request.id, nowMs)
            return QueueOutcome.Existing(active.operationId, active.status, SyncCopy.existing(active.status).display)
        }
        return try {
            database.runInTransaction(
                Callable {
                    val bound = bind(profile.profileId) ?: return@Callable QueueOutcome.Rejected("profile")
                    if (bound.originEpoch != profile.originEpoch || bound.credentialEpoch != profile.credentialEpoch) return@Callable QueueOutcome.Rejected("profile")
                    val again = dao.activeDecision(
                        profile.profileId,
                        request.id,
                        OutboxRules.TYPE_DECIDE,
                        (OutboxRules.ACTIVE + OutboxStatus.AUTH_BLOCKED.name).toList(),
                        activePrefix,
                    )
                    if (again != null) {
                        noteDuplicate(profile, request.id, nowMs)
                        return@Callable QueueOutcome.Existing(
                            again.operationId,
                            again.status,
                            SyncCopy.existing(again.status).display,
                        )
                    }
                    val generation = dao.decisionCount(profile.profileId, prefix) + 1
                    val operationId = database.auditRuntime.ids.next()
                    val localId = database.auditRuntime.ids.next()
                    if (!RecordRules.validId(operationId) || !RecordRules.validId(localId)) {
                        return@Callable QueueOutcome.Rejected("bad_id")
                    }
                    val plain = body.toByteArray(Charsets.UTF_8)
                    if (database.outboxSealer.keyAliasVersion <= 0) return@Callable QueueOutcome.Rejected("seal")
                    val sealed = database.outboxSealer.seal(plain)
                    val hash = sha256Hex(plain)
                    val expected = stateHash("pending", request.ownerDecision, request.createdAt)
                    val queuedAt = nowMs
                    dao.insertOutbox(
                        OutboxOperationEntity(
                            operationId = operationId,
                            profileId = profile.profileId,
                            originEpoch = profile.originEpoch,
                            credentialEpoch = profile.credentialEpoch,
                            operationType = OutboxRules.TYPE_DECIDE,
                            entityId = request.id,
                            payloadCiphertext = sealed,
                            payloadHash = hash,
                            idempotencyKey = decisionKey(request.id, verb, generation),
                            expectedStateHash = expected,
                            dependsOn = null,
                            status = OutboxStatus.QUEUED.name,
                            attempts = 0,
                            nextAttemptAt = queuedAt,
                            leaseUntil = null,
                            lastErrorCode = null,
                            expiresAt = queuedAt + OutboxRules.INTENT_TTL_MS,
                            confirmedAt = null,
                            createdAtUtcMs = queuedAt,
                            keyAliasVersion = database.outboxSealer.keyAliasVersion,
                        ),
                    )
                    upsertRequest(
                        profile,
                        CachedRequest(
                            remoteId = request.id,
                            productCode = request.product,
                            deviceCode = request.device,
                            kind = request.kind,
                            serverStatus = "pending",
                            ownerDecision = request.ownerDecision?.takeIf { it.isNotBlank() },
                            ownerDecidedAt = null,
                            createdServerAt = request.createdAt,
                        ),
                        expected,
                        nowMs,
                        localId,
                    )
                    note(
                        screen = AuditVocab.SCREEN_REQUESTS,
                        action = AuditVocab.ACTION_CREATE,
                        entityType = AuditVocab.ENTITY_DECISION,
                        entityId = request.id,
                        workspaceId = profile.workspaceId,
                        hubProfileId = profile.profileId,
                        nowMs = nowMs,
                        result = AuditVocab.RESULT_INTENT,
                        reasonCode = verb,
                        after = mapOf(
                            "status" to JsonValue.Text(OutboxStatus.QUEUED.name),
                            "originEpoch" to JsonValue.Int64(profile.originEpoch),
                            "credentialEpoch" to JsonValue.Int64(profile.credentialEpoch),
                        ),
                    )
                    QueueOutcome.Queued(operationId, SyncCopy.queuedDisplay(verb))
                },
            )
        } catch (error: RuntimeException) {
            val winner = dao.activeDecision(
                profile.profileId,
                request.id,
                OutboxRules.TYPE_DECIDE,
                (OutboxRules.ACTIVE + OutboxStatus.AUTH_BLOCKED.name).toList(),
                activePrefix,
            )
            if (winner != null) {
                noteDuplicate(profile, request.id, nowMs)
                QueueOutcome.Existing(winner.operationId, winner.status, SyncCopy.existing(winner.status).display)
            } else {
                throw error
            }
        }
    }

    fun savePendingPage(
        profile: BoundProfile,
        rows: List<CachedRequest>,
        cursor: String,
        complete: Boolean,
        nowMs: Long,
    ) {
        database.runInTransaction {
            rows.forEach { row ->
                val hash = stateHash(row.serverStatus, row.ownerDecision, row.createdServerAt)
                val localId = database.auditRuntime.ids.next()
                upsertRequest(profile, row, hash, nowMs, localId)
            }
            writeCursor(profile.profileId, STREAM_PENDING, cursor, complete, nowMs, rows.firstOrNull()?.createdServerAt, rows.lastOrNull()?.createdServerAt)
        }
    }

    fun saveEvents(
        profile: BoundProfile,
        rows: List<CachedEvent>,
        cursor: String,
        complete: Boolean,
        nowMs: Long,
    ) {
        database.runInTransaction {
            rows.forEach { row -> upsertEvent(profile, row, nowMs) }
            writeCursor(profile.profileId, STREAM_EVENTS, cursor, complete, nowMs, null, null)
        }
    }

    fun saveMailbox(
        profile: BoundProfile,
        rows: List<CachedBatch>,
        cursor: String,
        complete: Boolean,
        nowMs: Long,
    ) {
        database.runInTransaction {
            rows.forEach { row -> upsertBatch(profile, row, nowMs) }
            writeCursor(profile.profileId, STREAM_MAILBOX, cursor, complete, nowMs, null, null)
        }
    }

    fun request(profileId: String, remoteId: String): LicenceRequestEntity? = dao.requestByRemote(profileId, remoteId)

    fun cursor(profileId: String, stream: String): SyncCursorEntity? = dao.cursor(profileId, stream)

    fun openRows(profileId: String): List<OutboxOperationEntity> = dao.openForProfile(profileId)

    fun due(profileId: String, nowMs: Long): List<OutboxOperationEntity> =
        dao.openForProfile(profileId).filter { row ->
            when (row.status) {
                OutboxStatus.QUEUED.name, OutboxStatus.CONFIRMED_LOCAL.name -> row.nextAttemptAt <= nowMs
                OutboxStatus.RETRY_WAIT.name -> row.nextAttemptAt <= nowMs
                OutboxStatus.SENT.name -> row.nextAttemptAt <= nowMs
                OutboxStatus.LEASED.name -> row.leaseUntil == null || row.leaseUntil <= nowMs
                else -> false
            }
        }

    fun apply(row: OutboxOperationEntity, state: OutboxState): Boolean = database.runInTransaction(Callable {
        if (!write(row, state)) return@Callable false
        val profile = bind(row.profileId) ?: error("missing_profile")
        note(
            screen = AuditVocab.SCREEN_REQUESTS,
            action = AuditVocab.ACTION_UPDATE,
            entityType = AuditVocab.ENTITY_DECISION,
            entityId = row.operationId,
            workspaceId = profile.workspaceId,
            hubProfileId = profile.profileId,
            nowMs = System.currentTimeMillis(),
            result = when (state.status) {
                OutboxStatus.CONFIRMED_REMOTE -> AuditVocab.RESULT_SUCCESS
                OutboxStatus.CONFLICT -> AuditVocab.RESULT_CONFLICT
                OutboxStatus.AUTH_BLOCKED, OutboxStatus.NEEDS_REVIEW -> AuditVocab.RESULT_FAILURE
                OutboxStatus.CANCELLED -> AuditVocab.RESULT_CANCELLED
                else -> AuditVocab.RESULT_INTENT
            },
            reasonCode = if (state.status == OutboxStatus.CONFIRMED_REMOTE) "remote_confirmed" else "queue_state",
            before = mapOf("status" to JsonValue.Text(row.status)),
            after = mapOf("status" to JsonValue.Text(state.status.name), "attempts" to JsonValue.Int64(state.attempts.toLong())),
        )
        true
    })

    fun openPayload(row: OutboxOperationEntity): ByteArray? {
        val opened = runCatching { database.outboxSealer.open(row.payloadCiphertext) }.getOrNull() ?: return null
        if (sha256Hex(opened) != row.payloadHash) return null
        return opened
    }

    fun waitingLine(profileId: String, nowMs: Long): String {
        val count = dao.waitingCount(profileId)
        val oldest = dao.oldestWaitingAt(profileId)
        val age = oldest?.let { nowMs - it }
        val blocked = dao.blockedCount(profileId)
        val blockedLine = if (blocked == 0) "" else "تحتاج مراجعة: $blocked. راجع الاتصال والطلب قبل تأكيد قرار جديد.\nNeeds review: $blocked. Check the connection and request before confirming a new decision."
        return listOf(SyncCopy.queueLine(count, age), blockedLine).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun noticeSnapshot(profileId: String, stream: String, incoming: List<String>, nowMs: Long): NoticePlan =
        database.runInTransaction(Callable {
            val plan = planNotices(dao.cursor(profileId, stream) == null, incoming, observedNotices(profileId))
            rememberNotices(profileId, plan.remember, deliver = false, nowMs)
            writeCursor(profileId, stream, "", false, nowMs, null, null)
            plan
        })

    fun cachedRequests(profileId: String): List<LicenceRequest> = dao.cachedPending(profileId).map {
        LicenceRequest(it.remoteId, it.productCode, it.kind, it.deviceCode, "", "", it.createdServerAt, it.ownerDecision, it.ownerDecidedAt)
    }

    fun observedNotices(profileId: String): Set<String> = dao.observedNotices(profileId).map { it.removeSuffix(":$profileId") }.toSet()

    fun markNoticesDelivered(profileId: String, keys: List<String>, nowMs: Long) {
        database.runInTransaction { keys.forEach { dao.markNoticeDelivered("$it:$profileId", nowMs) } }
    }

    fun rememberNotices(profileId: String, keys: List<String>, deliver: Boolean, nowMs: Long) {
        if (dao.hub(profileId) == null) return
        database.runInTransaction {
            keys.forEach { key ->
                if (!NOTICE_KEY.matches(key)) return@forEach
                dao.insertNotice(
                    NotificationReceiptEntity(
                        stableEventKey = "$key:$profileId",
                        profileId = profileId,
                        scheduledAt = nowMs,
                        deliveredAt = if (deliver) nowMs else null,
                        channel = "hub",
                    ),
                )
                if (deliver) dao.markNoticeDelivered("$key:$profileId", nowMs)
            }
        }
    }

    fun auditRun(profile: BoundProfile, confirmed: Int, blocked: Int, waiting: Int, nowMs: Long) {
        if (confirmed == 0 && blocked == 0 && waiting == 0) return
        database.runInTransaction {
            note(
                screen = AuditVocab.SCREEN_HUB,
                action = AuditVocab.ACTION_UPDATE,
                entityType = AuditVocab.ENTITY_DECISION,
                entityId = profile.profileId,
                workspaceId = profile.workspaceId,
                hubProfileId = profile.profileId,
                nowMs = nowMs,
                result = if (blocked == 0) AuditVocab.RESULT_SUCCESS else AuditVocab.RESULT_FAILURE,
                reasonCode = "sync_run",
                after = mapOf(
                    "confirmedCount" to JsonValue.Int64(confirmed.toLong()),
                    "blockedCount" to JsonValue.Int64(blocked.toLong()),
                    "queuedCount" to JsonValue.Int64(waiting.toLong()),
                ),
            )
        }
    }

    private fun upsertRequest(
        profile: BoundProfile,
        row: CachedRequest,
        hash: String,
        nowMs: Long,
        localId: String,
    ) {
        val product = RecordRules.productCode(row.productCode) ?: error("bad_request_cache")
        val existing = dao.requestByRemote(profile.profileId, row.remoteId)
        if (existing == null) {
            if (!RecordRules.validId(localId) || !RecordRules.validId(row.remoteId)) error("bad_request_cache")
            dao.insertRequest(
                LicenceRequestEntity(
                    id = localId,
                    workspaceId = profile.workspaceId,
                    createdAtUtcMs = nowMs,
                    updatedAtUtcMs = nowMs,
                    revision = 1,
                    deletedAtUtcMs = null,
                    hubProfileId = profile.profileId,
                    remoteId = row.remoteId,
                    productCode = product,
                    deviceCode = row.deviceCode.take(20),
                    customerId = null,
                    kind = row.kind.take(20),
                    serverStatus = row.serverStatus,
                    ownerDecision = row.ownerDecision?.take(20),
                    ownerDecidedAt = row.ownerDecidedAt,
                    createdServerAt = row.createdServerAt,
                    fetchedAt = nowMs,
                    snapshotHash = hash,
                ),
            )
        } else if (existing.deletedAtUtcMs == null) {
            dao.updateRequest(
                id = existing.id,
                serverStatus = row.serverStatus,
                ownerDecision = row.ownerDecision?.take(20),
                ownerDecidedAt = row.ownerDecidedAt,
                createdServerAt = row.createdServerAt,
                fetchedAt = nowMs,
                snapshotHash = hash,
                productCode = product,
                deviceCode = row.deviceCode.take(20),
                kind = row.kind.take(20),
                nowUtcMs = nowMs,
            )
        }
    }

    private fun upsertEvent(profile: BoundProfile, row: CachedEvent, nowMs: Long) {
        if (row.remoteId.isBlank() || row.remoteId.length > 40) error("bad_event")
        if (dao.eventByRemote(profile.profileId, row.remoteId) != null) return
        val id = database.auditRuntime.ids.next()
        if (!RecordRules.validId(id)) error("bad_event")
        dao.insertEvent(
            HubEventEntity(
                id = id,
                workspaceId = profile.workspaceId,
                createdAtUtcMs = nowMs,
                updatedAtUtcMs = nowMs,
                revision = 1,
                deletedAtUtcMs = null,
                profileId = profile.profileId,
                remoteEventId = row.remoteId,
                requestRemoteId = row.requestRemoteId,
                eventType = row.eventType.take(40),
                eventAt = row.eventAt,
                safeDetail = redactDetail(row.detail),
            ),
        )
    }

    private fun upsertBatch(profile: BoundProfile, row: CachedBatch, nowMs: Long) {
        if (row.batchId.isBlank() || row.batchId.length > 80 || row.batchId.any { it.isWhitespace() }) error("bad_mailbox")
        if (row.known != 0 && row.known != 1) error("bad_mailbox")
        if (dao.mailboxByBatch(profile.profileId, row.batchId) != null) return
        val id = database.auditRuntime.ids.next()
        if (!RecordRules.validId(id)) error("bad_mailbox")
        val digest = sha256Hex("${row.batchId}|${row.known}|${row.byteCount}".toByteArray(Charsets.UTF_8))
        dao.insertMailbox(
            MailboxReceiptEntity(
                id = id,
                workspaceId = profile.workspaceId,
                createdAtUtcMs = nowMs,
                updatedAtUtcMs = nowMs,
                revision = 1,
                deletedAtUtcMs = null,
                profileId = profile.profileId,
                batchId = row.batchId,
                installRemoteId = row.installRemoteId.take(80),
                receivedAt = row.receivedAt,
                byteCount = row.byteCount.coerceAtLeast(0),
                known = row.known,
                payloadDigest = digest,
            ),
        )
    }

    private fun writeCursor(
        profileId: String,
        stream: String,
        cursor: String,
        complete: Boolean,
        nowMs: Long,
        coverageStart: Long?,
        coverageEnd: Long?,
    ) {
        val existing = dao.cursor(profileId, stream)
        if (existing == null) {
            dao.insertCursor(
                SyncCursorEntity(
                    profileId = profileId,
                    stream = stream,
                    cursor = cursor.take(200),
                    lastSuccess = nowMs,
                    lastAttempt = nowMs,
                    coverageStart = coverageStart,
                    coverageEnd = coverageEnd,
                    complete = if (complete) 1 else 0,
                ),
            )
        } else {
            dao.updateCursor(
                profileId = profileId,
                stream = stream,
                cursor = cursor.take(200),
                lastSuccess = nowMs,
                lastAttempt = nowMs,
                coverageStart = coverageStart ?: existing.coverageStart,
                coverageEnd = coverageEnd ?: existing.coverageEnd,
                complete = if (complete) 1 else 0,
            )
        }
    }

    private fun write(row: OutboxOperationEntity, state: OutboxState): Boolean = dao.compareAndWrite(
        id = row.operationId,
        expectedStatus = row.status,
        expectedAttempts = row.attempts,
        expectedLeaseUntil = row.leaseUntil,
        expectedCredentialEpoch = row.credentialEpoch,
        status = state.status.name,
        attempts = state.attempts,
        nextAttemptAt = state.nextAttemptAt,
        leaseUntil = state.leaseUntil,
        lastErrorCode = state.lastErrorCode,
        confirmedAt = state.confirmedAt,
        credentialEpoch = state.credentialEpoch,
    ) == 1

    private fun noteDuplicate(profile: BoundProfile, requestId: String, nowMs: Long) {
        database.runInTransaction {
            note(
                screen = AuditVocab.SCREEN_REQUESTS,
                action = AuditVocab.ACTION_CREATE,
                entityType = AuditVocab.ENTITY_DECISION,
                entityId = requestId,
                workspaceId = profile.workspaceId,
                hubProfileId = profile.profileId,
                nowMs = nowMs,
                result = AuditVocab.RESULT_CONFLICT,
                reasonCode = "duplicate",
            )
        }
    }

    private fun workspaceId(nowMs: Long): String? {
        dao.firstWorkspace()?.let { return it.id }
        val id = database.auditRuntime.ids.next()
        if (!RecordRules.validId(id)) return null
        val created = database.records().createWorkspace(id, "المكتب", nowMs)
        return if (created == RecordResult.Applied) id else dao.firstWorkspace()?.id
    }

    private fun note(
        screen: String,
        action: String,
        entityType: String,
        entityId: String,
        workspaceId: String,
        hubProfileId: String?,
        nowMs: Long,
        result: String,
        reasonCode: String? = null,
        before: Map<String, JsonValue> = emptyMap(),
        after: Map<String, JsonValue> = emptyMap(),
    ) {
        AuditWriter(database).append(
            AuditSpec(
                screen = screen,
                action = action,
                entityType = entityType,
                entityId = entityId,
                workspaceId = workspaceId,
                hubProfileId = hubProfileId,
                nowUtcMs = nowMs,
                before = before,
                after = after,
                result = result,
                reasonCode = reasonCode,
            ),
        )
    }

    private fun <T> writeBool(body: () -> T): T = database.runInTransaction(Callable { body() })

    companion object {
        const val STREAM_PENDING = "licence_pending"
        const val STREAM_EVENTS = "licence_events"
        const val STREAM_MAILBOX = "mailbox"

        private val NOTICE_KEY = Regex("^(request|event):[A-Za-z0-9._:-]{1,80}$")

        fun redactDetail(raw: String): String {
            val lower = raw.lowercase()
            if ("token" in lower || "bearer" in lower || "secret" in lower || "code" in lower) return ""
            return ""
        }
    }
}

internal fun OutboxOperationEntity.toState(): OutboxState = OutboxState(
    status = OutboxRules.status(status) ?: error("outbox_status"),
    attempts = attempts,
    nextAttemptAt = nextAttemptAt,
    leaseUntil = leaseUntil,
    lastErrorCode = lastErrorCode,
    expiresAt = expiresAt,
    confirmedAt = confirmedAt,
    originEpoch = originEpoch,
    credentialEpoch = credentialEpoch,
)
