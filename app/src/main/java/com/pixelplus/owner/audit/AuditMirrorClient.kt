package com.pixelplus.owner.audit

import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubRequest
import com.pixelplus.owner.HubTransport
import com.pixelplus.owner.HubTransportResult
import com.pixelplus.owner.UrlResult
import com.pixelplus.owner.normalizeHubUrl
import com.pixelplus.owner.parseErrorCode
import com.pixelplus.owner.rejectedHubPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's side of the hub's audit mirror (M05): `POST /audit/append` and `GET /audit/recent`.
 *
 * Staged on purpose. Nothing in the app calls this yet, and an older Worker that does not know the routes answers 404 or 405, which
 * is [MirrorResult.NotAvailable] and never an error the owner sees. The owner token is the only credential used; the pull token never
 * is. The client sends the exact canonical bytes the phone hashed, as a string, and never rebuilds an entry. It does not send tokens,
 * codes or customer text because the entry holds none (AuditRedaction). No `/ack`.
 *
 * A reply is never believed on its face: an append is confirmed only when the hub echoes the same event, sequence and hash, and a
 * page read back is re-hashed link by link before it is returned. No Android types.
 */
data class ChainHead(val sequence: Long, val entryHash: String)

data class Appended(val eventId: String, val sequence: Long, val entryHash: String, val replay: Boolean)

data class MirrorEntry(
    val eventId: String,
    val sequence: Long,
    val canonical: ByteArray,
    val previousHash: String,
    val entryHash: String,
)

data class RecentPage(
    val head: ChainHead,
    /** Newest first, as the hub sends them. */
    val entries: List<MirrorEntry>,
    val nextBeforeSequence: Long?,
)

sealed class MirrorResult<out T> {
    data class Ok<T>(val value: T) : MirrorResult<T>()

    /** 409: a gap, a fork, a wrong predecessor, another device, or a reused event id. The hub's head is the truth to reconcile with. */
    data class Conflict(val head: ChainHead?) : MirrorResult<Nothing>()

    /** 404 or 405: the Worker is older than M05. Baseline behaviour only; nothing to show the owner. */
    data object NotAvailable : MirrorResult<Nothing>()

    /** 401 or 403: pause, as for every owner call. */
    data object AuthBlocked : MirrorResult<Nothing>()

    /** 400, 413 or 507: the hub will not take this entry as sent. Retrying the same bytes cannot help; it needs review or an operator. */
    data class Invalid(val code: String) : MirrorResult<Nothing>()

    /** Network failure, 429 or 5xx: safe to retry the identical bytes later. */
    data class Retry(val code: String) : MirrorResult<Nothing>()

    /** A 2xx the phone cannot trust (wrong shape, wrong echo, a hash that does not verify). Fails closed. */
    data class BadResponse(val reason: String) : MirrorResult<Nothing>()

    /** Refused here before any request was made (no token, bad address, entry too large or malformed). */
    data class Rejected(val reason: String) : MirrorResult<Nothing>()
}

class AuditMirrorClient(private val transport: HubTransport) {
    fun append(config: HubConfig, link: ChainLink): MirrorResult<Appended> {
        if (link.canonicalBytes.size > AuditHash.MAX_CANONICAL_BYTES) return MirrorResult.Rejected("too_large")
        if (!isHash(link.previousHash) || !isHash(link.entryHash)) return MirrorResult.Rejected("bad_hash")
        if (AuditHash.of(link.previousHash, link.canonicalBytes) != link.entryHash) return MirrorResult.Rejected("hash_mismatch")
        val draft = runCatching { AuditCanonical.draftOf(link.canonicalBytes) }.getOrNull()
            ?: return MirrorResult.Rejected("bad_entry")
        val body = appendBody(link)
        return exchange(config, "POST", "/audit/append", body) { code, text ->
            when (code) {
                200, 201 -> parseAppended(text, draft, link, replayExpected = code == 200)
                409 -> MirrorResult.Conflict(parseHead(text))
                else -> null
            }
        }
    }

    /** `beforeSequence` null reads the newest page. The page is verified before it is returned. */
    fun recent(config: HubConfig, chainId: String, beforeSequence: Long? = null, limit: Int = 50): MirrorResult<RecentPage> {
        if (!UUID.matches(chainId)) return MirrorResult.Rejected("bad_chain")
        if (beforeSequence != null && beforeSequence < 1) return MirrorResult.Rejected("bad_cursor")
        val size = limit.coerceIn(1, 100)
        val path = buildString {
            append("/audit/recent?chainId=").append(chainId)
            if (beforeSequence != null) append("&beforeSequence=").append(beforeSequence)
            append("&limit=").append(size)
        }
        return exchange(config, "GET", path, null) { code, text ->
            if (code == 200) parsePage(text, chainId, beforeSequence, size) else null
        }
    }

