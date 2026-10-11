package com.pixelplus.owner.backup

import com.pixelplus.owner.audit.AuditHash
import com.pixelplus.owner.sync.OutboxState
import com.pixelplus.owner.sync.OutboxStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val sealer = AesGcmBackupSealer.ephemeral()
    private val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
    private val db = sqliteMagic + ByteArray(2000) { (it % 251).toByte() }
    private val json = """{"snapshot":"x","customers":[]}""".toByteArray()
    private val heads = mapOf("22222222-2222-4222-8222-222222222222" to AuditHeadEntry(7, "ab".repeat(32)))
    private val profiles = setOf("66666666-6666-4666-8666-666666666666")

    private fun build(files: Map<String, ByteArray> = mapOf(BackupLimits.DATABASE to db, BackupLimits.LOGICAL to json), s: BackupSealer = sealer) =
        BackupArchive.build("77777777-7777-4777-8777-777777777777", 1_800_000_000_000, 3, "0.4.0", heads, profiles, files, s)

    private fun archive() = (build() as ArchiveResult.Built).bytes

    @Test fun roundTripKeepsEveryFileAndTheManifest() {
        val opened = BackupArchive.open(archive(), sealer) as OpenResult.Opened
        assertArrayEquals(db, opened.files.getValue(BackupLimits.DATABASE))
        assertArrayEquals(json, opened.files.getValue(BackupLimits.LOGICAL))
        assertEquals(3, opened.manifest.dbSchemaVersion)
        assertEquals(heads, opened.manifest.auditHeads)
        assertEquals(profiles, opened.manifest.hubProfileIds)
        assertEquals(db.size.toLong(), opened.manifest.files.getValue(BackupLimits.DATABASE).size)
        assertEquals("device", opened.manifest.keyMode)
    }

    @Test fun theArchiveHoldsNoPlainDatabaseAndAFreshNonceEveryTime() {
        val a = archive()
        val b = archive()
        assertFalse(String(a, Charsets.ISO_8859_1).contains("SQLite format 3"))
        assertFalse(String(a, Charsets.ISO_8859_1).contains("customers"))
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun theManifestIsReadableWithoutTheKeyAndHoldsNoSecret() {
        val (manifest, fault) = BackupArchive.peek(archive())
        assertNull(fault)
        val text = String(manifest!!.bytes())
        for (word in listOf("token", "secret", "bearer", "password", "privateKey")) assertFalse(word, text.lowercase().contains(word.lowercase()))
    }

    @Test fun aWrongKeyAndAChangedByteGiveTheSameAnswer() {
        val a = archive()
        assertEquals(OpenResult.Refused(BackupFault.WRONG_KEY_OR_TAMPERED), BackupArchive.open(a, AesGcmBackupSealer.ephemeral()))
        for (index in listOf(a.size - 1, a.size - 20, a.size / 2)) {
            val bad = a.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(BackupFault.WRONG_KEY_OR_TAMPERED, (BackupArchive.open(bad, sealer) as OpenResult.Refused).reason)
        }
    }

    @Test fun theManifestIsAuthenticatedEvenThoughItIsPlain() {
        val a = archive()
        val (manifest, _) = BackupArchive.peek(a)
        val forged = manifest!!.copy(dbSchemaVersion = 1).bytes()
        val orig = manifest.bytes()
        assertEquals(orig.size, forged.size)   // same length so the header still parses: only the authentication can catch it
        val bad = a.copyOf()
        System.arraycopy(forged, 0, bad, 9, forged.size)
        assertEquals(BackupFault.WRONG_KEY_OR_TAMPERED, (BackupArchive.open(bad, sealer) as OpenResult.Refused).reason)
    }

    @Test fun notAnArchiveTruncatedOrNewerFormatIsRefusedBeforeTheKey() {
        val a = archive()
        assertEquals(BackupFault.NOT_AN_ARCHIVE, BackupArchive.peek(ByteArray(0)).second)
        assertEquals(BackupFault.NOT_AN_ARCHIVE, BackupArchive.peek("not a backup at all".toByteArray()).second)
        assertEquals(BackupFault.NOT_AN_ARCHIVE, BackupArchive.peek(a.copyOf(12)).second)
        assertEquals(BackupFault.UNSUPPORTED_FORMAT, BackupArchive.peek(a.copyOf().also { it[4] = 2 }).second)
        assertEquals(BackupFault.WRONG_KEY_OR_TAMPERED, (BackupArchive.open(a.copyOf(a.size - 5), sealer) as OpenResult.Refused).reason)
        assertEquals(BackupFault.NOT_AN_ARCHIVE, BackupArchive.peek(a.copyOf().also { it[5] = 0x7f }).second)
    }

    @Test fun buildRefusesBadNamesTooManyAndTooLargeFiles() {
        assertEquals(ArchiveResult.Refused(BackupFault.BAD_MANIFEST), build(mapOf("../etc/passwd" to json)))
        assertEquals(ArchiveResult.Refused(BackupFault.BAD_MANIFEST), build(mapOf("A.db" to json)))
        assertEquals(ArchiveResult.Refused(BackupFault.TOO_LARGE), build(emptyMap()))
        assertEquals(ArchiveResult.Refused(BackupFault.TOO_LARGE), build((1..9).associate { "f$it" to json }))
        assertEquals(ArchiveResult.Refused(BackupFault.TOO_LARGE), build(mapOf("big.bin" to ByteArray(BackupLimits.MAX_PAYLOAD_BYTES + 1))))
    }

    @Test fun aPlainDatabaseThatSomeHowReachesTheOutputIsCaught() {
        val leaky = object : BackupSealer {
            override fun seal(aad: ByteArray, plain: ByteArray) = ByteArray(12) + plain   // "encryption" that forgot to encrypt
            override fun open(aad: ByteArray, sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
        }
        assertEquals(ArchiveResult.Refused(BackupFault.PLAINTEXT_LEAK), build(s = leaky))
    }

    // ---- restore ----

    @Test fun restoreChecksAreReadyWithTheNoticesTheOwnerMustSee() {
        val ready = RestorePlanner.check(archive(), sealer, currentDbSchemaVersion = 3) as RestoreCheck.Ready
        assertEquals(
            listOf(RestoreNotice.TOKENS_MUST_BE_ENTERED_AGAIN, RestoreNotice.QUEUE_NEEDS_REVIEW, RestoreNotice.NEW_AUDIT_CHAIN_STARTS, RestoreNotice.SIGNING_STAYS_OFF),
            ready.plan.notices,
        )
        val older = RestorePlanner.check(archive(), sealer, currentDbSchemaVersion = 5) as RestoreCheck.Ready
        assertTrue(RestoreNotice.OLDER_SCHEMA_WILL_MIGRATE in older.plan.notices)
    }

    @Test fun aNewerDatabaseIsRefusedEvenWithTheWrongKey() {
        assertEquals(RestoreCheck.Refused(BackupFault.NEWER_SCHEMA), RestorePlanner.check(archive(), AesGcmBackupSealer.ephemeral(), currentDbSchemaVersion = 2))
        assertEquals(RestoreCheck.Refused(BackupFault.WRONG_KEY_OR_TAMPERED), RestorePlanner.check(archive(), AesGcmBackupSealer.ephemeral(), currentDbSchemaVersion = 3))
    }

    @Test fun anArchiveWithoutTheDatabaseOrTheExportIsNotRestorable() {
        val partial = (build(mapOf(BackupLimits.DATABASE to db)) as ArchiveResult.Built).bytes
        assertEquals(RestoreCheck.Refused(BackupFault.MISSING_FILE), RestorePlanner.check(partial, sealer, 3))
    }

    @Test fun aManifestSplicedOntoAnotherPayloadIsRefused() {
        val good = BackupArchive.build("77777777-7777-4777-8777-777777777777", 1, 3, "0.4.0", heads, profiles, mapOf(BackupLimits.DATABASE to db, BackupLimits.LOGICAL to json), sealer)
        val other = BackupArchive.build("77777777-7777-4777-8777-777777777777", 1, 3, "0.4.0", heads, profiles, mapOf(BackupLimits.DATABASE to db, BackupLimits.LOGICAL to "{}".toByteArray()), sealer)
        val a = (good as ArchiveResult.Built).bytes
        val b = (other as ArchiveResult.Built).bytes
        // a manifest from `good` over the payload of `other`: the seal (AAD) catches the swap
        val (mGood, _) = BackupArchive.peek(a)
        val (mOther, _) = BackupArchive.peek(b)
        assertNotEquals(mGood!!.files, mOther!!.files)
        val spliced = a.copyOf(9 + mGood.bytes().size) + b.copyOfRange(9 + mOther.bytes().size, b.size)
        assertEquals(BackupFault.WRONG_KEY_OR_TAMPERED, (BackupArchive.open(spliced, sealer) as OpenResult.Refused).reason)
    }

    @Test fun quarantineTurnsEveryActiveDecisionIntoNeedsReviewAndLeavesFinishedOnesAlone() {
        fun state(status: OutboxStatus) = OutboxState(status, 2, 5L, if (status == OutboxStatus.LEASED) 9L else null, null, 100L, null, 1L, 1L)
        val before = OutboxStatus.entries.map { state(it) }
        val after = RestorePlanner.quarantine(before)
        for ((b, a) in before.zip(after)) {
            if (b.status.name in com.pixelplus.owner.sync.OutboxRules.ACTIVE) {
                assertEquals(OutboxStatus.NEEDS_REVIEW, a.status)
                assertNull(a.leaseUntil)
            } else {
                assertEquals(b, a)
            }
        }
        assertTrue(after.none { it.status == OutboxStatus.QUEUED || it.status == OutboxStatus.LEASED || it.status == OutboxStatus.SENT })
    }

    // ---- coordinator ----

    private class FakeSource(var fail: Boolean = false, val db: ByteArray, val json: ByteArray) : SnapshotSource {
        var open = true
        var quiescedCount = 0
        var reopened = 0
        override fun <T> quiesced(block: (Snapshot) -> T): T {
            open = false; quiescedCount++
            try {
                if (fail) error("disk")
                return block(Snapshot(3, "0.4.0", db, json, mapOf("c" to AuditHeadEntry(0, AuditHash.GENESIS)), emptySet()))
            } finally { open = true; reopened++ }
        }
    }

    private class FakeSink(var failWrite: Boolean = false, var failCommit: Boolean = false) : BackupSink {
        val temps = mutableMapOf<String, ByteArray>()
        val finals = mutableMapOf<String, ByteArray>()
        override fun writeTemp(name: String, sealed: ByteArray) = if (failWrite) false else { temps[name] = sealed; true }
        override fun commit(name: String): Boolean {
            if (failCommit) return false
            finals[name.removeSuffix(".tmp")] = temps.remove(name)!!
            return true
        }
        override fun discardTemp(name: String) { temps.remove(name) }
    }

    private fun coordinator(source: SnapshotSource, sink: BackupSink) = BackupCoordinator(source, sealer, sink, { 1_800_000_000_000 }, { "77777777-7777-4777-8777-777777777777" })

    @Test fun aBackupWritesOnlySealedBytesAndReopensTheDatabase() {
        val src = FakeSource(db = db, json = json)
        val sink = FakeSink()
        val outcome = coordinator(src, sink).run() as BackupOutcome.Done
        val stored = sink.finals.getValue(BackupCoordinator.DEFAULT_NAME)
        assertEquals(stored.size, outcome.sizeBytes)
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("SQLite format 3"))
        assertTrue(sink.temps.isEmpty())
        assertTrue(src.open)
        assertTrue(BackupArchive.open(stored, sealer) is OpenResult.Opened)
    }

    @Test fun aFailureAtAnyStageKeepsTheLastGoodArtifactAndReopensTheDatabase() {
        val sink = FakeSink()
        val first = coordinator(FakeSource(db = db, json = json), sink)
        assertTrue(first.run() is BackupOutcome.Done)
        val good = sink.finals.getValue(BackupCoordinator.DEFAULT_NAME).copyOf()

        val broken = FakeSource(fail = true, db = db, json = json)
        assertEquals(BackupOutcome.Failed(Stage.SNAPSHOT), coordinator(broken, sink).run())
        assertTrue(broken.open); assertEquals(1, broken.reopened)

        val src = FakeSource(db = db, json = json)
        sink.failWrite = true
        assertEquals(BackupOutcome.Failed(Stage.WRITE), coordinator(src, sink).run())
        sink.failWrite = false; sink.failCommit = true
        assertEquals(BackupOutcome.Failed(Stage.COMMIT), coordinator(src, sink).run())
        assertTrue(src.open); assertEquals(2, src.reopened)
        assertTrue(sink.temps.isEmpty())
        assertArrayEquals(good, sink.finals.getValue(BackupCoordinator.DEFAULT_NAME))
    }

    @Test fun anOversizedSnapshotFailsAtSealWithAReasonAndStillReopens() {
        val src = FakeSource(db = ByteArray(BackupLimits.MAX_PAYLOAD_BYTES + 1), json = json)
        val sink = FakeSink()
        assertEquals(BackupOutcome.Failed(Stage.SEAL, BackupFault.TOO_LARGE), coordinator(src, sink).run())
        assertTrue(src.open)
        assertTrue(sink.finals.isEmpty() && sink.temps.isEmpty())
    }
}
