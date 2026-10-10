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
    fun pending(config: HubConfig, limit: Int = 50): HubResult<List<LicenceRequest>> =
        authedGet(config, config.ownerToken, "/licence/pending?limit=${limit.coerceIn(1, 100)}", ::parsePending)

    fun events(config: HubConfig, limit: Int = 100): HubResult<List<LicenceEvent>> =
        authedGet(config, config.ownerToken, "/licence/events?limit=${limit.coerceIn(1, 200)}", ::parseEvents)

    fun pull(config: HubConfig, limit: Int = 10): HubResult<PullSummary> {
        if (config.pullToken.isBlank()) return HubResult.Rejected("no_pull_token")
        return authedGet(config, config.pullToken, "/pull?limit=${limit.coerceIn(1, 20)}", ::parsePull)
    }

    fun decide(config: HubConfig, id: String, action: OwnerAction): HubResult<DecideAck> {
        val body = decideBody(id, action) ?: return HubResult.Rejected("bad_id")
        val base = baseOf(config)
        if (base is HubBase.No) return HubResult.Rejected(base.reason)
        val raw = transport.exchange(HubRequest("POST", base.url + "/licence/decide", config.ownerToken, body))
        return interpret(raw, ::parseDecide)
    }

    private fun <T> authedGet(
        config: HubConfig,
        token: String,
        path: String,
        parse: (String) -> Parse<T>
    ): HubResult<T> {
        if (token.isBlank()) return HubResult.Rejected("no_token")
        val base = baseOf(config)
        if (base is HubBase.No) return HubResult.Rejected(base.reason)
        val raw = transport.exchange(HubRequest("GET", base.url + path, token, null))
        return interpret(raw, parse)
    }

    private fun baseOf(config: HubConfig): HubBase = when (val url = normalizeHubUrl(config.baseUrl)) {
        is UrlResult.Ok -> HubBase.Ready(url.url)
        is UrlResult.Reject -> HubBase.No(url.reason)
    }

    private fun <T> interpret(raw: HubTransportResult, parse: (String) -> Parse<T>): HubResult<T> {
        if (raw is HubTransportResult.Failed) return HubResult.Network(raw.kind)
        if (raw.code == 401) return HubResult.Http(401, parseErrorCode(raw.body) ?: "unauthorised")
        if (raw.code !in 200..299) return HubResult.Http(raw.code, parseErrorCode(raw.body) ?: "http")
        return when (val parsed = parse(raw.body)) {
            is Parse.Ok -> HubResult.Ok(parsed.value)
            is Parse.Bad -> HubResult.BadResponse(parsed.reason)
        }
    }
}

private sealed class HubBase {
    data class Ready(val url: String) : HubBase()
    data class No(val reason: String) : HubBase()
}

fun failureCode(result: HubResult<*>): String = when (result) {
    is HubResult.Ok -> ""
    is HubResult.Http -> result.error.ifBlank { "http" }
    is HubResult.BadResponse -> result.reason
    is HubResult.Network -> result.kind
    is HubResult.Rejected -> result.reason
}