    private fun <T> exchange(
        config: HubConfig,
        method: String,
        path: String,
        body: String?,
        onSuccessOrConflict: (Int, String) -> MirrorResult<T>?,
    ): MirrorResult<T> {
        if (config.ownerToken.isBlank()) return MirrorResult.Rejected("no_token")
        val base = when (val checked = normalizeHubUrl(config.baseUrl)) {
            is UrlResult.Ok -> checked.url
            is UrlResult.Reject -> return MirrorResult.Rejected(checked.reason)
        }
        val url = base + path
        rejectedHubPath(method, url)?.let { return MirrorResult.Rejected(it) }
        return when (val raw = transport.exchange(HubRequest(method, url, config.ownerToken, body))) {
            is HubTransportResult.Failed -> MirrorResult.Retry(raw.kind)
            is HubTransportResult.Response -> interpret(raw.code, raw.body, onSuccessOrConflict)
        }
    }

    private fun <T> interpret(code: Int, text: String, handle: (Int, String) -> MirrorResult<T>?): MirrorResult<T> {
        handle(code, text)?.let { return it }
        val error = parseErrorCode(text) ?: "http"
        return when {
            code == 401 || code == 403 -> MirrorResult.AuthBlocked
            code == 404 || code == 405 -> MirrorResult.NotAvailable
            code == 429 || (code in 500..599 && code != 507) -> MirrorResult.Retry(error)
            code == 507 -> MirrorResult.Invalid("full")
            code == 400 || code == 413 || code == 409 -> MirrorResult.Invalid(error)
            code in 200..299 -> MirrorResult.BadResponse("status")
            else -> MirrorResult.Invalid(error)
        }
    }

