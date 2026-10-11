package com.pixelplus.owner.sync

import com.pixelplus.owner.DecideAck
import com.pixelplus.owner.HubResult
import java.security.MessageDigest

/**
 * The offline decision queue.
 *
 * A row is an intent. Remote success is a later state, after a fresh server
 * read agrees. A missing response is not success and is not a reason to send
 * the opposite action. No Android types.
 */
enum class OutboxStatus {
    CONFIRMED_LOCAL,
    QUEUED,
    LEASED,
    SENT,
    CONFIRMED_REMOTE,
    RETRY_WAIT,
    CONFLICT,
    NEEDS_REVIEW,
    CANCELLED,
    AUTH_BLOCKED,
}

object OutboxRules {
    const val TYPE_DECIDE = "licence_decide"
    const val ACTION_APPROVE = "approve"
    const val ACTION_REFUSE = "refuse"

    /** The owner's intent expires. This does not ask the hub to keep an approval alive. */
    const val INTENT_TTL_MS = 24L * 60L * 60L * 1000L
    const val LEASE_MS = 60_000L
    const val MAX_ATTEMPTS = 8
    const val BACKOFF_BASE_MS = 15_000L
    const val BACKOFF_CAP_MS = 15L * 60L * 1000L
    const val JITTER_MAX_MS = 5_000L

    /**
     * The Worker's default approval window. Used only to avoid a send the hub
     * would reject as stale. The phone never sends a longer window.
     */
    const val APPROVAL_WINDOW_SEC = 72L * 3600L

    const val ERR_AMBIGUOUS = "ambiguous"
    const val ERR_PENDING_CONFIRM = "pending_confirm"

    val ACTIVE = setOf(
        OutboxStatus.CONFIRMED_LOCAL.name,
        OutboxStatus.QUEUED.name,
        OutboxStatus.LEASED.name,
        OutboxStatus.SENT.name,
        OutboxStatus.RETRY_WAIT.name,
    )

    val TERMINAL = setOf(
        OutboxStatus.CONFIRMED_REMOTE.name,
        OutboxStatus.CONFLICT.name,
        OutboxStatus.NEEDS_REVIEW.name,
        OutboxStatus.CANCELLED.name,
        OutboxStatus.AUTH_BLOCKED.name,
    )

    fun status(raw: String): OutboxStatus? = OutboxStatus.entries.firstOrNull { it.name == raw }
}

data class OutboxState(
    val status: OutboxStatus,
    val attempts: Int,
    val nextAttemptAt: Long,
    val leaseUntil: Long?,
    val lastErrorCode: String?,
    val expiresAt: Long,
    val confirmedAt: Long?,
    val originEpoch: Long,
    val credentialEpoch: Long,
)

sealed class MachineMove {
    data class Ok(val state: OutboxState) : MachineMove()
    data class No(val reason: String) : MachineMove()
}

data class FreshSnapshot(
    val status: String,
    val ownerDecision: String?,
    val createdAtSec: Long,
)

sealed class Freshness {
    data object Send : Freshness()
    data object Applied : Freshness()
    data class Stop(val status: OutboxStatus, val reason: String) : Freshness()
}

sealed class SendHint {
    data object Reconcile : SendHint()
    data class Retry(val reason: String) : SendHint()
    data class Ambiguous(val reason: String) : SendHint()
    data class Auth(val reason: String) : SendHint()
    data class Conflict(val reason: String) : SendHint()
    data class Review(val reason: String) : SendHint()
}

object OutboxMachine {
    fun release(state: OutboxState, nowMs: Long): MachineMove {
        if (state.status != OutboxStatus.CONFIRMED_LOCAL) return MachineMove.No("status")
        if (nowMs >= state.expiresAt) return expire(state, nowMs)
        return MachineMove.Ok(state.copy(status = OutboxStatus.QUEUED, nextAttemptAt = nowMs))
    }

