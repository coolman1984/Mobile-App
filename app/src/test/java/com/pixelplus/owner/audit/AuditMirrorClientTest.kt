package com.pixelplus.owner.audit

import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubRequest
import com.pixelplus.owner.HubTransport
import com.pixelplus.owner.HubTransportResult
import com.pixelplus.owner.rejectedHubPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake-transport contract tests for the staged audit mirror client. No Android types, no network. */
class AuditMirrorClientTest {
    private val owner = "owner-secret-value"
    private val pull = "pull-secret-value"
    private val config = HubConfig("https://factory-hub.example", owner, pull)
    private val chainId = "22222222-2222-4222-8222-222222222222"

    private class Recording(private val handler: (HubRequest) -> HubTransportResult) : HubTransport {
        val calls = mutableListOf<HubRequest>()
        override fun exchange(request: HubRequest): HubTransportResult {
            calls += request
            return handler(request)
        }
    }

    private fun client(handler: (HubRequest) -> HubTransportResult) = Recording(handler).let { it to AuditMirrorClient(it) }

    private fun draft(sequence: Long, chain: String = chainId, over: (AuditDraft) -> AuditDraft = { it }): AuditDraft {
        val eventId = "11111111-1111-4111-8111-%012d".format(sequence)
        return over(
            AuditDraft(
                schemaVersion = 1, eventId = eventId, chainId = chain, sequence = sequence,
                deviceInstanceId = "33333333-3333-4333-8333-333333333333", actorType = "owner",
                workspaceId = "44444444-4444-4444-8444-444444444444", hubProfileId = null,
                occurredAtUtcMs = 1_700_000_000_000 + sequence, cairoLocalIso = "2023-11-15T00:13:20+02:00",
                cairoOffsetSeconds = 7200, zoneId = "Africa/Cairo", screen = "notebook", action = "create",
                entityType = "customer", entityId = "55555555-5555-4555-8555-555555555555",
                beforeSafe = emptyMap(), afterSafe = mapOf("revision" to JsonValue.Int64(sequence)), result = "success",
                reasonCode = null, explanationAr = "تم إنشاء العميل.", explanationEn = "Customer was created.",
                explanationTemplateVersion = 1, correlationId = eventId, originalEventId = null, operationId = null,
                canonicalVersion = 1,
            ),
        )
    }

    private fun links(count: Int, chain: String = chainId): List<ChainLink> {
        val out = mutableListOf<ChainLink>()
        var previous = AuditHash.GENESIS
        for (n in 1..count) {
            val link = AuditCanonical.link(draft(n.toLong(), chain), previous)
            previous = link.entryHash
            out += link
        }
        return out
    }

    private fun appended(link: ChainLink, replay: Boolean = false) =
        """{"eventId":"${AuditCanonical.draftOf(link.canonicalBytes).eventId}","sequence":${AuditCanonical.draftOf(link.canonicalBytes).sequence},""" +
            """"entryHash":"${link.entryHash}","receivedAt":1800000000,"replay":$replay,"server_time":1800000000}"""

    private fun page(chain: List<ChainLink>, before: Long? = null, limit: Int = 50): String {
        val head = chain.lastOrNull()
        val shown = chain.filter { before == null || AuditCanonical.draftOf(it.canonicalBytes).sequence < before }.reversed()
        val rows = shown.take(limit)
        val next = if (shown.size > limit) """{"beforeSequence":${AuditCanonical.draftOf(rows.last().canonicalBytes).sequence}}""" else "null"
        val entries = rows.joinToString(",") {
            val d = AuditCanonical.draftOf(it.canonicalBytes)
            """{"eventId":"${d.eventId}","sequence":${d.sequence},"entry":${JsonPrimitive(it.canonicalBytes.toString(Charsets.UTF_8))},""" +
                """"previousHash":"${it.previousHash}","entryHash":"${it.entryHash}","receivedAt":1800000000}"""
        }
        val headJson = if (head == null) """{"sequence":0,"entryHash":"${AuditHash.GENESIS}"}"""
        else """{"sequence":${AuditCanonical.draftOf(head.canonicalBytes).sequence},"entryHash":"${head.entryHash}"}"""
        return """{"chainId":"$chainId","head":$headJson,"entries":[$entries],"nextCursor":$next,"server_time":1800000000}"""
    }

