package com.pixelplus.owner.sync

import android.database.sqlite.SQLiteDatabase
import com.pixelplus.owner.*
import com.pixelplus.owner.audit.AesGcmAuditSealer
import com.pixelplus.owner.audit.AuditRuntime
import com.pixelplus.owner.audit.RandomIdSource
import com.pixelplus.owner.audit.PlainAuditSealer
import com.pixelplus.owner.data.db.OwnerDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SyncTest {
    private val context get() = RuntimeEnvironment.getApplication() as android.content.Context
    private val name = "m04-${UUID.randomUUID()}.db"
    private val runtime = AuditRuntime(RandomIdSource, AesGcmAuditSealer.ephemeral())
    private lateinit var db: OwnerDatabase
    private lateinit var store: SyncStore
    private lateinit var profile: BoundProfile
    private val config = HubConfig("https://hub.example", "synthetic-owner", "")
    private val id = "00000000-0000-4000-8000-000000000001"
    private val now = 1_800_000_000_000L
    private val request get() = LicenceRequest(id, "store", "trial", "ABCDE12345", "Synthetic shop", "1", now / 1000, null)

    @Before fun open() {
        reopen()
        profile = store.ensureProfile(config.baseUrl, now)!!
    }
    private fun reopen() {
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true, runtime = runtime)
        store = SyncStore(db)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun queue(action: OwnerAction = OwnerAction.APPROVE): String =
        (store.enqueue(profile, request, action, now) as QueueOutcome.Queued).operationId
    private fun row(operation: String) = db.syncDao().outbox(operation)!!
    private fun sync(hub: FakeHub) = HubSync(store, HubClient(hub))

    private inner class FakeHub : HubTransport {
        var status = "pending"
        var decision: String? = null
        var effective: String? = null
        var postCode = 200
        var lostResponse = false
        var applyPost = true
        var statesCode = 200
        var malformed = false
        var disappear = false
        var readOffline = false
        val posts = mutableListOf<HubRequest>()
        val calls = mutableListOf<HubRequest>()
        override fun exchange(request: HubRequest): HubTransportResult {
            calls.add(request)
            val path = java.net.URI(request.url).path
            if (readOffline && path != "/licence/decide") return HubTransportResult.Failed("io")
            val body = when (path) {
                "/licence/events" -> "{\"events\":[]}"
                "/licence/pending" -> if (status != "pending" || disappear) "{\"requests\":[]}" else {
                    """{"requests":[{"id":"$id","product":"store","kind":"trial","device":"ABCDE12345","created_at":${now / 1000},"owner_decision":${(effective ?: decision)?.let { "\"$it\"" } ?: "null"}}]}"""
                }
                "/licence/states" -> {
                    if (statesCode != 200) return HubTransportResult.Response(statesCode, "{\"error\":\"unauthorised\"}")
                    if (disappear) "{\"states\":{}}" else """{"states":{"$id":{"status":"$status","owner_decision":${decision?.let { "\"$it\"" } ?: "null"}}}}"""
                }
                "/licence/decide" -> {
                    posts.add(request)
                    if (applyPost) {
                        if (request.jsonBody!!.contains("approve")) decision = "approved" else status = "refused"
                    }
                    if (lostResponse) return HubTransportResult.Failed("io")
                    if (postCode != 200) return HubTransportResult.Response(postCode, "{\"error\":\"http\"}")
                    if (malformed) return HubTransportResult.Response(200, "{}")
                    """{"status":"$status","owner_decision":${decision?.let { "\"$it\"" } ?: "null"}}"""
                }
                else -> error("Unexpected route: $path")
            }
            return HubTransportResult.Response(200, body)
        }
    }

    @Test fun confirmationIsAtomicEncryptedIntentAndSurvivesReopen() {
        val operation = queue()
        val queued = row(operation)
        assertEquals("QUEUED", queued.status)
        assertNull(queued.confirmedAt)
        assertFalse(String(queued.payloadCiphertext).contains("approve"))
        assertEquals(decideBody(id, OwnerAction.APPROVE), String(store.openPayload(queued)!!))
        assertEquals("pending", store.request(profile.profileId, id)!!.serverStatus)
        assertEquals(1, db.syncDao().requestCount())
        assertTrue(db.verifyAudit().ok)
        assertTrue(store.waitingLine(profile.profileId, now).contains("Waiting"))
        db.close(); reopen()
        assertEquals("QUEUED", row(operation).status)
        assertNotNull(store.openPayload(row(operation)))
    }

    @Test fun duplicateAndOppositeTapKeepOneOperation() {
        val operation = queue()
        val again = store.enqueue(profile, request, OwnerAction.APPROVE, now + 1) as QueueOutcome.Existing
        val opposite = store.enqueue(profile, request, OwnerAction.REFUSE, now + 2) as QueueOutcome.Existing
        assertEquals(operation, again.operationId)
        assertEquals(operation, opposite.operationId)
        assertEquals(1, db.syncDao().outboxCount())
        assertTrue(db.verifyAudit().ok)
    }

    @Test fun simultaneousTapsKeepOneIntent() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val work = (1..4).map { pool.submit<QueueOutcome> { start.await(); store.enqueue(profile, request, OwnerAction.APPROVE, now) } }
        start.countDown()
        val results = work.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(1, results.count { it is QueueOutcome.Queued })
        assertEquals(1, db.syncDao().outboxCount())
        assertTrue(db.verifyAudit().ok)
    }

    @Test fun approvalIsConfirmedOnlyByFreshEffectiveState() {
        val operation = queue()
        val hub = FakeHub()
        val result = sync(hub).drain(config, now + 1)
        assertEquals(1, result.confirmed)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertEquals(1, hub.posts.size)
        assertEquals("pending", hub.status)
        assertFalse(hub.posts.single().jsonBody!!.contains("code"))
        assertTrue(hub.calls.all { rejectedHubPath(it.method, it.url) == null })
        sync(hub).drain(config, now + 2)
        assertEquals(1, hub.posts.size)
    }

    @Test fun refusalUsesTheStoredCanonicalBody() {
        val operation = queue(OwnerAction.REFUSE)
        val hub = FakeHub()
        sync(hub).drain(config, now + 1)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertEquals(decideBody(id, OwnerAction.REFUSE), hub.posts.single().jsonBody)
    }

    @Test fun lostResponseReconcilesWithoutPostingAgainAfterRestart() {
        val operation = queue()
        val hub = FakeHub().apply { lostResponse = true }
        sync(hub).drain(config, now + 1)
        assertEquals("SENT", row(operation).status)
        assertNull(row(operation).confirmedAt)
        db.close(); reopen()
        sync(hub).drain(config, now + 30_000)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertEquals(1, hub.posts.size)
    }

    @Test fun ambiguousUnappliedResponseRetriesIdenticalBytesAndId() {
        val operation = queue()
        val hub = FakeHub().apply { lostResponse = true; applyPost = false }
        sync(hub).drain(config, now + 1)
        hub.lostResponse = false; hub.applyPost = true
        sync(hub).drain(config, now + 30_000)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertEquals(2, hub.posts.size)
        assertEquals(hub.posts[0].jsonBody, hub.posts[1].jsonBody)
        assertEquals(operation, row(operation).operationId)
    }

    @Test fun serverErrorAfterCommitReconcilesWithoutDuplicate() {
        val operation = queue()
        val hub = FakeHub().apply { postCode = 503 }
        sync(hub).drain(config, now + 1)
        assertEquals("SENT", row(operation).status)
        sync(hub).drain(config, now + 30_000)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertEquals(1, hub.posts.size)
    }

    @Test fun malformedSuccessDoesNotClaimRemoteSuccess() {
        val operation = queue()
        val hub = FakeHub().apply { malformed = true }
        sync(hub).drain(config, now + 1)
        assertEquals("NEEDS_REVIEW", row(operation).status)
        assertNull(row(operation).confirmedAt)
    }

    @Test fun rawApprovedButEffectivelyExpiredIsNeverConfirmed() {
        val operation = queue()
        val hub = FakeHub().apply { decision = "approved"; effective = "expired" }
        sync(hub).drain(config, now + 1)
        assertEquals("NEEDS_REVIEW", row(operation).status)
        assertEquals("expired", row(operation).lastErrorCode)
        assertTrue(hub.posts.isEmpty())
    }

    @Test fun closedAbsentAndExpiredIntentsDoNotPost() {
        val operation = queue()
        val hub = FakeHub().apply { status = "issued" }
        sync(hub).drain(config, now + 1)
        assertEquals("CONFLICT", row(operation).status)
        assertTrue(hub.posts.isEmpty())
        val second = (store.enqueue(profile, request, OwnerAction.APPROVE, now + 2) as QueueOutcome.Queued).operationId
        hub.disappear = true
        sync(hub).drain(config, now + 3)
        assertEquals("NEEDS_REVIEW", row(second).status)
        val third = (store.enqueue(profile, request, OwnerAction.APPROVE, now + 4) as QueueOutcome.Queued).operationId
        sync(hub).drain(config, now + OutboxRules.INTENT_TTL_MS + 5)
        assertEquals("expired_intent", row(third).lastErrorCode)
        assertTrue(hub.posts.isEmpty())
    }

    @Test fun deniedCredentialPausesAllDispatchAndNewCredentialNeedsReconciliation() {
        val operation = queue()
        val hub = FakeHub().apply { statesCode = 401 }
        sync(hub).drain(config, now + 1)
        assertEquals("AUTH_BLOCKED", row(operation).status)
        assertTrue(store.bind(profile.profileId)!!.paused)
        assertFalse(sync(hub).noteReachable(config, now + 2))
        store.pauseCredential(profile.profileId, now + 3)
        hub.statesCode = 200
        assertTrue(sync(hub).noteReachable(config.copy(ownerToken = "rotated-synthetic"), now + 4))
        sync(hub).drain(config.copy(ownerToken = "rotated-synthetic"), now + 5)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertEquals("rotated-synthetic", hub.posts.single().bearer)
    }

    @Test fun originChangeAndDifferentHubNeverReceiveOldIntent() {
        val operation = queue()
        val otherConfig = config.copy(baseUrl = "https://other.example")
        store.ensureProfile(otherConfig.baseUrl, now + 1)!!
        val hub = FakeHub()
        sync(hub).drain(otherConfig, now + 2)
        assertTrue(hub.posts.isEmpty())
        assertEquals("QUEUED", row(operation).status)
        store.changeOrigin(profile.profileId, config.baseUrl, now + 3)
        sync(hub).drain(config, now + 4)
        assertEquals("NEEDS_REVIEW", row(operation).status)
        assertTrue(hub.posts.isEmpty())
    }

    @Test fun leaseHasOneWinnerAndKilledSenderIsReconciled() {
        val operation = queue()
        val original = row(operation)
        val claimed = OutboxMachine.claim(original.toState(), now + 1) as MachineMove.Ok
        assertTrue(store.apply(original, claimed.state))
        assertFalse(store.apply(original, claimed.state))
        assertTrue(store.due(profile.profileId, now + 2).isEmpty())
        val hub = FakeHub().apply { decision = "approved" }
        sync(hub).drain(config, now + OutboxRules.LEASE_MS + 2)
        assertEquals("CONFIRMED_REMOTE", row(operation).status)
        assertTrue(hub.posts.isEmpty())
    }

    @Test fun retryIsBoundedAndAnOfflineReconciliationKeepsUncertainty() {
        val operation = queue()
        val hub = FakeHub().apply { lostResponse = true; applyPost = false }
        sync(hub).drain(config, now + 1)
        hub.readOffline = true
        sync(hub).drain(config, now + 30_000)
        assertEquals("SENT", row(operation).status)
        var next = row(operation).nextAttemptAt
        repeat(OutboxRules.MAX_ATTEMPTS + 1) {
            sync(hub).drain(config, next)
            next = row(operation).nextAttemptAt + 1
        }
        assertEquals("NEEDS_REVIEW", row(operation).status)
        assertEquals(1, hub.posts.size)
    }

    @Test fun cacheAndCursorRollbackTogetherAndAbsenceNeverDeletes() {
        val cached = request.toCached()
        store.savePendingPage(profile, listOf(cached), "first", true, now)
        val before = store.cursor(profile.profileId, SyncStore.STREAM_PENDING)!!
        try {
            store.savePendingPage(profile, listOf(cached.copy(remoteId = "00000000-0000-4000-8000-000000000002"), cached.copy(remoteId = "invalid")), "bad", true, now + 1)
            fail("Invalid row should roll back")
        } catch (_: IllegalStateException) { }
        assertEquals(1, db.syncDao().requestCount())
        assertEquals(before, store.cursor(profile.profileId, SyncStore.STREAM_PENDING))
        store.savePendingPage(profile, emptyList(), "", true, now + 2)
        assertEquals(1, store.cachedRequests(profile.profileId).size)
    }

    @Test fun cacheDiscardsFreeTextAndMailboxIsPartialIdempotent() {
        store.saveEvents(profile, listOf(CachedEvent("1", id, "created", now / 1000, "A name or phone must not persist")), "1", false, now)
        store.saveMailbox(profile, listOf(CachedBatch("batch-1", "install-1", now / 1000, 12, 1)), "batch-1", false, now)
        store.saveMailbox(profile, listOf(CachedBatch("batch-1", "install-1", now / 1000, 12, 1)), "batch-1", false, now + 1)
        assertEquals(1, db.syncDao().mailboxCount())
        assertEquals(0, store.cursor(profile.profileId, SyncStore.STREAM_MAILBOX)!!.complete)
        assertEquals("", db.syncDao().eventByRemote(profile.profileId, "1")!!.safeDetail)
    }

    @Test fun notificationsAreDurableAndSeparatePerHubIncludingEmptyBaseline() {
        assertTrue(store.noticeSnapshot(profile.profileId, "notice_requests", emptyList(), now).notify.isEmpty())
        assertEquals(listOf(stableRequestKey(id)), store.noticeSnapshot(profile.profileId, "notice_requests", listOf(stableRequestKey(id)), now + 1).notify)
        db.close(); reopen()
        assertTrue(store.noticeSnapshot(profile.profileId, "notice_requests", listOf(stableRequestKey(id)), now + 2).notify.isEmpty())
        val other = store.ensureProfile("https://other.example", now + 3)!!
        assertTrue(store.noticeSnapshot(other.profileId, "notice_requests", listOf(stableRequestKey(id)), now + 4).notify.isEmpty())
        assertEquals(2, db.syncDao().noticeCount())
    }

    @Test fun unavailableEncryptionCannotLeaveAnIntentOrCacheRow() {
        val before = db.auditDao().entries(db.auditDao().chain()!!.chainId).size
        db.outboxSealer = PlainAuditSealer
        assertTrue(store.enqueue(profile, request, OwnerAction.APPROVE, now) is QueueOutcome.Rejected)
        assertEquals(0, db.syncDao().outboxCount())
        assertEquals(0, db.syncDao().requestCount())
        assertEquals(before, db.auditDao().entries(db.auditDao().chain()!!.chainId).size)
    }

    @Test fun tamperedPayloadIsBlockedAndOriginalBodyCannotBeChanged() {
        val operation = queue()
        val queued = row(operation)
        assertNull(store.openPayload(queued.copy(payloadCiphertext = queued.payloadCiphertext.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })))
        try {
            db.openHelper.writableDatabase.execSQL("UPDATE outbox SET payloadHash = ? WHERE operationId = ?", arrayOf("0".repeat(64), operation))
            fail("Payload must be immutable")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test fun idleDrainDoesNotPollOrCreateWork() {
        val hub = FakeHub()
        assertEquals(DrainSummary.idle(), sync(hub).drain(config, now + 1))
        assertTrue(hub.calls.isEmpty())
    }

    @Test fun workRequestContainsProfileScopeAndNoCredentialOrPayload() {
        val request = HubScheduler.outboxRequest(profile.profileId)
        assertTrue(request.tags.contains("profile:${profile.profileId}"))
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test fun stateAndAuditRollbackTogetherWhenAuditCannotSeal() {
        val operation = queue()
        val original = row(operation)
        val claimed = (OutboxMachine.claim(original.toState(), now + 1) as MachineMove.Ok).state
        val goodRuntime = db.auditRuntime
        db.auditRuntime = AuditRuntime(RandomIdSource, object : com.pixelplus.owner.audit.AuditSealer {
            override val keyAliasVersion = 1
            override fun seal(plain: ByteArray): ByteArray = error("Synthetic key failure")
            override fun open(sealed: ByteArray): ByteArray = error("Synthetic key failure")
        })
        try { store.apply(original, claimed); fail("Audit failure must roll back state") } catch (_: IllegalStateException) { }
        db.auditRuntime = goodRuntime
        assertEquals("QUEUED", row(operation).status)
        assertNull(row(operation).leaseUntil)
        assertTrue(db.verifyAudit().ok)
    }

    @Test fun version2MigratesInPlaceAndPreservesNotebook() {
        db.close(); context.deleteDatabase(name)
        val schema = JSONObject(locate("schemas/com.pixelplus.owner.data.db.OwnerDatabase/2.json").readText()).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (item in 0 until indices.length()) raw.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val workspace = "00000000-0000-4000-8000-000000000010"
        raw.execSQL("INSERT INTO workspaces (id,name,defaultCurrency,createdAtUtcMs,updatedAtUtcMs,revision) VALUES (?,?, 'EGP',?,?,1)", arrayOf<Any>(workspace,"Synthetic office",now,now))
        raw.execSQL("PRAGMA user_version = 2"); raw.close()
        reopen()
        assertEquals("Synthetic office", db.ownerDao().workspace(workspace)!!.name)
        assertEquals(3, db.openHelper.writableDatabase.version)
        profile = store.ensureProfile(config.baseUrl, now + 1)!!
        assertEquals(1L, profile.credentialEpoch)
        assertFalse(profile.paused)
        queue()
        assertEquals(1, db.syncDao().outboxCount())
        assertTrue(db.verifyAudit().ok)
    }
    private fun locate(relative: String): File = listOf(File(relative), File("../$relative"), File("../../$relative")).first { it.isFile }
}
