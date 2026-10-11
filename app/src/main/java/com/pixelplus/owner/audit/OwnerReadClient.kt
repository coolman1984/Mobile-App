package com.pixelplus.owner.audit

import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * M13: the phone's reader for the hub's read-only owner routes. Staged like the audit mirror: nothing calls it yet, an older Worker's
 * 404 is [MirrorResult.NotAvailable], the owner token is the only credential, and there is no write. Every reply is checked: a field
 * that must never reach the phone (a token hash, a code, a shop text) makes the whole reply [MirrorResult.BadResponse].
 */
data class Capabilities(val protocol: Int, val routes: Set<String>) {
    fun has(route: String) = route in routes
}

data class InstallsPage(val ids: List<String>, val nextAfterId: String?, val coverage: String)

data class HistoryRow(
    val id: String,
    val product: String,
    val kind: String,
    val device: String,
    val status: String,
    val reason: String,
    val ownerDecision: String?,
    val createdAt: Long,
    val decidedAt: Long?,
    val deliveredAt: Long?,
)

data class HistoryPage(val rows: List<HistoryRow>, val nextAfter: String?, val coverage: String)

data class EventsPage(val ids: List<Long>, val nextBeforeId: Long?)

class OwnerReadClient(transport: HubTransport) {
    private val http = MirrorHttp(transport)

    fun capabilities(config: HubConfig): MirrorResult<Capabilities> =
        http.exchange(config, "GET", "/owner/capabilities", null) { code, text ->
            if (code != 200) return@exchange null
            val o = obj(text) ?: return@exchange MirrorResult.BadResponse("shape")
            val protocol = o.long("protocol")?.toInt() ?: return@exchange MirrorResult.BadResponse("shape")
            val routes = (o["routes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (routes == null || routes.any { it == null || it.length > 80 } || routes.size > 64) return@exchange MirrorResult.BadResponse("routes")
            MirrorResult.Ok(Capabilities(protocol, routes.filterNotNull().toSet()))
        }

    fun installs(config: HubConfig, afterId: String? = null, limit: Int = 100): MirrorResult<InstallsPage> {
        if (afterId != null && !UUID.matches(afterId)) return MirrorResult.Rejected("bad_cursor")
        val path = "/installs?limit=${limit.coerceIn(1, 100)}" + (afterId?.let { "&afterId=$it" } ?: "")
        return http.exchange(config, "GET", path, null) { code, text ->
            if (code != 200) return@exchange null
            val o = obj(text) ?: return@exchange MirrorResult.BadResponse("shape")
            val rows = o["installs"] as? JsonArray ?: return@exchange MirrorResult.BadResponse("shape")
            val ids = rows.map { row ->
                val r = row as? JsonObject ?: return@exchange MirrorResult.BadResponse("shape")
                if (r.keys != setOf("id")) return@exchange MirrorResult.BadResponse("leak")   // a hash or any extra field: refuse the whole page
                r.string("id")?.takeIf { UUID.matches(it) } ?: return@exchange MirrorResult.BadResponse("shape")
            }
            if (ids.size > limit.coerceIn(1, 100) || ids.zipWithNext().any { (a, b) -> a >= b } || (afterId != null && ids.any { it <= afterId })) {
                return@exchange MirrorResult.BadResponse("order")
            }
            val next = cursorString(o["nextCursor"], "afterId") ?: if (o["nextCursor"].isNullCursor()) null else return@exchange MirrorResult.BadResponse("cursor")
            if (next != null && (ids.isEmpty() || next != ids.last())) return@exchange MirrorResult.BadResponse("cursor")
            MirrorResult.Ok(InstallsPage(ids, next, o.string("coverage") ?: ""))
        }
    }

    fun history(config: HubConfig, status: String? = null, after: String? = null, limit: Int = 50): MirrorResult<HistoryPage> {
        if (status != null && status !in STATUSES) return MirrorResult.Rejected("bad_status")
        if (after != null && !AFTER.matches(after)) return MirrorResult.Rejected("bad_cursor")
        val path = "/licence/history?limit=${limit.coerceIn(1, 100)}" + (status?.let { "&status=$it" } ?: "") + (after?.let { "&after=$it" } ?: "")
        return http.exchange(config, "GET", path, null) { code, text ->
            if (code != 200) return@exchange null
            val o = obj(text) ?: return@exchange MirrorResult.BadResponse("shape")
            val rows = o["requests"] as? JsonArray ?: return@exchange MirrorResult.BadResponse("shape")
            val parsed = rows.map { row ->
                val r = row as? JsonObject ?: return@exchange MirrorResult.BadResponse("shape")
                if (r.keys.any { it in FORBIDDEN }) return@exchange MirrorResult.BadResponse("leak")
                HistoryRow(
                    id = r.string("id")?.takeIf { UUID.matches(it) } ?: return@exchange MirrorResult.BadResponse("shape"),
                    product = r.string("product") ?: return@exchange MirrorResult.BadResponse("shape"),
                    kind = r.string("kind") ?: return@exchange MirrorResult.BadResponse("shape"),
                    device = r.string("device") ?: return@exchange MirrorResult.BadResponse("shape"),
                    status = r.string("status")?.takeIf { it in STATUSES } ?: return@exchange MirrorResult.BadResponse("status"),
                    reason = r.string("reason") ?: "",
                    ownerDecision = r.string("owner_decision"),
                    createdAt = r.long("created_at") ?: return@exchange MirrorResult.BadResponse("shape"),
                    decidedAt = r.long("decided_at"),
                    deliveredAt = r.long("delivered_at"),
                )
            }
            if (parsed.size > limit.coerceIn(1, 100) || parsed.zipWithNext().any { (a, b) -> a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id >= b.id) }) {
                return@exchange MirrorResult.BadResponse("order")
            }
            if (status != null && parsed.any { it.status != status }) return@exchange MirrorResult.BadResponse("status")
            val next = cursorString(o["nextCursor"], "after") ?: if (o["nextCursor"].isNullCursor()) null else return@exchange MirrorResult.BadResponse("cursor")
            if (next != null && (parsed.isEmpty() || next != "${parsed.last().createdAt}:${parsed.last().id}")) return@exchange MirrorResult.BadResponse("cursor")
            MirrorResult.Ok(HistoryPage(parsed, next, o.string("coverage") ?: ""))
        }
    }

    /** Only the page position of the older events; the event rows themselves are read by the existing HubClient parser. */
    fun eventsCursor(body: String): Long? {
        val o = obj(body) ?: return null
        return (o["nextCursor"] as? JsonObject)?.long("beforeId")
    }

    companion object {
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val AFTER = Regex("^[0-9]{1,12}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val STATUSES = setOf("pending", "issued", "refused", "delivered", "expired")
        private val FORBIDDEN = setOf("code", "poll_hash", "shop", "ref", "src", "machine", "token_hash", "version", "nonce")
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun obj(text: String): JsonObject? = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(key: String): Long? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.content.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } && it.length <= 15 }?.toLongOrNull()
        }
        private fun JsonObject.cursorValue(key: String) = string(key)
        private fun cursorString(v: kotlinx.serialization.json.JsonElement?, key: String): String? = (v as? JsonObject)?.cursorValue(key)
        private fun kotlinx.serialization.json.JsonElement?.isNullCursor() = this == null || this is JsonNull
    }
}
