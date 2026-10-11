package com.pixelplus.owner.audit

import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubRequest
import com.pixelplus.owner.HubTransport
import com.pixelplus.owner.HubTransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerReadClientTest {
    private val config = HubConfig("https://factory-hub.example", "owner-secret-value", "pull-secret-value")
    private fun uuid(n: Int) = "%08x-0000-4000-8000-000000000000".format(n)

    private class Rec(val reply: HubTransportResult) : HubTransport {
        val calls = mutableListOf<HubRequest>()
        override fun exchange(request: HubRequest): HubTransportResult { calls += request; return reply }
    }

    private fun client(code: Int, body: String): Pair<Rec, OwnerReadClient> = Rec(HubTransportResult.Response(code, body)).let { it to OwnerReadClient(it) }

    @Test fun capabilitiesAreReadWithTheOwnerTokenAndAnOldWorkerIsNotAvailable() {
        val (rec, c) = client(200, """{"protocol":1,"routes":["GET /installs","POST /audit/append"],"limits":{"page":100},"server_time":1}""")
        val ok = (c.capabilities(config) as MirrorResult.Ok).value
        assertEquals(1, ok.protocol); assertTrue(ok.has("GET /installs")); assertTrue(!ok.has("GET /pull"))
        assertEquals("https://factory-hub.example/owner/capabilities", rec.calls.single().url)
        assertEquals("owner-secret-value", rec.calls.single().bearer)
        assertEquals(MirrorResult.NotAvailable, client(404, """{"error":"not found"}""").second.capabilities(config))
        assertEquals(MirrorResult.AuthBlocked, client(401, "{}").second.capabilities(config))
        assertTrue(client(200, """{"protocol":1,"routes":[1]}""").second.capabilities(config) is MirrorResult.BadResponse)
        assertTrue(client(200, "[]").second.capabilities(config) is MirrorResult.BadResponse)
    }

    @Test fun installsComeBackAsIdsOnlyAndAHashMakesTheWholePageBad() {
        val body = """{"installs":[{"id":"${uuid(1)}"},{"id":"${uuid(2)}"}],"nextCursor":{"afterId":"${uuid(2)}"},"coverage":"registry"}"""
        val page = (client(200, body).second.installs(config, limit = 2) as MirrorResult.Ok).value
        assertEquals(listOf(uuid(1), uuid(2)), page.ids); assertEquals(uuid(2), page.nextAfterId); assertEquals("registry", page.coverage)
        val leak = """{"installs":[{"id":"${uuid(1)}","token_hash":"${"f".repeat(64)}"}],"nextCursor":null}"""
        assertEquals(MirrorResult.BadResponse("leak"), client(200, leak).second.installs(config))
        val unordered = """{"installs":[{"id":"${uuid(2)}"},{"id":"${uuid(1)}"}],"nextCursor":null}"""
        assertEquals(MirrorResult.BadResponse("order"), client(200, unordered).second.installs(config))
        val wrongCursor = """{"installs":[{"id":"${uuid(1)}"}],"nextCursor":{"afterId":"${uuid(9)}"}}"""
        assertEquals(MirrorResult.BadResponse("cursor"), client(200, wrongCursor).second.installs(config))
        val repeats = """{"installs":[{"id":"${uuid(1)}"}],"nextCursor":null}"""
        assertEquals(MirrorResult.BadResponse("order"), client(200, repeats).second.installs(config, afterId = uuid(1)))
        assertEquals(MirrorResult.Rejected("bad_cursor"), client(200, repeats).second.installs(config, afterId = "../x"))
        val (rec, c) = client(200, """{"installs":[],"nextCursor":null}""")
        c.installs(config, afterId = uuid(3), limit = 100000)
        assertEquals("https://factory-hub.example/installs?limit=100&afterId=${uuid(3)}", rec.calls.single().url)
    }

    private fun row(n: Int, status: String = "pending", extra: String = "") =
        """{"id":"${uuid(n)}","product":"al-store","kind":"trial","device":"7KD2M-QX9TP","status":"$status","reason":"","created_at":${1000 + n},"decided_at":null,"delivered_at":null,"owner_decision":"approved","owner_decided_at":1$extra}"""

    @Test fun historyIsParsedStrictlyAndNeverCarriesPrivateFields() {
        val body = """{"requests":[${row(1)},${row(2, "issued")}],"nextCursor":{"after":"1002:${uuid(2)}"},"coverage":"retained"}"""
        val page = (client(200, body).second.history(config) as MirrorResult.Ok).value
        assertEquals(listOf(uuid(1), uuid(2)), page.rows.map { it.id })
        assertEquals("approved", page.rows[0].ownerDecision); assertNull(page.rows[0].decidedAt)
        assertEquals("1002:${uuid(2)}", page.nextAfter)
        for (field in listOf("code", "poll_hash", "shop", "ref", "src", "machine")) {
            val leaked = """{"requests":[${row(1, extra = ""","$field":"x"""")}],"nextCursor":null}"""
            assertEquals(field, MirrorResult.BadResponse("leak"), client(200, leaked).second.history(config))
        }
        assertEquals(MirrorResult.BadResponse("status"), client(200, """{"requests":[${row(1, "weird")}],"nextCursor":null}""").second.history(config))
        assertEquals(MirrorResult.BadResponse("status"), client(200, """{"requests":[${row(1, "issued")}],"nextCursor":null}""").second.history(config, status = "pending"))
        assertEquals(MirrorResult.BadResponse("order"), client(200, """{"requests":[${row(2)},${row(1)}],"nextCursor":null}""").second.history(config))
        assertEquals(MirrorResult.BadResponse("cursor"), client(200, """{"requests":[${row(1)}],"nextCursor":{"after":"5:${uuid(1)}"}}""").second.history(config))
    }

    @Test fun historyArgumentsAreValidatedAndTheUrlIsBuiltFromThem() {
        val (rec, c) = client(200, """{"requests":[],"nextCursor":null}""")
        assertEquals(MirrorResult.Rejected("bad_status"), c.history(config, status = "all"))
        assertEquals(MirrorResult.Rejected("bad_cursor"), c.history(config, after = "abc"))
        assertTrue(rec.calls.isEmpty())
        c.history(config, status = "refused", after = "1002:${uuid(2)}", limit = 0)
        assertEquals("https://factory-hub.example/licence/history?limit=1&status=refused&after=1002:${uuid(2)}", rec.calls.single().url)
    }

    @Test fun theEventsCursorIsReadWithoutTouchingTheRows() {
        val c = OwnerReadClient { HubTransportResult.Failed("x") }
        assertEquals(151L, c.eventsCursor("""{"events":[],"nextCursor":{"beforeId":151}}"""))
        assertNull(c.eventsCursor("""{"events":[],"nextCursor":null}"""))
        assertNull(c.eventsCursor("not json"))
    }

    @Test fun noRequestLeavesWithoutTheAllowListAndTheMirrorStillWorks() {
        assertNull(com.pixelplus.owner.rejectedHubPath("GET", "https://factory-hub.example/installs?limit=1"))
        assertEquals("installs", com.pixelplus.owner.rejectedHubPath("POST", "https://factory-hub.example/installs"))
        assertEquals("forbidden", com.pixelplus.owner.rejectedHubPath("DELETE", "https://factory-hub.example/installs"))
        assertEquals("forbidden", com.pixelplus.owner.rejectedHubPath("POST", "https://factory-hub.example/licence/history"))
        assertEquals(MirrorResult.Rejected("no_token"), OwnerReadClient { HubTransportResult.Failed("x") }.capabilities(HubConfig("https://factory-hub.example", "", "p")))
    }
}
