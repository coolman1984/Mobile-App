package com.pixelplus.owner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubLogicTest {
    private val id = "11111111-2222-4333-8444-555555555555"
    private val secret = "owner-secret-value"
    private val pullSecret = "pull-secret-value"
    private val live = HubConfig("https://factory-hub.example", secret, pullSecret)

    @Test fun hubUrlMustBeHttpsOrigin() {
        assertEquals("https://factory-hub.mflma2040.workers.dev", okUrl("https://factory-hub.mflma2040.workers.dev"))
        assertEquals("https://factory-hub.mflma2040.workers.dev", okUrl("https://factory-hub.mflma2040.workers.dev/"))
        assertEquals("https://factory.example", okUrl("HTTPS://Factory.Example"))
        assertEquals("not_https", badUrl("http://factory-hub.example"))
        assertEquals("userinfo", badUrl("https://user:pass@factory-hub.example"))
        assertEquals("path", badUrl("https://factory-hub.example/licence"))
        assertEquals("extra", badUrl("https://factory-hub.example?x=1"))
        assertEquals("empty", badUrl("  "))
        assertEquals("bad_host", badUrl("https://"))
    }

    @Test fun hostShownToTheOwnerNeverIncludesTheToken() {
        assertEquals("factory-hub.example", hostOnly(live.baseUrl))
        assertFalse(hostOnly(live.baseUrl).contains(secret))
    }

    @Test fun decideBodyMatchesTheRelayContractAndNeverIssues() {
        val approve = Json.parseToJsonElement(decideBody(id, OwnerAction.APPROVE)!!).jsonObject
        assertEquals(setOf("id", "action"), approve.keys)
        assertEquals(id, approve.getValue("id").jsonPrimitive.content)
        assertEquals("approve", approve.getValue("action").jsonPrimitive.content)
        assertNull(approve["code"])
        val refuse = Json.parseToJsonElement(decideBody(id.uppercase(), OwnerAction.REFUSE)!!).jsonObject
        assertEquals(setOf("id", "action", "reason"), refuse.keys)
        assertEquals("refuse", refuse.getValue("action").jsonPrimitive.content)
        assertEquals("owner_refused", refuse.getValue("reason").jsonPrimitive.content)
        assertEquals(id, refuse.getValue("id").jsonPrimitive.content)
        assertNull(decideBody("REQ-101", OwnerAction.APPROVE))
        assertFalse(decideBody(id, OwnerAction.APPROVE)!!.contains("issue"))
    }

    @Test fun pendingAndEventsParseTheRelayShape() {
        val pending = parsePending(
            """{"requests":[{"id":"$id","product":"al-store","kind":"trial","device":"7KD2M-QX9TP",
                "machine":null,"shop":"متجر النور","ref":"","version":"1.6","src":"abcd",
                "created_at":1800000000,"owner_decision":null,"owner_decided_at":null}],"server_time":1800000000}"""
        ) as Parse.Ok<List<LicenceRequest>>
        val row = pending.value.single()
        assertEquals(id, row.id)
        assertEquals("متجر النور", row.shop)
        assertEquals("trial", row.kind)
        assertNull(row.ownerDecision)
        assertEquals("1970-01-01 00:00 UTC", formatEpoch(0))
        val events = parseEvents(
            """{"events":[{"id":7,"at":1800000001,"request_id":null,"event":"created","detail":"trial"}]}"""
        ) as Parse.Ok<List<LicenceEvent>>
        assertEquals("7", events.value.single().id)
        assertNull(events.value.single().requestId)
        assertEquals("طلب جديد", eventTitle(events.value.single().event))
        assertTrue(parsePending("{") is Parse.Bad)
        assertTrue(parsePending("""{"error":"unauthorised"}""") is Parse.Bad)
    }

    @Test fun pullSummaryDropsBatchBodiesAndTokenHashes() {
        val body = "SUPER_SECRET_BATCH"
        val hash = "a".repeat(64)
        val parsed = parsePull(
            """{"batches":[
                {"id":"b1","install_id":"i","known":1,"token_hash":"$hash","sent_at":null,"received_at":5,"size":12,"body":"$body"},
                {"id":"b2","install_id":"j","known":0,"token_hash":"$hash","sent_at":1,"received_at":6,"size":3,"body":"$body"}
            ]}"""
        ) as Parse.Ok<PullSummary>
        val summary = parsed.value
        assertEquals(PullSummary(shown = 2, known = 1, unregistered = 1, bytes = 15), summary)
        assertFalse(summary.toString().contains(body))
        assertFalse(summary.toString().contains(hash))
        assertTrue(pullSummaryText(summary).contains("لم يُحذف شيء"))
    }

    @Test fun firstSightIsSilentAndOnlyLaterIdsNotify() {
        val first = noticeNew(SeenMarks.EMPTY, listOf("a"), listOf("1"))
        assertTrue(first.newRequestIds.isEmpty())
        assertTrue(first.newEventIds.isEmpty())
        assertTrue(first.next.requestsReady)
        val second = noticeNew(first.next, listOf("a", "b"), listOf("1"))
        assertEquals(listOf("b"), second.newRequestIds)
        assertTrue(second.newEventIds.isEmpty())
        val again = noticeNew(second.next, listOf("a", "b"), listOf("1"))
        assertTrue(again.newRequestIds.isEmpty())
        val failed = noticeNew(second.next, null, null)
        assertEquals(second.next.requestIds, failed.next.requestIds)
        assertTrue(failed.newRequestIds.isEmpty())
        assertNull(notificationText(first))
        assertEquals("طلبات جديدة: 1 / New requests: 1", notificationText(second))
        assertFalse(notificationText(second)!!.contains(secret))
    }

    @Test fun rememberedIdsStayBounded() {
        val seen = SeenMarks(true, true, listOf("a", "b", "c"), emptyList())
        val next = noticeNew(seen, listOf("d"), null, cap = 2)
        assertEquals(listOf("d"), next.newRequestIds)
        assertEquals(listOf("d", "a"), next.next.requestIds)
        assertEquals(listOf("d", "a"), decodeIds(encodeIds(next.next.requestIds)))
    }

    @Test fun saveKeepsAStoredTokenWhenTheFieldIsLeftBlank() {
        val saved = HubConfig("https://old.example", secret, pullSecret)
        val kept = planSave(saved, "https://factory-hub.example/", "  ", "", clearPull = false) as SavePlan.Accept
        assertEquals("https://factory-hub.example", kept.config.baseUrl)
        assertEquals(secret, kept.config.ownerToken)
        assertEquals(pullSecret, kept.config.pullToken)
        val cleared = planSave(saved, "https://factory-hub.example", "", "", clearPull = true) as SavePlan.Accept
        assertEquals("", cleared.config.pullToken)
        assertEquals(secret, cleared.config.ownerToken)
        assertEquals("not_https", (planSave(saved, "http://factory-hub.example", "", "", false) as SavePlan.Reject).reason)
        assertEquals("empty_token", (planSave(HubConfig("", "", ""), "https://factory-hub.example", " ", "", false) as SavePlan.Reject).reason)
        assertEquals("bad_token", (planSave(saved, "https://factory-hub.example", "bad token", "", false) as SavePlan.Reject).reason)
    }

    @Test fun demoStaysUntilTheHubIsConfiguredAndLiveDataDoesNotMix() {
        val demo = DemoSeed.state().chooseCompany("demo-2")
        val demoView = presentRequests(HubConfig("", "", ""), demo, loading = false, errorCode = null, live = null)
        assertTrue(demoView.demo)
        assertEquals(listOf("REQ-103"), demoView.cards.map { it.id })
        assertTrue(demoView.cards.all { it.demo && !it.canApprove })
        val approved = LicenceRequest(id, "al-store", "monthly", "7KD2M-QX9TP", "متجر", "1", 1_800_000_000, "approved")
        val waiting = approved.copy(id = "22222222-2222-4333-8444-555555555555", ownerDecision = null, kind = "trial")
        val liveView = presentRequests(live, demo, loading = false, errorCode = null, live = listOf(approved, waiting))
        assertFalse(liveView.demo)
        assertFalse(liveView.cards.any { it.id.startsWith("REQ-") })
        assertFalse(liveView.cards.first { it.id == id }.canApprove)
        assertTrue(liveView.cards.first { it.id == id }.canReject)
        assertTrue(liveView.cards.first { it.id == waiting.id }.canApprove)
        val failed = presentRequests(live, demo, loading = false, errorCode = "unauthorised", live = null)
        assertFalse(failed.demo)
        assertTrue(failed.cards.isEmpty())
        assertEquals("unauthorised", failed.errorCode)
        assertFalse(arabicFailure(failed.errorCode).contains(secret))
        val loading = presentRequests(live, demo, loading = true, errorCode = null, live = null)
        assertTrue(loading.loading)
        assertTrue(loading.cards.isEmpty())
    }

    @Test fun alertsShowWhatTheHubReturnsAndDoNotPretendToDeleteIt() {
        val demo = presentAlerts(HubConfig("", "", ""), DemoSeed.state(), false, null, null, null, null)
        assertTrue(demo.demo)
        assertEquals(3, demo.cards.size)
        assertNull(demo.pullNote)
        val events = listOf(LicenceEvent("4", 0, id, "approved", "admin"))
        val noPull = presentAlerts(live.copy(pullToken = ""), DemoSeed.state(), false, null, events, null, null)
        assertFalse(noPull.demo)
        assertEquals("موافقة مسجّلة", noPull.cards.single().title)
        assertTrue(noPull.pullNote!!.contains("رمز سحب القياسات"))
        assertNull(noPull.pullSummary)
        val withPull = presentAlerts(live, DemoSeed.state(), false, null, events, PullSummary(1, 1, 0, 8), null)
        assertTrue(withPull.pullSummary!!.contains("لم يُحذف شيء"))
        assertNull(withPull.pullNote)
        val companies = presentCompanies(live, DemoSeed.state(), events.let {
            listOf(LicenceRequest(id, "al-store", "trial", "7KD2M-QX9TP", "", "", 0, null))
        })
        assertFalse(companies.demo)
        assertEquals(listOf("al-store"), companies.names)
        assertFalse(companies.names.contains("شركة تجريبية للأدوات"))
    }

    @Test fun clientUsesTheRightTokenAndNeverAcksOrIssues() {
        val transport = RecordingTransport { request ->
            when {
                request.url.endsWith("/licence/pending?limit=1") && request.bearer == secret ->
                    HubTransportResult.Response(200, """{"requests":[]}""")
                request.url.contains("/licence/pending") && request.bearer == "nope" ->
                    HubTransportResult.Response(401, """{"error":"unauthorised"}""")
                request.url.contains("/pull?") && request.bearer == pullSecret ->
                    HubTransportResult.Response(200, """{"batches":[]}""")
                request.url.endsWith("/licence/decide") && request.bearer == secret ->
                    HubTransportResult.Response(200, """{"status":"pending","owner_decision":"approved"}""")
                else -> HubTransportResult.Failed("unexpected")
            }
        }
        val client = HubClient(transport)
        assertTrue(client.pending(live, limit = 1) is HubResult.Ok)
        val refused = client.pending(live.copy(ownerToken = "nope"))
        assertTrue(refused is HubResult.Http)
        assertEquals("unauthorised", (refused as HubResult.Http).error)
        assertTrue(client.pull(live.copy(pullToken = "")) is HubResult.Rejected)
        assertTrue(client.pull(live) is HubResult.Ok)
        val decided = client.decide(live, id, OwnerAction.APPROVE) as HubResult.Ok<DecideAck>
        assertEquals("approved", decided.value.ownerDecision)
        assertTrue(client.decide(live, "REQ-101", OwnerAction.APPROVE) is HubResult.Rejected)
        assertTrue(client.pending(HubConfig("http://factory-hub.example", secret, "")) is HubResult.Rejected)
        assertTrue(transport.calls.none { it.url.contains("/ack") })
        assertTrue(transport.calls.none { it.url.contains(secret) || it.url.contains(pullSecret) })
        assertTrue(transport.calls.none { it.jsonBody?.contains("\"issue\"") == true || it.jsonBody?.contains("\"code\"") == true })
        val pullCall = transport.calls.single { it.url.contains("/pull?") }
        assertEquals("GET", pullCall.method)
        assertEquals(pullSecret, pullCall.bearer)
        val decideCall = transport.calls.single { it.url.endsWith("/licence/decide") }
        assertEquals("POST", decideCall.method)
        assertEquals(secret, decideCall.bearer)
        assertEquals("approve", Json.parseToJsonElement(decideCall.jsonBody!!).jsonObject.getValue("action").jsonPrimitive.content)
    }

    private fun okUrl(raw: String) = (normalizeHubUrl(raw) as UrlResult.Ok).url
    private fun badUrl(raw: String) = (normalizeHubUrl(raw) as UrlResult.Reject).reason

    private class RecordingTransport(
        private val handler: (HubRequest) -> HubTransportResult
    ) : HubTransport {
        val calls = mutableListOf<HubRequest>()
        override fun exchange(request: HubRequest): HubTransportResult {
            calls.add(request)
            return handler(request)
        }
    }
}
