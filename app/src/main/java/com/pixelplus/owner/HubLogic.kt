package com.pixelplus.owner

import com.pixelplus.owner.ui.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure hub contract: URL checks, JSON, the decide body, "what is new", and screen state.
 * No Android types, no tokens in thrown messages, and no code-signing field is ever built.
 */

private val JSON = Json { ignoreUnknownKeys = true }

private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

data class HubConfig(val baseUrl: String, val ownerToken: String, val pullToken: String) {
    val isLive: Boolean get() = baseUrl.isNotBlank() && ownerToken.isNotBlank()
}

enum class OwnerAction { APPROVE, REFUSE }

data class LicenceRequest(
    val id: String,
    val product: String,
    val kind: String,
    val device: String,
    val shop: String,
    val version: String,
    val createdAt: Long,
    val ownerDecision: String?,
    val ownerDecidedAt: Long? = null,
)

data class LicenceEvent(
    val id: String,
    val at: Long,
    val requestId: String?,
    val event: String,
    val detail: String
)

data class PullSummary(val shown: Int, val known: Int, val unregistered: Int, val bytes: Long)

data class DecideAck(val status: String, val ownerDecision: String?, val repeat: Boolean)

data class RequestState(val status: String, val reason: String?, val ownerDecision: String?)

data class MailboxBatch(
    val id: String,
    val installId: String,
    val known: Int,
    val receivedAt: Long,
    val size: Long,
)

sealed class Parse<out T> {
    data class Ok<T>(val value: T) : Parse<T>()
    data class Bad(val reason: String) : Parse<Nothing>()
}

sealed class UrlResult {
    data class Ok(val url: String) : UrlResult()
    data class Reject(val reason: String) : UrlResult()
}

data class SeenMarks(
    val requestsReady: Boolean,
    val eventsReady: Boolean,
    val requestIds: List<String>,
    val eventIds: List<String>
) {
    companion object {
        val EMPTY = SeenMarks(false, false, emptyList(), emptyList())
    }
}

data class FreshNotice(
    val newRequestIds: List<String>,
    val newEventIds: List<String>,
    val next: SeenMarks
)

data class RequestCard(
    val id: String,
    val title: String,
    val detail: String,
    val status: String,
    val kind: String,
    val pending: Boolean,
    val canApprove: Boolean,
    val canReject: Boolean,
    val demo: Boolean,
    val device: String = "",
    val shop: String = "",
    val version: String = "",
    val createdAtEpoch: Long = 0L,
    val ownerDecision: String? = null,
)

data class RequestsPresentation(
    val demo: Boolean,
    val loading: Boolean,
    val errorCode: String?,
    val cards: List<RequestCard>,
    val truncated: Boolean
)

data class AlertCard(
    val id: String,
    val title: String,
    val detail: String,
    val demo: Boolean,
    val read: Boolean = false,
    val eventKind: String = "",
    val eventAtEpoch: Long = 0L,
    val requestId: String? = null,
    val extraDetail: String = "",
)

data class AlertsPresentation(
    val demo: Boolean,
    val loading: Boolean,
    val errorCode: String?,
    val cards: List<AlertCard>,
    val pullNote: String?,
    val pullSummary: String?,
    val pullError: String?
)

data class HomeLine(val label: String, val value: String)

data class CompaniesPresentation(val demo: Boolean, val names: List<String>, val note: String?)

sealed class SavePlan {
    data class Accept(val config: HubConfig) : SavePlan()
    data class Reject(val reason: String) : SavePlan()
}

fun canonicalId(id: String): String? {
    val trimmed = id.trim().lowercase()
    return if (UUID_RE.matches(trimmed)) trimmed else null
}

fun cleanToken(raw: String): String? {
    val token = raw.trim()
    if (token.isEmpty() || token.length > 500) return null
    if (token.any { it.isWhitespace() || it.isISOControl() }) return null
    return token
}