    private fun parseAppended(text: String, draft: AuditDraft, link: ChainLink, replayExpected: Boolean): MirrorResult<Appended> {
        val root = obj(text) ?: return MirrorResult.BadResponse("shape")
        val eventId = root.string("eventId")
        val sequence = root.long("sequence")
        val hash = root.string("entryHash")
        val replay = (root["replay"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (eventId == null || sequence == null || hash == null || replay !in setOf("true", "false")) return MirrorResult.BadResponse("shape")
        // 201 means new and 200 means replay: a hub that mixes them up is not trusted
        if ((replay == "true") != replayExpected) return MirrorResult.BadResponse("replay")
        if (eventId != draft.eventId || sequence != draft.sequence || hash != link.entryHash) return MirrorResult.BadResponse("echo")
        return MirrorResult.Ok(Appended(eventId, sequence, hash, replay == "true"))
    }

    private fun parseHead(text: String): ChainHead? {
        val head = obj(text)?.get("head") as? JsonObject ?: return null
        return headOf(head)
    }

    private fun headOf(head: JsonObject): ChainHead? {
        val sequence = head.long("sequence") ?: return null
        val hash = head.string("entryHash") ?: return null
        if (sequence < 0 || !isHash(hash)) return null
        if ((sequence == 0L) != (hash == AuditHash.GENESIS)) return null
        return ChainHead(sequence, hash)
    }

    private fun parsePage(text: String, chainId: String, before: Long?, limit: Int): MirrorResult<RecentPage> {
        val root = obj(text) ?: return MirrorResult.BadResponse("shape")
        if (root.string("chainId") != chainId) return MirrorResult.BadResponse("chain")
        val head = (root["head"] as? JsonObject)?.let(::headOf) ?: return MirrorResult.BadResponse("head")
        val rows = root["entries"] as? KJsonArray ?: return MirrorResult.BadResponse("entries")
        if (rows.size > limit) return MirrorResult.BadResponse("limit")
        val entries = mutableListOf<MirrorEntry>()
        for (row in rows) {
            val o = row as? JsonObject ?: return MirrorResult.BadResponse("entries")
            val eventId = o.string("eventId")
            val sequence = o.long("sequence")
            val entry = o.string("entry")
            val previous = o.string("previousHash")
            val hash = o.string("entryHash")
            if (eventId == null || sequence == null || entry == null || previous == null || hash == null) return MirrorResult.BadResponse("entries")
            if (!isHash(previous) || !isHash(hash)) return MirrorResult.BadResponse("entries")
            val bytes = entry.toByteArray(Charsets.UTF_8)
            if (bytes.size > AuditHash.MAX_CANONICAL_BYTES) return MirrorResult.BadResponse("entries")
            // the hub's word is not enough: the bytes must hash to the hash, and say what the row claims
            if (AuditHash.of(previous, bytes) != hash) return MirrorResult.BadResponse("hash")
            val draft = runCatching { AuditCanonical.draftOf(bytes) }.getOrNull() ?: return MirrorResult.BadResponse("entries")
            if (draft.eventId != eventId || draft.sequence != sequence || draft.chainId != chainId) return MirrorResult.BadResponse("entries")
            if (!AuditCanonical.bytes(draft).contentEquals(bytes)) return MirrorResult.BadResponse("canonical")
            entries += MirrorEntry(eventId, sequence, bytes, previous, hash)
        }
        for (i in 1 until entries.size) {
            val newer = entries[i - 1]
            val older = entries[i]
            if (older.sequence != newer.sequence - 1) return MirrorResult.BadResponse("order")
            if (newer.previousHash != older.entryHash) return MirrorResult.BadResponse("link")
        }
        if (entries.isNotEmpty()) {
            val first = entries.first()
            if (before == null && (first.sequence != head.sequence || first.entryHash != head.entryHash)) return MirrorResult.BadResponse("head")
            if (before != null && first.sequence >= before) return MirrorResult.BadResponse("cursor")
            if (first.sequence > head.sequence) return MirrorResult.BadResponse("head")
            if (entries.last().sequence == 1L && entries.last().previousHash != AuditHash.GENESIS) return MirrorResult.BadResponse("genesis")
        } else if (before == null && head.sequence != 0L) {
            return MirrorResult.BadResponse("head")
        }
        val cursor = root["nextCursor"]
        val next: Long? = when {
            cursor == null || cursor is JsonNull -> null
            cursor is JsonObject -> cursor.long("beforeSequence")?.takeIf { it >= 1 && entries.isNotEmpty() && it == entries.last().sequence }
                ?: return MirrorResult.BadResponse("cursor")
            else -> return MirrorResult.BadResponse("cursor")
        }
        if (next == null && entries.isNotEmpty() && entries.last().sequence != 1L) {
            // the hub says there is nothing older, yet the oldest row it sent is not the first of the chain
            return MirrorResult.BadResponse("coverage")
        }
        if (next != null && entries.last().sequence == 1L) return MirrorResult.BadResponse("cursor")
        return MirrorResult.Ok(RecentPage(head, entries, next))
    }

    companion object {
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun isHash(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        /** The body is built from the stored bytes and hashes only. */
        fun appendBody(link: ChainLink): String = buildJsonObject {
            put("entry", link.canonicalBytes.toString(Charsets.UTF_8))
            put("previousHash", link.previousHash)
            put("entryHash", link.entryHash)
        }.toString()

        private fun obj(text: String): JsonObject? = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.content.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } && it.length <= 15 }?.toLongOrNull()
        }
    }
}

/** How the phone's retained chain head compares with the hub's. A mismatch is shown, never silently repaired. */
enum class AnchorState {
    /** Same sequence, same hash. */
    IN_SYNC,

    /** The hub holds a verified prefix of the phone's chain: entries are waiting to be mirrored. */
    HUB_BEHIND,

    /** The hub holds entries the phone does not have (a restore, or another phone with the same chain). */
    HUB_AHEAD,

    /** Same sequence, different hash, or a prefix that does not match: one side was altered or forked. */
    MISMATCH,
}

object AuditAnchor {
    /** `localHashAt(n)` returns the phone's entry hash at sequence n, or null when it holds no such entry. */
    fun compare(local: ChainHead, remote: ChainHead, localHashAt: (Long) -> String?): AnchorState = when {
        remote.sequence == local.sequence -> if (remote.entryHash == local.entryHash) AnchorState.IN_SYNC else AnchorState.MISMATCH
        remote.sequence < local.sequence ->
            if (remote.sequence == 0L || localHashAt(remote.sequence) == remote.entryHash) AnchorState.HUB_BEHIND else AnchorState.MISMATCH
        else -> AnchorState.HUB_AHEAD
    }
}
