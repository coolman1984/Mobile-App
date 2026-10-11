package com.pixelplus.owner.sync

import com.pixelplus.owner.*
import org.junit.Assert.*
import org.junit.Test

class OutboxMachineTest {
    private fun state(status: OutboxStatus = OutboxStatus.QUEUED) = OutboxState(status, 0, 100, null, null, 100_000, null, 1, 1)
    @Test fun claimAndCancelRespectLeaseAndIntentExpiry() {
        assertTrue(OutboxMachine.claim(state(), 99) is MachineMove.No)
        val held = (OutboxMachine.claim(state(), 100) as MachineMove.Ok).state
        assertEquals(1, held.attempts)
        assertTrue(OutboxMachine.claim(held, 101) is MachineMove.No)
        assertTrue(OutboxMachine.cancel(held, 101) is MachineMove.No)
        assertEquals(OutboxStatus.CANCELLED, (OutboxMachine.cancel(state(), 100) as MachineMove.Ok).state.status)
        assertEquals(OutboxStatus.NEEDS_REVIEW, (OutboxMachine.claim(state(), 100_000) as MachineMove.Ok).state.status)
    }
    @Test fun staleApprovalAndChangedSnapshotFailClosed() {
        val expected = stateHash("pending", null, 100)
        assertEquals(Freshness.Send, judgeDecision("approve", FreshSnapshot("pending", null, 100), expected, 101))
        assertEquals("stale", (judgeDecision("approve", FreshSnapshot("pending", null, 100), expected, 100 + OutboxRules.APPROVAL_WINDOW_SEC + 1) as Freshness.Stop).reason)
        assertEquals("stale_snapshot", (judgeDecision("refuse", FreshSnapshot("pending", "approved", 100), expected, 101) as Freshness.Stop).reason)
        assertEquals("expired", (judgeDecision("approve", FreshSnapshot("pending", "expired", 100), expected, 101) as Freshness.Stop).reason)
        assertEquals("absent", (judgeDecision("approve", null, expected, 101) as Freshness.Stop).reason)
    }
    @Test fun profileEpochAndCredentialChangesPauseDispatch() {
        assertNull(profileGate(1,1,1,1,false))
        assertEquals("origin_changed", profileGate(1,1,2,1,false))
        assertEquals("credential_changed", profileGate(1,1,1,2,false))
        assertEquals("paused", profileGate(1,1,1,1,true))
    }
    @Test fun retryDelayIsBoundedAndJitterIsClamped() {
        assertEquals(15_000L, backoffMs(1,-1))
        assertEquals(35_000L, backoffMs(2,100_000))
        assertEquals(OutboxRules.BACKOFF_CAP_MS + OutboxRules.JITTER_MAX_MS, backoffMs(Int.MAX_VALUE,Long.MAX_VALUE))
    }
    @Test fun strictStoredBodiesRejectExtraFieldsAndEscapedSigningFields() {
        val id = "00000000-0000-4000-8000-000000000001"
        assertEquals("approve", actionOfStoredBody(decideBody(id,OwnerAction.APPROVE)!!))
        assertEquals("refuse", actionOfStoredBody(decideBody(id,OwnerAction.REFUSE)!!))
        assertNull(actionOfStoredBody("""{"id":"$id","action":"approve","code":"x"}"""))
        assertNull(actionOfStoredBody("""{"id":"$id","action":"approve","co\u0064e":"x"}"""))
        assertNull(actionOfStoredBody("""{"id":"$id","action":"issue"}"""))
    }
    @Test fun phoneTransportAllowlistRejectsDestructiveAndShopRoutes() {
        for (path in listOf("/ack", "/licence/ack", "/installs", "/licence/request", "/licence/status", "/unknown")) {
            assertNotNull(rejectedHubPath("POST", "https://hub.example$path"))
        }
        assertNotNull(rejectedHubPath("POST", "https://hub.example/licence/%61ck"))
        assertNull(rejectedHubPath("POST", "https://hub.example/licence/states"))
        assertNull(rejectedHubPath("POST", "https://hub.example/licence/decide"))
        val requests = mutableListOf<HubRequest>()
        val client = HubClient(HubTransport { request -> requests.add(request); HubTransportResult.Response(200, "{\"states\":{}}") })
        client.states(HubConfig("https://hub.example", "synthetic-owner", "synthetic-pull"), listOf("00000000-0000-4000-8000-000000000001"))
        assertEquals("synthetic-owner", requests.single().bearer)
    }

    @Test fun pendingAllExhaustsPagesAndRefusesRepeatedPage() {
        val rows = (1..100).map { i -> """{"id":"00000000-0000-4000-8000-${i.toString().padStart(12,'0')}","product":"store","kind":"trial","device":"ABCDE12345","created_at":100}""" }
        val calls = mutableListOf<HubRequest>()
        val client = HubClient(HubTransport { r -> calls.add(r); HubTransportResult.Response(200, if (calls.size == 1) "{\"requests\":[${rows.joinToString(",") }]}" else "{\"requests\":[]}") })
        val config = HubConfig("https://hub.example","synthetic", "")
        assertEquals(100, (client.pendingAll(config) as HubResult.Ok).value.size)
        assertTrue(calls[1].url.contains("after=100:00000000-0000-4000-8000-000000000100"))
        val repeated = HubClient(HubTransport { HubTransportResult.Response(200,"{\"requests\":[${rows.joinToString(",")}]}" ) })
        assertTrue(repeated.pendingAll(config) is HubResult.BadResponse)
    }
    @Test fun noSuccessResponseCanStandInForReconciliation() {
        assertEquals(SendHint.Reconcile, hintForDecide(HubResult.Ok(DecideAck("pending","approved",false)),"approve"))
        assertTrue(hintForDecide(HubResult.BadResponse("shape"),"approve") is SendHint.Review)
        assertTrue(hintForDecide(HubResult.Network("io"),"approve") is SendHint.Ambiguous)
        assertTrue(hintForDecide(HubResult.Http(503,"http"),"approve") is SendHint.Ambiguous)
        assertTrue(hintForDecide(HubResult.Http(403,"forbidden"),"approve") is SendHint.Auth)
    }
    @Test fun queueCopyIsBilingualAndNeverDescribesIssuedCode() {
        val copy = SyncCopy.queuedDisplay("approve")
        assertTrue(copy.contains("قائمة الانتظار"))
        assertTrue(copy.contains("Not confirmed"))
        assertFalse(copy.contains("issued"))
        assertEquals("", SyncCopy.queueLine(0,null))
        val cached = SyncCopy.cachedSnapshot(0)
        assertTrue(cached.arabic.contains("القاهرة"))
        assertTrue(cached.english.contains("Cairo"))
        assertTrue(cached.display.contains("1970-01-01T02:00:00+02:00"))
    }
}