fun normalizeHubUrl(raw: String): UrlResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return UrlResult.Reject("empty")
    if (trimmed.length > 200) return UrlResult.Reject("too_long")
    // "https://" has an empty authority. java.net.URI (JVM and Android libcore)
    // throws URISyntaxException ("Expected authority") before a host exists, so
    // the host check below never runs. That is a missing host. android.net.Uri
    // would accept it, but that class is stubbed in JVM unit tests and throws.
    if (isBareHttps(trimmed)) return UrlResult.Reject("bad_host")
    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        return UrlResult.Reject("bad_url")
    }
    val scheme = uri.scheme ?: return UrlResult.Reject("bad_url")
    if (!scheme.equals("https", ignoreCase = true)) return UrlResult.Reject("not_https")
    if (!uri.userInfo.isNullOrEmpty() || uri.rawAuthority?.contains('@') == true) {
        return UrlResult.Reject("userinfo")
    }
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return UrlResult.Reject("bad_host")
    // A present-but-empty query ("?") or fragment ("#") is still not an origin.
    if (uri.rawQuery != null || uri.rawFragment != null) return UrlResult.Reject("extra")
    val path = uri.path ?: ""
    if (path.isNotEmpty() && path != "/") return UrlResult.Reject("path")
    val port = when (val value = uri.port) {
        -1, 443 -> ""
        in 1..65535 -> ":$value"
        else -> return UrlResult.Reject("bad_url")
    }
    return UrlResult.Ok("https://$host$port")
}

/** True for "https://" with no host. Scheme case does not matter. */
private fun isBareHttps(raw: String): Boolean =
    raw.length == "https://".length &&
        raw.regionMatches(0, "https://", 0, raw.length, ignoreCase = true)

fun hostOnly(url: String): String = try {
    URI(url).host?.lowercase().orEmpty()
} catch (e: Exception) {
    ""
}

fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}

/** Body for POST /licence/decide. Approve never includes a code. Issue is not a phone action. */
fun decideBody(id: String, action: OwnerAction): String? {
    val canonical = canonicalId(id) ?: return null
    val quoted = jsonString(canonical)
    return when (action) {
        OwnerAction.APPROVE -> """{"id":$quoted,"action":"approve"}"""
        OwnerAction.REFUSE -> """{"id":$quoted,"action":"refuse","reason":"owner_refused"}"""
    }
}

fun formatEpoch(seconds: Long): String = try {
    CLOCK.format(Instant.ofEpochSecond(seconds)) + " UTC"
} catch (e: Exception) {
    seconds.toString()
}

fun parsePending(body: String): Parse<List<LicenceRequest>> {
    val rows = when (val parsed = jsonRows(body, "requests")) {
        is Parse.Bad -> return parsed
        is Parse.Ok -> parsed.value
    }
    val out = ArrayList<LicenceRequest>()
    for (row in rows) {
        val id = canonicalId(row.text("id") ?: return Parse.Bad("shape")) ?: return Parse.Bad("shape")
        val product = row.text("product") ?: return Parse.Bad("shape")
        val kind = row.text("kind") ?: return Parse.Bad("shape")
        val device = row.text("device") ?: return Parse.Bad("shape")
        val created = row.long("created_at") ?: return Parse.Bad("shape")
        out.add(
            LicenceRequest(
                id = id,
                product = product.take(48),
                kind = kind.take(20),
                device = device.take(20),
                shop = row.text("shop").orEmpty().take(80),
                version = row.text("version").orEmpty().take(20),
                createdAt = created,
                ownerDecision = row.text("owner_decision")?.take(20),
                ownerDecidedAt = row.long("owner_decided_at")
            )
        )
    }
    return Parse.Ok(out)
}

fun parseEvents(body: String): Parse<List<LicenceEvent>> {
    val rows = when (val parsed = jsonRows(body, "events")) {
        is Parse.Bad -> return parsed
        is Parse.Ok -> parsed.value
    }
    val out = ArrayList<LicenceEvent>()
    for (row in rows) {
        val id = row.text("id")?.takeIf { it.isNotBlank() && it.length <= 40 } ?: return Parse.Bad("shape")
        val at = row.long("at") ?: return Parse.Bad("shape")
        val event = row.text("event")?.take(40) ?: return Parse.Bad("shape")
        out.add(
            LicenceEvent(
                id = id,
                at = at,
                requestId = row.text("request_id")?.let { canonicalId(it) },
                event = event,
                detail = row.text("detail").orEmpty().take(200)
            )
        )
    }
    return Parse.Ok(out)
}

/** Counts only. The batch body and token hash are never copied into the result. */
fun parsePull(body: String): Parse<PullSummary> {
    val rows = when (val parsed = jsonRows(body, "batches")) {
        is Parse.Bad -> return parsed
        is Parse.Ok -> parsed.value
    }
    var known = 0
    var unregistered = 0
    var bytes = 0L
    for (row in rows) {
        when (row.long("known")) {
            1L -> known++
            0L -> unregistered++
            else -> return Parse.Bad("shape")
        }
        val size = row.long("size") ?: 0L
        if (size > 0) bytes += size
    }
    return Parse.Ok(PullSummary(rows.size, known, unregistered, bytes))
}

