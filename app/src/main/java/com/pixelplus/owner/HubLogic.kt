package com.pixelplus.owner

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
    val ownerDecision: String?
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
    val demo: Boolean
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
    val read: Boolean = false
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
    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        return UrlResult.Reject("bad_url")
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return UrlResult.Reject("not_https")
    if (!uri.userInfo.isNullOrEmpty()) return UrlResult.Reject("userinfo")
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return UrlResult.Reject("bad_host")
    if (!uri.rawQuery.isNullOrEmpty() || uri.rawFragment != null) return UrlResult.Reject("extra")
    val path = uri.path ?: ""
    if (path.isNotEmpty() && path != "/") return UrlResult.Reject("path")
    val port = when (uri.port) {
        -1, 443 -> ""
        else -> ":${uri.port}"
    }
    return UrlResult.Ok("https://$host$port")
}

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

fun kindTitle(kind: String): String = when (kind) {
    "trial" -> "تجربة"
    "monthly" -> "اشتراك شهري"
    "permanent" -> "تفعيل دائم"
    else -> kind
}

fun decisionLabel(decision: String?): String = when (decision) {
    null, "" -> "في الانتظار"
    "approved" -> "تمت الموافقة. إصدار الكود يتم على الكمبيوتر"
    "expired" -> "الموافقة انتهت"
    "denied" -> "مرفوض"
    else -> decision
}

fun eventTitle(event: String): String = when (event) {
    "created" -> "طلب جديد"
    "approved" -> "موافقة مسجّلة"
    "refused" -> "طلب مرفوض"
    "issued" -> "كود صدر من الكمبيوتر"
    "delivered" -> "المحل استلم الكود"
    "reissued" -> "إعادة تسليم كود موجود"
    "tg_refused" -> "ضغط غير مقبول"
    "tg_stale" -> "موافقة قديمة"
    else -> event
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
                ownerDecision = row.text("owner_decision")?.take(20)
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

fun notificationText(fresh: FreshNotice): String? {
    if (fresh.newRequestIds.isEmpty() && fresh.newEventIds.isEmpty()) return null
    val parts = ArrayList<String>(2)
    if (fresh.newRequestIds.isNotEmpty()) parts.add("طلبات جديدة: ${fresh.newRequestIds.size}")
    if (fresh.newEventIds.isNotEmpty()) parts.add("أحداث جديدة: ${fresh.newEventIds.size}")
    return parts.joinToString(" — ")
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
    live: List<LicenceRequest>?
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
        truncated = (live?.size ?: 0) >= 50
    )
}

fun presentAlerts(
    config: HubConfig,
    demo: DemoState,
    loading: Boolean,
    errorCode: String?,
    events: List<LicenceEvent>?,
    pull: PullSummary?,
    pullError: String?
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
        pullNote = if (config.pullToken.isBlank()) "لم يُحفظ رمز سحب القياسات. تُعرض أحداث الترخيص فقط." else null,
        pullSummary = pull?.let { pullSummaryText(it) },
        pullError = pullError
    )
}

fun pullSummaryText(summary: PullSummary): String =
    "دفعات قياسات في هذه الصفحة: ${summary.shown}. معروفة: ${summary.known}. غير مسجّلة: ${summary.unregistered}. الحجم: ${summary.bytes} بايت. لم يُحذف شيء."

fun homeLines(
    config: HubConfig,
    demo: DemoState,
    requests: List<LicenceRequest>?,
    events: List<LicenceEvent>?
): List<HomeLine> {
    if (!config.isLive) {
        return listOf(
            HomeLine("الشركات", demo.companies.size.toString()),
            HomeLine("طلبات المراجعة", demo.visibleRequests().count { it.status == RequestStatus.PENDING }.toString()),
            HomeLine("تنبيهات غير مقروءة", demo.visibleAlerts().count { !it.read }.toString())
        )
    }
    return listOf(
        HomeLine("طلبات معلّقة", (requests?.size ?: 0).toString()),
        HomeLine("أحداث معروضة", (events?.size ?: 0).toString()),
        HomeLine("وضع العمل", "مباشر")
    )
}

fun presentCompanies(config: HubConfig, demo: DemoState, requests: List<LicenceRequest>?): CompaniesPresentation {
    if (!config.isLive) return CompaniesPresentation(true, demo.companies.map { it.name }, null)
    return CompaniesPresentation(
        demo = false,
        names = requests.orEmpty().map { it.product }.distinct(),
        note = "المركز لا يرسل قائمة شركات. هذه المنتجات الظاهرة في الطلبات المعلّقة."
    )
}

fun arabicFailure(code: String?): String = when (code) {
    null, "" -> ""
    "unauthorised", "401" -> "رُفض الرمز. راجع الرمز في الإعدادات."
    "network", "io" -> "تعذر الاتصال بالمركز."
    "redirect" -> "المركز أعاد تحويلاً. لم يُرسل الرمز إلى عنوان آخر."
    "not_https" -> "العنوان يجب أن يبدأ بـ https."
    "bad_response", "json", "shape" -> "رد غير مفهوم من المركز."
    "bad_url", "bad_host", "empty", "path", "extra", "userinfo", "too_long" ->
        "عنوان المركز غير مقبول. استخدم https من غير مسار ومن غير اسم مستخدم."
    "empty_token", "bad_token", "no_token" -> "رمز المالك مطلوب، من غير مسافات."
    "bad_pull_token" -> "رمز سحب القياسات غير مقبول."
    "no_pull_token" -> "رمز سحب القياسات غير محفوظ."
    "closed" -> "الطلب لم يعد معلّقًا."
    "stale" -> "الطلب قديم. اطلب من المحل إرسال طلب جديد."
    "expired" -> "الموافقة السابقة انتهت."
    "not found" -> "الطلب غير موجود."
    "code" -> "لا يُرسل أي كود من الهاتف."
    "fields" -> "المركز رفض القرار. قد يحتاج المركز إلى تحديث، ولم يُصدر أي كود."
    "too_large" -> "الرد أكبر من الحد. لم يُحذف شيء."
    "http" -> "المركز أعاد خطأ."
    else -> "تعذر إكمال العملية."
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
    detail = "النوع: " + req.kind.title + " | الجهاز: " + req.deviceRef,
    status = req.status.title,
    kind = req.kind.name,
    pending = req.status == RequestStatus.PENDING,
    canApprove = false,
    canReject = false,
    demo = true
)

private fun liveCard(req: LicenceRequest) = RequestCard(
    id = req.id,
    title = req.product + " • " + kindTitle(req.kind),
    detail = "الجهاز: " + req.device +
        "\nالمحل: " + req.shop.ifBlank { "—" } +
        (if (req.version.isBlank()) "" else "\nالإصدار: " + req.version) +
        "\nالوقت: " + formatEpoch(req.createdAt),
    status = decisionLabel(req.ownerDecision),
    kind = req.kind,
    pending = true,
    canApprove = req.ownerDecision != "approved",
    canReject = true,
    demo = false
)

private fun eventCard(event: LicenceEvent): AlertCard {
    val detail = buildString {
        append(formatEpoch(event.at))
        if (!event.requestId.isNullOrBlank()) append("\nالطلب: ").append(event.requestId.take(8))
        if (event.detail.isNotBlank()) append("\n").append(event.detail)
    }
    return AlertCard(event.id, eventTitle(event.event), detail, demo = false)
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
