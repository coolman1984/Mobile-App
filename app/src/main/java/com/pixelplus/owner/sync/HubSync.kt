package com.pixelplus.owner.sync

import com.pixelplus.owner.HubClient
import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubResult
import com.pixelplus.owner.LicenceEvent
import com.pixelplus.owner.LicenceRequest
import com.pixelplus.owner.MailboxBatch
import com.pixelplus.owner.data.db.OutboxOperationEntity

internal data class DrainSummary(
    val confirmed: Int,
    val blocked: Int,
    val waiting: Int,
    val retry: Boolean,
) {
    companion object {
        fun idle() = DrainSummary(0, 0, 0, false)
    }
}

/**
 * Sends a queued decision only after a fresh server read.
 * A 2xx decide response is not remote success. An ambiguous failure is not
 * undone and is not replaced with the opposite action.
 */
internal class HubSync(
    private val store: SyncStore,
    private val client: HubClient,
) {
    fun refresh(config: HubConfig, profile: BoundProfile, nowMs: Long) {
        when (val pending = client.pendingAll(config)) {
            is HubResult.Ok -> store.savePendingPage(
                profile,
                pending.value.map { it.toCached() },
                cursor = pending.value.lastOrNull()?.let { "${it.createdAt}:${it.id}" }.orEmpty(),
                complete = true,
                nowMs = nowMs,
            )
            else -> Unit
        }
        when (val events = client.events(config, limit = 100)) {
            is HubResult.Ok -> store.saveEvents(
                profile,
                events.value.map { it.toCached() },
                cursor = events.value.firstOrNull()?.id.orEmpty(),
                complete = false,
                nowMs = nowMs,
            )
            else -> Unit
        }
        if (config.pullToken.isNotBlank()) {
            when (val mailbox = client.mailbox(config, limit = 20)) {
                is HubResult.Ok -> store.saveMailbox(
                    profile,
                    mailbox.value.map { it.toCached() },
                    cursor = mailbox.value.lastOrNull()?.id.orEmpty(),
                    complete = false,
                    nowMs = nowMs,
                )
                else -> Unit
            }
        }
    }

    fun drain(config: HubConfig, nowMs: Long, jitterMs: Long = 0L): DrainSummary {
        if (!config.isLive) return DrainSummary.idle()
        val initial = store.profileForUrl(config.baseUrl) ?: return DrainSummary.idle()
        var profile = store.bind(initial.profileId) ?: return DrainSummary.idle()
        var confirmed = 0
        var blocked = 0
        var waiting = 0
        var retry = false
        for (row in store.due(profile.profileId, nowMs).take(50)) {
            profile = store.bind(profile.profileId) ?: break
            when (val step = step(config, profile, row, nowMs, jitterMs)) {
                Step.Confirmed -> confirmed += 1
                Step.Blocked -> blocked += 1
                Step.Waiting -> waiting += 1
                Step.Retry -> {
                    waiting += 1
                    retry = true
                }
            }
        }
        val latest = store.bind(profile.profileId)
        if (latest != null) store.auditRun(latest, confirmed, blocked, waiting, nowMs)
        return DrainSummary(confirmed, blocked, waiting, retry || store.openRows(profile.profileId).any { it.status in OutboxRules.ACTIVE && !profile.paused })
    }

    /**
     * A successful read with the current credential lets a paused queue continue.
     * Rows are not sent here.
     */
    fun noteReachable(config: HubConfig, nowMs: Long): Boolean {
        val profile = store.profileForUrl(config.baseUrl) ?: return false
        if (!profile.paused && store.openRows(profile.profileId).none { it.credentialEpoch != profile.credentialEpoch }) {
            return true
        }
        val ids = store.openRows(profile.profileId).map { it.entityId }.distinct().take(50)
        if (ids.isNotEmpty()) {
            val states = client.states(config, ids)
            if (states !is HubResult.Ok) return false
        }
        return store.resume(profile.profileId, nowMs)
    }

    private fun step(
        config: HubConfig,
        profile: BoundProfile,
        row: OutboxOperationEntity,
        nowMs: Long,
        jitterMs: Long,
    ): Step {
        val gate = profileGate(
            row.originEpoch,
            row.credentialEpoch,
            profile.originEpoch,
            profile.credentialEpoch,
            profile.paused,
        )
        if (gate == "origin_changed") {
            return move(row, OutboxMachine.review(row.toState(), "origin_changed"), Step.Blocked)
        }
        if (gate != null) return Step.Waiting
        if (nowMs >= row.expiresAt) {
            return move(row, OutboxMachine.expire(row.toState(), nowMs), Step.Blocked)
        }
        val action = actionOf(row) ?: return move(row, OutboxMachine.review(row.toState(), "seal"), Step.Blocked)
        when (val read = readFresh(config, profile, row, nowMs, jitterMs)) {
            is Read.Finished -> return read.step
            is Read.Page -> {
                return when (val judged = judgeDecision(action, read.fresh, row.expectedStateHash, nowMs / 1000L)) {
                    is Freshness.Applied -> move(row, OutboxMachine.confirm(row.toState(), nowMs), Step.Confirmed)
                    is Freshness.Stop -> move(row, stopMove(row.toState(), judged), Step.Blocked)
                    Freshness.Send -> send(config, row, action, nowMs, jitterMs)
                }
            }
        }
    }

    private fun send(
        config: HubConfig,
        row: OutboxOperationEntity,
        action: String,
        nowMs: Long,
        jitterMs: Long,
    ): Step {
        val current = store.openRows(row.profileId).firstOrNull { it.operationId == row.operationId } ?: return Step.Waiting
        if (current.status == OutboxStatus.SENT.name && current.lastErrorCode != OutboxRules.ERR_AMBIGUOUS) {
            return move(current, OutboxMachine.review(current.toState(), "unconfirmed"), Step.Blocked)
        }
        val claimed = if (current.status == OutboxStatus.SENT.name) {
            OutboxMachine.retrySame(current.toState(), nowMs)
        } else {
            OutboxMachine.claim(current.toState(), nowMs)
        }
        val leased = claimed as? MachineMove.Ok ?: return move(current, claimed, Step.Blocked)
        if (!store.apply(current, leased.state)) return Step.Waiting
        val held = current.copy(
            status = leased.state.status.name,
            attempts = leased.state.attempts,
            leaseUntil = leased.state.leaseUntil,
            lastErrorCode = leased.state.lastErrorCode,
            nextAttemptAt = leased.state.nextAttemptAt,
        )
        val plain = store.openPayload(held) ?: return move(held, OutboxMachine.review(held.toState(), "seal"), Step.Blocked)
        val body = plain.toString(Charsets.UTF_8)
        val ownerAction = if (action == OutboxRules.ACTION_APPROVE) com.pixelplus.owner.OwnerAction.APPROVE else com.pixelplus.owner.OwnerAction.REFUSE
        if (row.operationType != OutboxRules.TYPE_DECIDE || body != com.pixelplus.owner.decideBody(row.entityId, ownerAction)) {
            return move(held, OutboxMachine.review(held.toState(), "seal"), Step.Blocked)
        }
        val posted = client.postDecideBody(config, body)
        return when (val hint = hintForDecide(posted, action)) {
            SendHint.Reconcile -> afterPost(config, held, action, nowMs, jitterMs)
            is SendHint.Ambiguous -> move(held, OutboxMachine.ambiguous(held.toState(), nowMs, jitterMs), Step.Retry)
            is SendHint.Retry -> move(held, OutboxMachine.retryWait(held.toState(), nowMs, hint.reason, jitterMs), Step.Retry)
            is SendHint.Auth -> {
                val paused = store.bind(held.profileId)?.paused == true
                if (!paused) store.pauseCredential(held.profileId, nowMs)
                move(held, OutboxMachine.auth(held.toState(), hint.reason), Step.Blocked)
            }
            is SendHint.Conflict -> move(held, OutboxMachine.conflict(held.toState(), hint.reason), Step.Blocked)
            is SendHint.Review -> move(held, OutboxMachine.review(held.toState(), hint.reason), Step.Blocked)
        }
    }

    private fun afterPost(
        config: HubConfig,
        row: OutboxOperationEntity,
        action: String,
        nowMs: Long,
        jitterMs: Long,
    ): Step {
        val profile = store.bind(row.profileId) ?: return Step.Retry
        return when (val read = readFresh(config, profile, row, nowMs, jitterMs)) {
            is Read.Finished -> read.step
            is Read.Page -> when (val judged = judgeDecision(action, read.fresh, row.expectedStateHash, nowMs / 1000L)) {
                Freshness.Applied -> move(row, OutboxMachine.confirm(row.toState(), nowMs), Step.Confirmed)
                is Freshness.Stop -> move(row, stopMove(row.toState(), judged), Step.Blocked)
                Freshness.Send -> move(row, OutboxMachine.awaitConfirm(row.toState(), nowMs, jitterMs), Step.Retry)
            }
        }
    }

    private fun readFresh(
        config: HubConfig,
        profile: BoundProfile,
        row: OutboxOperationEntity,
        nowMs: Long,
        jitterMs: Long,
    ): Read {
        val states = client.states(config, listOf(row.entityId))
        return when (states) {
            is HubResult.Ok -> {
                val server = states.value[row.entityId]
                if (server == null) {
                    Read.Page(null)
                } else {
                    if (server.status != "pending") {
                        Read.Page(FreshSnapshot(server.status, server.ownerDecision, -1L))
                    } else {
                        // /states does not project effective approval expiry. Never trust its raw approved value.
                        when (val pending = client.pendingAll(config)) {
                            is HubResult.Ok -> {
                                val found = pending.value.firstOrNull { it.id == row.entityId }
                                Read.Page(found?.let { FreshSnapshot("pending", it.ownerDecision, it.createdAt) })
                            }
                            else -> Read.Finished(readFailure(profile, row, pending, nowMs, jitterMs))
                        }
                    }
                }
            }
            is HubResult.Http -> {
                val step = if (states.code == 401 || states.code == 403) {
                    if (!profile.paused) store.pauseCredential(profile.profileId, nowMs)
                    move(row, OutboxMachine.auth(row.toState(), if (states.code == 401) "unauthorised" else "forbidden"), Step.Blocked)
                } else if (states.code == 409) {
                    move(row, OutboxMachine.conflict(row.toState(), "closed"), Step.Blocked)
                } else if (states.code == 429 || states.code in 500..599) {
                    move(row, OutboxMachine.defer(row.toState(), nowMs, "http", jitterMs), Step.Retry)
                } else {
                    move(row, OutboxMachine.review(row.toState(), "http"), Step.Blocked)
                }
                Read.Finished(step)
            }
            is HubResult.Network -> Read.Finished(move(row, OutboxMachine.defer(row.toState(), nowMs, "io", jitterMs), Step.Retry))
            is HubResult.BadResponse -> Read.Finished(move(row, OutboxMachine.review(row.toState(), "bad_response"), Step.Blocked))
            is HubResult.Rejected -> Read.Finished(move(row, OutboxMachine.review(row.toState(), "rejected"), Step.Blocked))
        }
    }

    private fun readFailure(profile: BoundProfile, row: OutboxOperationEntity, result: HubResult<*>, nowMs: Long, jitterMs: Long): Step {
        return when {
            result is HubResult.Http && result.code in setOf(401, 403) -> {
                if (!profile.paused) store.pauseCredential(profile.profileId, nowMs)
                move(row, OutboxMachine.auth(row.toState(), if (result.code == 401) "unauthorised" else "forbidden"), Step.Blocked)
            }
            result is HubResult.Network || result is HubResult.Http && (result.code == 429 || result.code >= 500) ->
                move(row, OutboxMachine.defer(row.toState(), nowMs, "io", jitterMs), Step.Retry)
            else -> move(row, OutboxMachine.review(row.toState(), "bad_response"), Step.Blocked)
        }
    }

    private fun move(row: OutboxOperationEntity, moved: MachineMove, step: Step): Step {
        val ok = moved as? MachineMove.Ok ?: return Step.Blocked
        return if (store.apply(row, ok.state)) step else Step.Waiting
    }

    private fun stopMove(state: OutboxState, stop: Freshness.Stop): MachineMove = when (stop.status) {
        OutboxStatus.CONFLICT -> OutboxMachine.conflict(state, stop.reason)
        OutboxStatus.AUTH_BLOCKED -> OutboxMachine.auth(state, stop.reason)
        else -> OutboxMachine.review(state, stop.reason)
    }

    private fun actionOf(row: OutboxOperationEntity): String? {
        val plain = store.openPayload(row) ?: return null
        return actionOfBody(plain.toString(Charsets.UTF_8))
    }

    private enum class Step { Confirmed, Blocked, Waiting, Retry }

    private sealed class Read {
        data class Page(val fresh: FreshSnapshot?) : Read()
        data class Finished(val step: Step) : Read()
    }
}

internal fun LicenceRequest.toCached() = CachedRequest(
    remoteId = id,
    productCode = product,
    deviceCode = device,
    kind = kind,
    serverStatus = "pending",
    ownerDecision = ownerDecision?.takeIf { it.isNotBlank() },
    ownerDecidedAt = ownerDecidedAt,
    createdServerAt = createdAt,
)

private fun LicenceEvent.toCached() = CachedEvent(
    remoteId = id,
    requestRemoteId = requestId,
    eventType = event,
    eventAt = at,
    detail = detail,
)

private fun MailboxBatch.toCached() = CachedBatch(
    batchId = id,
    installRemoteId = installId,
    receivedAt = receivedAt,
    byteCount = size,
    known = known,
)

internal fun actionOfBody(body: String): String? = com.pixelplus.owner.actionOfStoredBody(body)