    fun claim(state: OutboxState, nowMs: Long, leaseMs: Long = OutboxRules.LEASE_MS): MachineMove {
        if (nowMs >= state.expiresAt) return expire(state, nowMs)
        val recoverable = state.status == OutboxStatus.LEASED &&
            state.leaseUntil != null &&
            state.leaseUntil <= nowMs
        val waiting = state.status == OutboxStatus.QUEUED || state.status == OutboxStatus.RETRY_WAIT
        if (!recoverable && !waiting) return MachineMove.No("status")
        if (waiting && state.nextAttemptAt > nowMs) return MachineMove.No("early")
        if (state.attempts >= OutboxRules.MAX_ATTEMPTS) {
            return review(state, "attempts")
        }
        return MachineMove.Ok(
            state.copy(
                status = OutboxStatus.LEASED,
                attempts = state.attempts + 1,
                leaseUntil = nowMs + leaseMs,
                lastErrorCode = if (recoverable) OutboxRules.ERR_AMBIGUOUS else state.lastErrorCode,
            ),
        )
    }

    fun ambiguous(state: OutboxState, nowMs: Long, jitterMs: Long): MachineMove {
        if (state.status != OutboxStatus.LEASED) return MachineMove.No("status")
        return MachineMove.Ok(
            state.copy(
                status = OutboxStatus.SENT,
                leaseUntil = null,
                lastErrorCode = OutboxRules.ERR_AMBIGUOUS,
                nextAttemptAt = nowMs + backoffMs(1, jitterMs),
            ),
        )
    }

    fun retrySame(state: OutboxState, nowMs: Long, leaseMs: Long = OutboxRules.LEASE_MS): MachineMove {
        if (state.status != OutboxStatus.SENT || state.lastErrorCode != OutboxRules.ERR_AMBIGUOUS) {
            return MachineMove.No("status")
        }
        if (nowMs < state.nextAttemptAt) return MachineMove.No("early")
        if (nowMs >= state.expiresAt) return expire(state, nowMs)
        val attempts = state.attempts + 1
        if (attempts > OutboxRules.MAX_ATTEMPTS) return review(state, "attempts")
        return MachineMove.Ok(
            state.copy(
                status = OutboxStatus.LEASED,
                attempts = attempts,
                leaseUntil = nowMs + leaseMs,
            ),
        )
    }

    fun awaitConfirm(state: OutboxState, nowMs: Long, jitterMs: Long): MachineMove {
        if (state.status != OutboxStatus.LEASED && state.status != OutboxStatus.SENT) {
            return MachineMove.No("status")
        }
        return MachineMove.Ok(
            state.copy(
                status = OutboxStatus.SENT,
                leaseUntil = null,
                lastErrorCode = OutboxRules.ERR_PENDING_CONFIRM,
                nextAttemptAt = nowMs + backoffMs(1, jitterMs),
            ),
        )
    }

    /** The decision was not sent. Try the same intent later. */
    fun defer(state: OutboxState, nowMs: Long, reason: String, jitterMs: Long): MachineMove {
        if (state.status != OutboxStatus.QUEUED &&
            state.status != OutboxStatus.RETRY_WAIT &&
            state.status != OutboxStatus.LEASED &&
            state.status != OutboxStatus.SENT
        ) {
            return MachineMove.No("status")
        }
        val attempts = state.attempts + 1
        if (attempts > OutboxRules.MAX_ATTEMPTS) return review(state, "attempts")
        return MachineMove.Ok(
            state.copy(
                status = if (state.status == OutboxStatus.SENT || state.status == OutboxStatus.LEASED) OutboxStatus.SENT else OutboxStatus.RETRY_WAIT,
                attempts = attempts,
                nextAttemptAt = nowMs + backoffMs(attempts, jitterMs),
                leaseUntil = null,
                lastErrorCode = if (state.status == OutboxStatus.SENT || state.status == OutboxStatus.LEASED) OutboxRules.ERR_AMBIGUOUS else reason,
            ),
        )
    }

    fun retryWait(state: OutboxState, nowMs: Long, reason: String, jitterMs: Long): MachineMove {
        if (state.status != OutboxStatus.LEASED && state.status != OutboxStatus.SENT) {
            return MachineMove.No("status")
        }
        val attempts = state.attempts + 1
        if (attempts > OutboxRules.MAX_ATTEMPTS) return review(state.copy(attempts = state.attempts), "attempts")
        return MachineMove.Ok(
            state.copy(
                status = OutboxStatus.RETRY_WAIT,
                attempts = attempts,
                nextAttemptAt = nowMs + backoffMs(attempts, jitterMs),
                leaseUntil = null,
                lastErrorCode = reason,
            ),
        )
    }