fun parseDecide(body: String): Parse<DecideAck> {
    val root = jsonObject(body) ?: return Parse.Bad("json")
    val status = root.text("status")?.takeIf { it.length <= 20 } ?: return Parse.Bad("shape")
    val repeat = when (val flag = root["repeat"]) {
        null, is JsonNull -> false
        is JsonPrimitive -> flag.booleanOrNull == true
        else -> false
    }
    return Parse.Ok(DecideAck(status, root.text("owner_decision")?.take(20), repeat))
}

fun parseErrorCode(body: String): String? {
    val root = jsonObject(body) ?: return null
    val code = root.text("error") ?: return null
    if (code.length > 40 || code.any { it.isISOControl() }) return null
    return code
}

fun encodeIds(ids: List<String>): String =
    ids.filter { it.isNotBlank() && it.none { ch -> ch == '\n' || ch == '\r' } }.joinToString("\n")

fun decodeIds(raw: String): List<String> = raw.split('\n').filter { it.isNotBlank() }

fun noticeNew(
    seen: SeenMarks,
    requestIds: List<String>?,
    eventIds: List<String>?,
    cap: Int = 400
): FreshNotice {
    var requestsReady = seen.requestsReady
    var eventsReady = seen.eventsReady
    var storedRequests = seen.requestIds
    var storedEvents = seen.eventIds
    val newRequests = ArrayList<String>()
    val newEvents = ArrayList<String>()
    if (requestIds != null) {
        if (requestsReady) {
            val known = storedRequests.toHashSet()
            for (id in requestIds) if (id !in known) newRequests.add(id)
        } else {
            requestsReady = true
        }
        storedRequests = rememberIds(requestIds, storedRequests, cap)
    }
    if (eventIds != null) {
        if (eventsReady) {
            val known = storedEvents.toHashSet()
            for (id in eventIds) if (id !in known) newEvents.add(id)
        } else {
            eventsReady = true
        }
        storedEvents = rememberIds(eventIds, storedEvents, cap)
    }
    return FreshNotice(
        newRequests,
        newEvents,
        SeenMarks(requestsReady, eventsReady, storedRequests, storedEvents)
    )
}

fun notificationText(context: android.content.Context, fresh: FreshNotice): String? {
    if (fresh.newRequestIds.isEmpty() && fresh.newEventIds.isEmpty()) return null
    val parts = ArrayList<String>(2)
    if (fresh.newRequestIds.isNotEmpty()) {
        val count = fresh.newRequestIds.size
        parts.add(context.text("notification_new_requests", count))
    }
    if (fresh.newEventIds.isNotEmpty()) {
        val count = fresh.newEventIds.size
        parts.add(context.text("notification_new_events", count))
    }
    return parts.joinToString(" — ")
}

/** The phone may call only these hub paths. /ack and the install registry are refused. */
fun rejectedHubPath(method: String, url: String): String? {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        return "forbidden"
    }
    val path = uri.rawPath ?: return "forbidden"
    if (path.contains("/ack")) return "ack"
    val get = method.equals("GET", ignoreCase = true)
    val post = method.equals("POST", ignoreCase = true)
    val allowed = when {
        get && (path == "/licence/pending" || path == "/licence/events" || path == "/pull") -> true
        get && (path == "/audit/recent" || path == "/installs" || path == "/licence/history" || path == "/owner/capabilities") -> true
        post && (path == "/licence/decide" || path == "/licence/states" || path == "/audit/append") -> true
        post && path == "/installs" -> return "installs"
        else -> false
    }
    return if (allowed) null else "forbidden"
}

fun pendingAfter(raw: String): String? {
    val parts = raw.split(':')
    if (parts.size != 2) return null
    val at = parts[0].toLongOrNull() ?: return null
    if (at < 0 || parts[0].length > 16 || (parts[0].length > 1 && parts[0].startsWith('0'))) return null
    val id = canonicalId(parts[1]) ?: return null
    return "$at:$id"
}

fun statesBody(ids: List<String>): String? {
    val clean = ids.mapNotNull { canonicalId(it) }.distinct().take(50)
    if (clean.isEmpty()) return null
    return """{"ids":[${clean.joinToString(",") { jsonString(it) }}]}"""
}

