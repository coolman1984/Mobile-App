package com.pixelplus.owner.audit

import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubRequest
import com.pixelplus.owner.HubTransport
import com.pixelplus.owner.HubTransportResult
import com.pixelplus.owner.UrlResult
import com.pixelplus.owner.normalizeHubUrl
import com.pixelplus.owner.parseErrorCode
import com.pixelplus.owner.rejectedHubPath

/**
 * The one request path shared by the staged owner clients (audit mirror, M13 read routes). Owner token only; the path allow-list
 * (`rejectedHubPath`) is checked before any request; a status the caller does not handle maps to one fixed outcome.
 */
internal class MirrorHttp(private val transport: HubTransport) {
    fun <T> exchange(
        config: HubConfig,
        method: String,
        path: String,
        body: String?,
        handle: (Int, String) -> MirrorResult<T>?,
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
            is HubTransportResult.Response -> interpret(raw.code, raw.body, handle)
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
}