    @Test fun appendSendsTheStoredBytesAndTheOwnerTokenOnly() {
        val link = links(1).single()
        val (rec, mirror) = client { HubTransportResult.Response(201, appended(link)) }
        val result = mirror.append(config, link)
        assertEquals(MirrorResult.Ok(Appended("11111111-1111-4111-8111-000000000001", 1, link.entryHash, false)), result)
        val call = rec.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://factory-hub.example/audit/append", call.url)
        assertEquals(owner, call.bearer)
        val body = Json.parseToJsonElement(call.jsonBody!!).jsonObject
        assertEquals(setOf("entry", "previousHash", "entryHash"), body.keys)
        assertEquals(link.canonicalBytes.toString(Charsets.UTF_8), body.getValue("entry").jsonPrimitive.content)
        assertEquals(link.previousHash, body.getValue("previousHash").jsonPrimitive.content)
        assertFalse(call.jsonBody!!.contains(pull))
        assertFalse(call.jsonBody!!.contains(owner))
    }

    @Test fun theSharedGoldenEntryIsSentExactly() {
        val fixture = Json.parseToJsonElement(
            javaClass.classLoader!!.getResourceAsStream("audit/golden-v1.json")!!.bufferedReader().use { it.readText() },
        ).jsonObject
        val canonical = fixture.getValue("canonical").jsonPrimitive.content
        val link = ChainLink(AuditHash.GENESIS, fixture.getValue("entryHash").jsonPrimitive.content, canonical.toByteArray(Charsets.UTF_8))
        val (rec, mirror) = client { r ->
            val d = AuditCanonical.draftOf(link.canonicalBytes)
            HubTransportResult.Response(201, """{"eventId":"${d.eventId}","sequence":1,"entryHash":"${link.entryHash}","replay":false}""")
        }
        assertTrue(mirror.append(config, link) is MirrorResult.Ok)
        assertEquals(canonical, Json.parseToJsonElement(rec.calls.single().jsonBody!!).jsonObject.getValue("entry").jsonPrimitive.content)
    }

    @Test fun aReplayIsOkOnlyWhenTheHubSays200AndEchoesTheSameEntry() {
        val link = links(1).single()
        val (_, mirror) = client { HubTransportResult.Response(200, appended(link, replay = true)) }
        assertEquals(true, (mirror.append(config, link) as MirrorResult.Ok).value.replay)
        // a 200 that claims it is new, or a 201 that claims replay, is not trusted
        assertTrue(client { HubTransportResult.Response(200, appended(link, replay = false)) }.second.append(config, link) is MirrorResult.BadResponse)
        assertTrue(client { HubTransportResult.Response(201, appended(link, replay = true)) }.second.append(config, link) is MirrorResult.BadResponse)
    }