/** Counts and safe ids only. The batch body and token hash are never copied. */
fun parseMailbox(body: String): Parse<List<MailboxBatch>> {
    val rows = when (val parsed = jsonRows(body, "batches")) {
        is Parse.Bad -> return parsed
        is Parse.Ok -> parsed.value
    }
    val out = ArrayList<MailboxBatch>(rows.size)
    for (row in rows) {
        val id = row.text("id")?.takeIf { it.isNotBlank() && it.length <= 80 && it.none { ch -> ch.isWhitespace() } }
            ?: return Parse.Bad("shape")
        val known = row.long("known") ?: return Parse.Bad("shape")
        if (known != 0L && known != 1L) return Parse.Bad("shape")
        out.add(
            MailboxBatch(
                id = id,
                installId = row.text("install_id").orEmpty().take(80),
                known = known.toInt(),
                receivedAt = row.long("received_at") ?: 0L,
                size = (row.long("size") ?: 0L).coerceAtLeast(0L),
            ),
        )
    }
    return Parse.Ok(out)
}

fun parseStates(body: String): Parse<Map<String, RequestState>> {
    val root = jsonObject(body) ?: return Parse.Bad("json")
    val states = root["states"] as? JsonObject ?: return Parse.Bad("shape")
    val out = linkedMapOf<String, RequestState>()
    for ((key, value) in states) {
        val id = canonicalId(key) ?: return Parse.Bad("shape")
        val row = value as? JsonObject ?: return Parse.Bad("shape")
        val status = row.text("status")?.takeIf { it.length in 1..20 } ?: return Parse.Bad("shape")
        out[id] = RequestState(
            status = status,
            reason = row.text("reason")?.take(40),
            ownerDecision = row.text("owner_decision")?.take(20),
        )
    }
    return Parse.Ok(out)
}

fun planSave(
    saved: HubConfig,
    urlRaw: String,
    ownerRaw: String,
    pullRaw: String,
    clearPull: Boolean
): SavePlan {
    val url = when (val parsed = normalizeHubUrl(urlRaw)) {
        is UrlResult.Ok -> parsed.url
        is UrlResult.Reject -> return SavePlan.Reject(parsed.reason)
    }
    val owner = if (ownerRaw.isBlank()) {
        saved.ownerToken
    } else {
        cleanToken(ownerRaw) ?: return SavePlan.Reject("bad_token")
    }
    if (owner.isBlank()) return SavePlan.Reject("empty_token")
    val pull = when {
        clearPull -> ""
        pullRaw.isBlank() -> saved.pullToken
        else -> cleanToken(pullRaw) ?: return SavePlan.Reject("bad_pull_token")
    }
    return SavePlan.Accept(HubConfig(url, owner, pull))
}

fun presentRequests(
    config: HubConfig,
    demo: DemoState,
    loading: Boolean,
    errorCode: String?,
    live: List<LicenceRequest>?,
    completeSnapshot: Boolean = false,
): RequestsPresentation {
    if (!config.isLive) {
        return RequestsPresentation(
            demo = true,
            loading = false,
            errorCode = null,
            cards = demo.visibleRequests().map { demoCard(it) },
            truncated = false
        )
    }
    val cards = live.orEmpty().map { liveCard(it) }
    return RequestsPresentation(
        demo = false,
        loading = loading && live == null,
        errorCode = errorCode,
        cards = cards,
        truncated = !completeSnapshot && (live?.size ?: 0) >= 50
    )
}

fun presentAlerts(
    context: android.content.Context,
    config: HubConfig,
    demo: DemoState,
    loading: Boolean,
    errorCode: String?,
    events: List<LicenceEvent>?,
    pull: PullSummary?,
    pullError: String?,
): AlertsPresentation {
    if (!config.isLive) {
        return AlertsPresentation(
            demo = true,
            loading = false,
            errorCode = null,
            cards = demo.visibleAlerts().map {
                AlertCard(it.id, it.title, it.detail, demo = true, read = it.read)
            },
            pullNote = null,
            pullSummary = null,
            pullError = null
        )
    }
    return AlertsPresentation(
        demo = false,
        loading = loading && events == null,
        errorCode = errorCode,
        cards = events.orEmpty().map { eventCard(it) },
        pullNote = if (config.pullToken.isBlank()) com.pixelplus.owner.ui.text(context, "pull_token_missing") else null,
        pullSummary = pull?.let { pullSummaryText(context, it) },
        pullError = pullError
    )
}

fun pullSummaryText(context: android.content.Context, summary: PullSummary): String =
    com.pixelplus.owner.ui.text(context, "pull_summary", summary.shown, summary.known, summary.unregistered, summary.bytes)

