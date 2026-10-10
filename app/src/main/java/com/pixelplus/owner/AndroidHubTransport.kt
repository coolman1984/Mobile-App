package com.pixelplus.owner

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** HTTPS only. The bearer stays in the header and is never written to a log. Redirects are not followed. */
class AndroidHubTransport : HubTransport {
    override fun exchange(request: HubRequest): HubTransportResult {
        if (request.bearer.isBlank()) return HubTransportResult.Failed("no_token")
        if (request.url.contains(request.bearer)) return HubTransportResult.Failed("bad_url")
        val uri = try {
            URI(request.url)
        } catch (e: Exception) {
            return HubTransportResult.Failed("bad_url")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return HubTransportResult.Failed("not_https")
        if (!uri.userInfo.isNullOrEmpty()) return HubTransportResult.Failed("userinfo")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${request.bearer}")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("User-Agent", "PixelPlusOwner/0.2")
                if (request.jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (request.jsonBody != null) {
                connection.outputStream.use { it.write(request.jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in 300..399) return HubTransportResult.Failed("redirect")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val cap = if (request.url.contains("/pull?")) 8_000_000 else 1_000_000
            val text = if (stream == null) {
                ""
            } else {
                stream.use { readCapped(it, cap) } ?: return HubTransportResult.Failed("too_large")
            }
            return HubTransportResult.Response(code, text)
        } catch (e: Exception) {
            return HubTransportResult.Failed("io")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(stream: InputStream, cap: Int): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > cap) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8)
    }
}