    @Test fun anEchoThatDiffersIsNeverConfirmed() {
        val (a, b) = links(2)
        assertEquals(MirrorResult.BadResponse("echo"), client { HubTransportResult.Response(201, appended(b)) }.second.append(config, a))
        val wrongHash = appended(a).replace(a.entryHash, "ab".repeat(32))
        assertEquals(MirrorResult.BadResponse("echo"), client { HubTransportResult.Response(201, wrongHash) }.second.append(config, a))
        for (body in listOf("", "[]", "{}", """{"eventId":1}""", "not json", appended(a).replace(""""replay":false""", """"replay":"false""""))) {
            assertTrue(body, client { HubTransportResult.Response(201, body) }.second.append(config, a) is MirrorResult.BadResponse)
        }
    }

    @Test fun everyStatusMapsToOneOutcome() {
        val link = links(1).single()
        fun outcome(code: Int, body: String = """{"error":"x"}""") = client { HubTransportResult.Response(code, body) }.second.append(config, link)
        assertEquals(MirrorResult.AuthBlocked, outcome(401))
        assertEquals(MirrorResult.AuthBlocked, outcome(403))
        assertEquals(MirrorResult.NotAvailable, outcome(404, """{"error":"not found"}"""))
        assertEquals(MirrorResult.NotAvailable, outcome(405))
        assertEquals(MirrorResult.Retry("x"), outcome(429))
        assertEquals(MirrorResult.Retry("x"), outcome(500))
        assertEquals(MirrorResult.Retry("x"), outcome(503))
        assertEquals(MirrorResult.Invalid("x"), outcome(400))
        assertEquals(MirrorResult.Invalid("x"), outcome(413))
        assertEquals(MirrorResult.Invalid("full"), outcome(507))
        assertEquals(MirrorResult.Retry("io"), client { HubTransportResult.Failed("io") }.second.append(config, link))
        assertEquals(MirrorResult.BadResponse("status"), outcome(204, ""))
    }

    @Test fun aConflictCarriesTheHubHeadOnlyWhenItIsWellFormed() {
        val link = links(1).single()
        val head = """{"error":"conflict","head":{"sequence":3,"entryHash":"${"ab".repeat(32)}"}}"""
        assertEquals(
            MirrorResult.Conflict(ChainHead(3, "ab".repeat(32))),
            client { HubTransportResult.Response(409, head) }.second.append(config, link),
        )
        assertEquals(MirrorResult.Conflict(null), client { HubTransportResult.Response(409, """{"error":"conflict"}""") }.second.append(config, link))
        val zero = """{"head":{"sequence":0,"entryHash":"${AuditHash.GENESIS}"}}"""
        assertEquals(MirrorResult.Conflict(ChainHead(0, AuditHash.GENESIS)), client { HubTransportResult.Response(409, zero) }.second.append(config, link))
        val lie = """{"head":{"sequence":0,"entryHash":"${"ab".repeat(32)}"}}"""
        assertEquals(MirrorResult.Conflict(null), client { HubTransportResult.Response(409, lie) }.second.append(config, link))
    }

    @Test fun anEntryThatCouldNeverBeAcceptedIsRefusedBeforeAnyRequest() {
        val link = links(1).single()
        val (rec, mirror) = client { HubTransportResult.Response(201, "") }
        assertEquals(MirrorResult.Rejected("no_token"), mirror.append(HubConfig(config.baseUrl, "", pull), link))
        assertEquals(MirrorResult.Rejected("not_https"), mirror.append(HubConfig("http://factory-hub.example", owner, pull), link))
        assertEquals(MirrorResult.Rejected("hash_mismatch"), mirror.append(config, link.copy(entryHash = "ab".repeat(32))))
        assertEquals(MirrorResult.Rejected("bad_hash"), mirror.append(config, link.copy(entryHash = "AB".repeat(32))))
        assertEquals(MirrorResult.Rejected("too_large"), mirror.append(config, link.copy(canonicalBytes = ByteArray(AuditHash.MAX_CANONICAL_BYTES + 1))))
        val junk = "[1,2]".toByteArray()
        assertEquals(MirrorResult.Rejected("bad_entry"), mirror.append(config, ChainLink(AuditHash.GENESIS, AuditHash.of(AuditHash.GENESIS, junk), junk)))
        assertTrue(rec.calls.isEmpty())
    }

    @Test fun onlyTheTwoAuditRoutesAreAddedToTheAllowList() {
        val base = "https://factory-hub.example"
        assertNull(rejectedHubPath("POST", "$base/audit/append"))
        assertNull(rejectedHubPath("GET", "$base/audit/recent?chainId=$chainId&limit=50"))
        assertEquals("forbidden", rejectedHubPath("GET", "$base/audit/append"))
        assertEquals("forbidden", rejectedHubPath("POST", "$base/audit/recent"))
        assertEquals("forbidden", rejectedHubPath("DELETE", "$base/audit/recent"))
        assertEquals("forbidden", rejectedHubPath("POST", "$base/audit/other"))
        assertEquals("ack", rejectedHubPath("POST", "$base/audit/ack"))
        assertEquals("installs", rejectedHubPath("POST", "$base/installs"))
    }

    @Test fun recentReadsAndVerifiesAPage() {
        val chain = links(5)
        val (rec, mirror) = client { HubTransportResult.Response(200, page(chain, limit = 2)) }
        val result = mirror.recent(config, chainId, limit = 2) as MirrorResult.Ok
        assertEquals(listOf(5L, 4L), result.value.entries.map { it.sequence })
        assertEquals(ChainHead(5, chain.last().entryHash), result.value.head)
        assertEquals(4L, result.value.nextBeforeSequence)
        assertEquals("GET", rec.calls.single().method)
        assertEquals("https://factory-hub.example/audit/recent?chainId=$chainId&limit=2", rec.calls.single().url)
        assertEquals(owner, rec.calls.single().bearer)
        assertNull(rec.calls.single().jsonBody)
        // the next page ends at the genesis entry and has no cursor
        val last = client { HubTransportResult.Response(200, page(chain, before = 3, limit = 50)) }.second.recent(config, chainId, 3) as MirrorResult.Ok
        assertEquals(listOf(2L, 1L), last.value.entries.map { it.sequence })
        assertNull(last.value.nextBeforeSequence)
    }

    @Test fun anEmptyChainIsAnEmptyPageWithTheGenesisHead() {
        val result = client { HubTransportResult.Response(200, page(emptyList())) }.second.recent(config, chainId) as MirrorResult.Ok
        assertEquals(ChainHead(0, AuditHash.GENESIS), result.value.head)
        assertTrue(result.value.entries.isEmpty())
    }

    @Test fun aPageTheHubTamperedWithIsNotReturned() {
        val chain = links(4)
        fun bad(before: Long? = null, mutate: (String) -> String) =
            client { HubTransportResult.Response(200, mutate(page(chain, before))) }.second.recent(config, chainId, before)
        // a changed byte inside an entry, a swapped hash, a missing middle row, a reordered pair, a wrong chain, a lying head
        assertTrue(bad { it.replace("تم إنشاء العميل.", "تم حذف العميل.") } is MirrorResult.BadResponse)
        assertTrue(bad { it.replace(chain[1].entryHash, "ab".repeat(32)) } is MirrorResult.BadResponse)
        val without = page(chain).let { text ->
            val root = Json.parseToJsonElement(text).jsonObject
            val rows = root.getValue("entries") as KJsonArray
            val kept = KJsonArray(rows.filterIndexed { i, _ -> i != 1 })
            kotlinx.serialization.json.JsonObject(root + ("entries" to kept)).toString()
        }
        assertEquals(MirrorResult.BadResponse("order"), client { HubTransportResult.Response(200, without) }.second.recent(config, chainId))
        val reordered = page(chain).let { text ->
            val root = Json.parseToJsonElement(text).jsonObject
            val rows = (root.getValue("entries") as KJsonArray).reversed()
            kotlinx.serialization.json.JsonObject(root + ("entries" to KJsonArray(rows))).toString()
        }
        assertEquals(MirrorResult.BadResponse("order"), client { HubTransportResult.Response(200, reordered) }.second.recent(config, chainId))
        assertEquals(MirrorResult.BadResponse("chain"), client { HubTransportResult.Response(200, page(chain).replace(chainId, "99999999-9999-4999-8999-999999999999")) }.second.recent(config, chainId))
        val liedHead = page(chain).replace(""""head":{"sequence":4""", """"head":{"sequence":9""")
        assertEquals(MirrorResult.BadResponse("head"), client { HubTransportResult.Response(200, liedHead) }.second.recent(config, chainId))
        assertEquals(MirrorResult.BadResponse("shape"), client { HubTransportResult.Response(200, "[]") }.second.recent(config, chainId))
    }

    @Test fun aPageThatBreaksTheChainOrHidesOlderRowsIsNotReturned() {
        val chain = links(3)
        // a row whose predecessor hash does not match the older row it sits on
        val forged = AuditCanonical.link(draft(3), "ab".repeat(32))
        val forkedChain = listOf(chain[0], chain[1], forged)
        assertEquals(MirrorResult.BadResponse("link"), client { HubTransportResult.Response(200, page(forkedChain)) }.second.recent(config, chainId))
        // the hub claims "nothing older" while the oldest row it sent is not the genesis entry
        val noCursor = page(chain.drop(1))
        assertEquals(MirrorResult.BadResponse("coverage"), client { HubTransportResult.Response(200, noCursor) }.second.recent(config, chainId))
        // a cursor on a page that already reached the genesis entry
        val cursor = page(chain).replace(""""nextCursor":null""", """"nextCursor":{"beforeSequence":1}""")
        assertEquals(MirrorResult.BadResponse("cursor"), client { HubTransportResult.Response(200, cursor) }.second.recent(config, chainId))
        // more rows than were asked for
        assertEquals(MirrorResult.BadResponse("limit"), client { HubTransportResult.Response(200, page(chain)) }.second.recent(config, chainId, limit = 2))
    }

    @Test fun recentRefusesBadArgumentsAndTreatsAnOldWorkerAsNotAvailable() {
        val (rec, mirror) = client { HubTransportResult.Response(404, """{"error":"not found"}""") }
        assertEquals(MirrorResult.Rejected("bad_chain"), mirror.recent(config, "../etc"))
        assertEquals(MirrorResult.Rejected("bad_cursor"), mirror.recent(config, chainId, 0))
        assertTrue(rec.calls.isEmpty())
        assertEquals(MirrorResult.NotAvailable, mirror.recent(config, chainId))
        assertEquals(MirrorResult.AuthBlocked, client { HubTransportResult.Response(401, "{}") }.second.recent(config, chainId))
        assertTrue(rec.calls.single().url.contains("limit=50"))
        client { HubTransportResult.Response(200, "{}") }.let { (r, m) -> m.recent(config, chainId, limit = 100000); assertTrue(r.calls.single().url.endsWith("limit=100")) }
    }

    @Test fun theAnchorComparisonNamesEveryRelation() {
        val chain = links(4)
        val hashAt = { n: Long -> chain.getOrNull((n - 1).toInt())?.entryHash }
        val local = ChainHead(4, chain[3].entryHash)
        assertEquals(AnchorState.IN_SYNC, AuditAnchor.compare(local, ChainHead(4, chain[3].entryHash), hashAt))
        assertEquals(AnchorState.HUB_BEHIND, AuditAnchor.compare(local, ChainHead(2, chain[1].entryHash), hashAt))
        assertEquals(AnchorState.HUB_BEHIND, AuditAnchor.compare(local, ChainHead(0, AuditHash.GENESIS), hashAt))
        assertEquals(AnchorState.HUB_AHEAD, AuditAnchor.compare(local, ChainHead(6, "cd".repeat(32)), hashAt))
        assertEquals(AnchorState.MISMATCH, AuditAnchor.compare(local, ChainHead(4, "cd".repeat(32)), hashAt))
        assertEquals(AnchorState.MISMATCH, AuditAnchor.compare(local, ChainHead(2, "cd".repeat(32)), hashAt))
        assertNotNull(chain.first())
    }
}