    fun confirm(state: OutboxState, nowMs: Long): MachineMove {
        if (state.status !in setOf(
                OutboxStatus.QUEUED,
                OutboxStatus.LEASED,
                OutboxStatus.SENT,
                OutboxStatus.RETRY_WAIT,
                OutboxStatus.CONFIRMED_LOCAL,
            )
        ) {
            return MachineMove.No("status")
        }
        return MachineMove.Ok(
            state.copy(
                status = OutboxStatus.CONFIRMED_REMOTE,
                leaseUntil = null,
                lastErrorCode = null,
                confirmedAt = nowMs,
            ),
        )
    }

    fun conflict(state: OutboxState, reason: String): MachineMove = stop(state, OutboxStatus.CONFLICT, reason)

    fun review(state: OutboxState, reason: String): MachineMove = stop(state, OutboxStatus.NEEDS_REVIEW, reason)

    fun auth(state: OutboxState, reason: String): MachineMove = stop(state, OutboxStatus.AUTH_BLOCKED, reason)

    fun cancel(state: OutboxState, nowMs: Long): MachineMove {
        val open = state.status == OutboxStatus.CONFIRMED_LOCAL ||
            state.status == OutboxStatus.QUEUED ||
            state.status == OutboxStatus.RETRY_WAIT
        if (!open) return MachineMove.No(if (state.status == OutboxStatus.LEASED || state.status == OutboxStatus.SENT) "sent" else "status")
        if (state.leaseUntil != null && state.leaseUntil > nowMs) return MachineMove.No("leased")
        return MachineMove.Ok(state.copy(status = OutboxStatus.CANCELLED, leaseUntil = null, lastErrorCode = null))
    }

    fun expire(state: OutboxState, nowMs: Long): MachineMove {
        if (state.status.name in OutboxRules.TERMINAL) return MachineMove.No("status")
        if (nowMs < state.expiresAt) return MachineMove.No("early")
        return review(state, "expired_intent")
    }

    fun rebind(state: OutboxState, credentialEpoch: Long): MachineMove {
        if (state.status.name in OutboxRules.TERMINAL && state.status != OutboxStatus.AUTH_BLOCKED) {
            return MachineMove.No("status")
        }
        val next = if (state.status == OutboxStatus.LEASED) OutboxStatus.SENT else if (state.status == OutboxStatus.AUTH_BLOCKED &&
            (state.lastErrorCode == "unauthorised" || state.lastErrorCode == "forbidden")
        ) {
            OutboxStatus.QUEUED
        } else {
            state.status
        }
        return MachineMove.Ok(
            state.copy(
                status = next,
                credentialEpoch = credentialEpoch,
                leaseUntil = null,
                lastErrorCode = if (state.status == OutboxStatus.LEASED) OutboxRules.ERR_AMBIGUOUS else if (next == OutboxStatus.QUEUED) null else state.lastErrorCode,
            ),
        )
    }

    private fun stop(state: OutboxState, status: OutboxStatus, reason: String): MachineMove {
        if (state.status.name in OutboxRules.TERMINAL) return MachineMove.No("status")
        if (reason.isBlank() || !REASON.matches(reason)) return MachineMove.No("reason")
        return MachineMove.Ok(
            state.copy(
                status = status,
                leaseUntil = null,
                lastErrorCode = reason,
            ),
        )
    }

    private val REASON = Regex("^[a-z0-9_]{1,40}$")
}

fun backoffMs(attempt: Int, jitterMs: Long): Long {
    val steps = (attempt - 1).coerceAtLeast(0)
    var delay = OutboxRules.BACKOFF_BASE_MS
    repeat(steps.coerceAtMost(16)) {
        if (delay >= OutboxRules.BACKOFF_CAP_MS / 2) {
            delay = OutboxRules.BACKOFF_CAP_MS
        } else {
            delay *= 2
        }
    }
    val jitter = jitterMs.coerceIn(0L, OutboxRules.JITTER_MAX_MS)
    return delay.coerceAtMost(OutboxRules.BACKOFF_CAP_MS) + jitter
}

fun decisionKey(requestId: String, action: String, generation: Int): String {
    require(generation >= 1) { "generation" }
    return "decide:$requestId:$action:$generation"
}

fun decisionPrefix(requestId: String, action: String): String = "decide:$requestId:$action:"

fun stateHash(status: String, ownerDecision: String?, createdAtSec: Long): String {
    val decision = ownerDecision?.takeIf { it.isNotBlank() }.orEmpty()
    return sha256Hex("$status|$decision|$createdAtSec".toByteArray(Charsets.UTF_8))
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val alphabet = "0123456789abcdef"
    val out = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        out[index * 2] = alphabet[value ushr 4]
        out[index * 2 + 1] = alphabet[value and 0x0f]
    }
    return String(out)
}