fun homeLines(
    context: android.content.Context,
    config: HubConfig,
    demo: DemoState,
    requests: List<LicenceRequest>?,
    events: List<LicenceEvent>?,
): List<HomeLine> {
    val t = { key: String -> com.pixelplus.owner.ui.text(context, key) }
    if (!config.isLive) {
        return listOf(
            HomeLine(t("home_companies"), demo.companies.size.toString()),
            HomeLine(t("home_review_requests"), demo.visibleRequests().count { it.status == RequestStatus.PENDING }.toString()),
            HomeLine(t("home_unread_alerts"), demo.visibleAlerts().count { !it.read }.toString()),
        )
    }
    return listOf(
        HomeLine(t("home_pending"), (requests?.size ?: 0).toString()),
        HomeLine(t("home_events"), (events?.size ?: 0).toString()),
        HomeLine(t("home_live_mode"), t("home_live")),
    )
}

fun presentCompanies(context: android.content.Context, config: HubConfig, demo: DemoState, requests: List<LicenceRequest>?): CompaniesPresentation {
    if (!config.isLive) return CompaniesPresentation(true, demo.companies.map { it.name }, null)
    return CompaniesPresentation(
        demo = false,
        names = requests.orEmpty().map { it.product }.distinct(),
        note = com.pixelplus.owner.ui.text(context, "hub_products_note"),
    )
}

fun failureKey(code: String?): String? = when (code) {
    null, "" -> null
    "unauthorised", "401" -> "err_unauthorised"
    "network", "io" -> "err_network"
    "redirect" -> "err_redirect"
    "not_https" -> "err_not_https"
    "bad_response", "json", "shape" -> "err_bad_response"
    "bad_url", "bad_host", "empty", "path", "extra", "userinfo", "too_long" -> "err_bad_url"
    "empty_token", "bad_token", "no_token" -> "err_empty_token"
    "bad_pull_token" -> "err_bad_pull"
    "no_pull_token" -> "err_no_pull"
    "closed" -> "err_closed"
    "stale" -> "err_stale"
    "expired" -> "err_expired"
    "not found" -> "err_not_found"
    "code" -> "err_code"
    "fields" -> "err_fields"
    "too_large" -> "err_too_large"
    "http" -> "err_http"
    else -> "err_generic"
}

fun failureMessage(context: android.content.Context, code: String?): String {
    val key = failureKey(code) ?: return ""
    return com.pixelplus.owner.ui.text(context, key)
}

private fun rememberIds(current: List<String>, previous: List<String>, cap: Int): List<String> {
    if (cap <= 0) return emptyList()
    val out = LinkedHashSet<String>()
    for (id in current) {
        if (out.size >= cap) break
        if (id.isNotBlank()) out.add(id)
    }
    for (id in previous) {
        if (out.size >= cap) break
        if (id.isNotBlank()) out.add(id)
    }
    return out.toList()
}

private fun demoCard(req: ActivationRequest) = RequestCard(
    id = req.id,
    title = req.id + " • " + req.product,
    detail = req.kind.title + "\t" + req.deviceRef,
    status = req.status.title,
    kind = req.kind.name,
    pending = req.status == RequestStatus.PENDING,
    canApprove = false,
    canReject = false,
    demo = true
)

private fun liveCard(req: LicenceRequest) = RequestCard(
    id = req.id,
    title = req.product,
    detail = "",
    status = req.ownerDecision ?: "",
    kind = req.kind,
    pending = true,
    canApprove = req.ownerDecision != "approved",
    canReject = true,
    demo = false,
    device = req.device,
    shop = req.shop,
    version = req.version,
    createdAtEpoch = req.createdAt,
    ownerDecision = req.ownerDecision,
)

private fun eventCard(event: LicenceEvent): AlertCard {
    return AlertCard(
        id = event.id,
        title = event.event,
        detail = "",
        demo = false,
        eventKind = event.event,
        eventAtEpoch = event.at,
        requestId = event.requestId,
        extraDetail = event.detail,
    )
}

private fun jsonRows(body: String, key: String): Parse<List<JsonObject>> {
    val root = jsonObject(body) ?: return Parse.Bad("json")
    val arr = root[key] as? JsonArray ?: return Parse.Bad("shape")
    val rows = ArrayList<JsonObject>(arr.size)
    for (el in arr) {
        rows.add(el as? JsonObject ?: return Parse.Bad("shape"))
    }
    return Parse.Ok(rows)
}

private fun jsonObject(body: String): JsonObject? = try {
    JSON.parseToJsonElement(body) as? JsonObject
} catch (e: Exception) {
    null
}

private fun JsonObject.text(key: String): String? = this[key]?.textOrNull()

private fun JsonObject.long(key: String): Long? = this[key]?.longOrNullValue()

private fun JsonElement.textOrNull(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

private fun JsonElement.longOrNullValue(): Long? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> longOrNull ?: content.toLongOrNull()
    else -> null
}
