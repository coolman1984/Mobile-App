package com.pixelplus.owner

/**
 * Talks to the hub through a transport the tests can replace.
 * The owner token and the pull token are different callers. This class never calls /ack.
 */

data class HubRequest(val method: String, val url: String, val bearer: String, val jsonBody: String?)

sealed class HubTransportResult {
    data class Response(val code: Int, val body: String) : HubTransportResult()
    data class Failed(val kind: String) : HubTransportResult()
}

fun interface HubTransport {
    fun exchange(request: HubRequest): HubTransportResult
}

sealed class HubResult<out T> {
    data class Ok<T>(val value: T) : HubResult<T>()
    data class Http(val code: Int, val error: String) : HubResult<Nothing>()
    data class BadResponse(val reason: String) : HubResult<Nothing>()
    data class Network(val kind: String) : HubResult<Nothing>()
    data class Rejected(val reason: String) : HubResult<Nothing>()
}

class HubClient(private val transport: HubTransport) {
    fun pending(config: HubConfig, limit: Int = 50, after: String? = null): HubResult<List<LicenceRequest>> {
        val cursor = if (after.isNullOrBlank()) "" else pendingAfter(after) ?: return HubResult.Rejected("bad_cursor")
        val path = buildString {
            append("/licence/pending?limit=${limit.coerceIn(1, 100)}")
            if (cursor.isNotEmpty()) append("&after=").append(cursor)
        }
        return exchange(config, "GET", path, config.ownerToken, null, ::parsePending)
    }

    /** Exhaust the existing stable created_at/id pages. A repeated cursor fails closed. */
    fun pendingAll(config: HubConfig): HubResult<List<LicenceRequest>> {
        val rows = mutableListOf<LicenceRequest>()
        val ids = mutableSetOf<String>()
        var after: String? = null
        repeat(100) {
            when (val page = pending(config, 100, after)) {
                is HubResult.Ok -> {
                    if (page.value.any { !ids.add(it.id) }) return HubResult.BadResponse("cursor")
                    rows.addAll(page.value)
                    if (page.value.size < 100) return HubResult.Ok(rows)
                    val last = page.value.last()
                    val next = "${last.createdAt}:${last.id}"
                    if (next == after) return HubResult.BadResponse("cursor")
                    after = next
                }
                else -> return page
            }
        }
        return HubResult.BadResponse("coverage")
    }

    fun events(config: HubConfig, limit: Int = 100): HubResult<List<LicenceEvent>> =
        exchange(config, "GET", "/licence/events?limit=${limit.coerceIn(1, 200)}", config.ownerToken, null, ::parseEvents)

    fun pull(config: HubConfig, limit: Int = 10): HubResult<PullSummary> {
        if (config.pullToken.isBlank()) return HubResult.Rejected("no_pull_token")
        return exchange(config, "GET", "/pull?limit=${limit.coerceIn(1, 20)}", config.pullToken, null, ::parsePull)
    }

    fun mailbox(config: HubConfig, limit: Int = 20): HubResult<List<MailboxBatch>> {
        if (config.pullToken.isBlank()) return HubResult.Rejected("no_pull_token")
        return exchange(config, "GET", "/pull?limit=${limit.coerceIn(1, 20)}", config.pullToken, null, ::parseMailbox)
    }

    fun states(config: HubConfig, ids: List<String>): HubResult<Map<String, RequestState>> {
        val body = statesBody(ids) ?: return HubResult.Rejected("bad_id")
        return exchange(config, "POST", "/licence/states", config.ownerToken, body, ::parseStates)
    }

    fun decide(config: HubConfig, id: String, action: OwnerAction): HubResult<DecideAck> {
        val body = decideBody(id, action) ?: return HubResult.Rejected("bad_id")
        return postDecideBody(config, body)
    }

    /** Posts a body that was stored earlier. The body is not rebuilt, so a retry cannot change the action. */
    fun postDecideBody(config: HubConfig, body: String): HubResult<DecideAck> {
        if (actionOfStoredBody(body) == null) return HubResult.Rejected("bad_body")
        return exchange(config, "POST", "/licence/decide", config.ownerToken, body, ::parseDecide)
    }

    private fun <T> exchange(
        config: HubConfig,
        method: String,
        path: String,
        token: String,
        body: String?,
        parse: (String) -> Parse<T>,
    ): HubResult<T> {
        if (token.isBlank()) return HubResult.Rejected("no_token")
        val base = when (val checked = baseOf(config)) {
            is HubBase.Ready -> checked.url
            is HubBase.No -> return HubResult.Rejected(checked.reason)
        }
        val url = base + path
        rejectedHubPath(method, url)?.let { return HubResult.Rejected(it) }
        val raw = transport.exchange(HubRequest(method, url, token, body))
        return interpret(raw, parse)
    }

    private fun baseOf(config: HubConfig): HubBase = when (val url = normalizeHubUrl(config.baseUrl)) {
        is UrlResult.Ok -> HubBase.Ready(url.url)
        is UrlResult.Reject -> HubBase.No(url.reason)
    }

    private fun <T> interpret(raw: HubTransportResult, parse: (String) -> Parse<T>): HubResult<T> =
        when (raw) {
            is HubTransportResult.Failed -> HubResult.Network(raw.kind)
            is HubTransportResult.Response -> interpretResponse(raw.code, raw.body, parse)
        }

    private fun <T> interpretResponse(
        code: Int,
        body: String,
        parse: (String) -> Parse<T>
    ): HubResult<T> {
        if (code == 401) return HubResult.Http(401, parseErrorCode(body) ?: "unauthorised")
        if (code !in 200..299) return HubResult.Http(code, parseErrorCode(body) ?: "http")
        return when (val parsed = parse(body)) {
            is Parse.Ok -> HubResult.Ok(parsed.value)
            is Parse.Bad -> HubResult.BadResponse(parsed.reason)
        }
    }
}

private sealed class HubBase {
    data class Ready(val url: String) : HubBase()
    data class No(val reason: String) : HubBase()
}

internal fun actionOfStoredBody(body: String): String? {
    if (body.length > 500) return null
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
    if (obj.keys != setOf("id", "action") && obj.keys != setOf("id", "action", "reason")) return null
    val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val verb = (obj["action"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val action = when (verb) { "approve" -> OwnerAction.APPROVE; "refuse" -> OwnerAction.REFUSE; else -> return null }
    return verb.takeIf { canonicalId(id) == id && decideBody(id, action) == body }
}

fun failureCode(result: HubResult<*>): String = when (result) {
    is HubResult.Ok -> ""
    is HubResult.Http -> result.error.ifBlank { "http" }
    is HubResult.BadResponse -> result.reason
    is HubResult.Network -> result.kind
    is HubResult.Rejected -> result.reason
}