/**
 * What the fresh server row allows.
 * [FreshSnapshot] is null when the hub has no such request.
 */
fun judgeDecision(action: String, fresh: FreshSnapshot?, expectedHash: String, nowSec: Long): Freshness {
    if (action != OutboxRules.ACTION_APPROVE && action != OutboxRules.ACTION_REFUSE) {
        return Freshness.Stop(OutboxStatus.NEEDS_REVIEW, "bad_action")
    }
    if (fresh == null) return Freshness.Stop(OutboxStatus.NEEDS_REVIEW, "absent")
    val decision = fresh.ownerDecision?.takeIf { it.isNotBlank() }
    if (decision == "expired") return Freshness.Stop(OutboxStatus.NEEDS_REVIEW, "expired")
    if (action == OutboxRules.ACTION_APPROVE && fresh.status == "pending" && decision == "approved") {
        return Freshness.Applied
    }
    if (action == OutboxRules.ACTION_REFUSE && fresh.status == "refused") return Freshness.Applied
    if (fresh.status != "pending") return Freshness.Stop(OutboxStatus.CONFLICT, "closed")
    if (decision == "denied") return Freshness.Stop(OutboxStatus.CONFLICT, "closed")
    if (action == OutboxRules.ACTION_APPROVE && nowSec - fresh.createdAtSec > OutboxRules.APPROVAL_WINDOW_SEC) {
        return Freshness.Stop(OutboxStatus.NEEDS_REVIEW, "stale")
    }
    if (fresh.createdAtSec < 0) return Freshness.Stop(OutboxStatus.NEEDS_REVIEW, "unverified")
    val hash = stateHash(fresh.status, decision, fresh.createdAtSec)
    if (hash != expectedHash) return Freshness.Stop(OutboxStatus.NEEDS_REVIEW, "stale_snapshot")
    return Freshness.Send
}

fun profileGate(
    rowOrigin: Long,
    rowCredential: Long,
    profileOrigin: Long,
    profileCredential: Long,
    paused: Boolean,
): String? {
    if (rowOrigin != profileOrigin) return "origin_changed"
    if (paused) return "paused"
    if (rowCredential != profileCredential) return "credential_changed"
    return null
}

fun hintForDecide(result: HubResult<*>, action: String): SendHint = when (result) {
    is HubResult.Ok -> {
        val ack = result.value as? DecideAck
        when {
            ack == null -> SendHint.Review("bad_response")
            action == OutboxRules.ACTION_APPROVE && ack.ownerDecision != "approved" -> SendHint.Review("bad_response")
            action == OutboxRules.ACTION_REFUSE && ack.status != "refused" -> SendHint.Review("bad_response")
            else -> SendHint.Reconcile
        }
    }
    is HubResult.BadResponse -> SendHint.Review("bad_response")
    is HubResult.Network -> SendHint.Ambiguous("io")
    is HubResult.Rejected -> SendHint.Review("rejected")
    is HubResult.Http -> when (result.code) {
        401 -> SendHint.Auth("unauthorised")
        403 -> SendHint.Auth("forbidden")
        409 -> SendHint.Conflict(safeReason(result.error, "closed"))
        429 -> SendHint.Retry("rate")
        in 500..599 -> SendHint.Ambiguous("http")
        else -> SendHint.Review(safeReason(result.error, "http"))
    }
}

private fun safeReason(raw: String, fallback: String): String =
    raw.takeIf { it in setOf("closed", "stale", "expired", "bad_id", "bad_action", "not_found", "rate") } ?: fallback

data class NoticePlan(val notify: List<String>, val remember: List<String>)

/**
 * The first snapshot is remembered and not announced.
 * A key that was already delivered is not announced again.
 */
fun planNotices(firstSnapshot: Boolean, incoming: List<String>, delivered: Set<String>): NoticePlan {
    val fresh = incoming.filter { it.isNotBlank() }.distinct()
    if (firstSnapshot) return NoticePlan(emptyList(), fresh)
    val notify = fresh.filter { it !in delivered }
    return NoticePlan(notify, notify)
}

fun stableRequestKey(id: String): String = "request:$id"

fun stableEventKey(id: String): String = "event:$id"
