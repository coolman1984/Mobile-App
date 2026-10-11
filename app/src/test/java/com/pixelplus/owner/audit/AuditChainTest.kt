package com.pixelplus.owner.audit

import com.pixelplus.owner.domain.CairoClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The canonical bytes are a fixed array. A later worker must hash the same bytes.
 */
class AuditChainTest {
    @Test fun goldenBytesMatchTheSharedFixture() {
        val fixture = Json.parseToJsonElement(resource("audit/golden-v1.json")).jsonObject
        val expected = fixture.getValue("canonical").jsonPrimitive.content
        val expectedHash = fixture.getValue("entryHash").jsonPrimitive.content
        val previous = fixture.getValue("previousHash").jsonPrimitive.content
        assertEquals(AuditHash.GENESIS, previous)
        val stamp = CairoClock.stamp(AT)
        assertEquals("2023-11-15T00:13:20+02:00", stamp.cairoLocalIso)
        assertEquals(7_200, stamp.cairoOffsetSeconds)
        val text = AuditText.explain(AuditVocab.ACTION_CREATE, AuditVocab.ENTITY_WORKSPACE, AuditVocab.RESULT_SUCCESS)
        assertEquals("تم إنشاء مساحة العمل.", text.arabic)
        assertEquals("Workspace was created.", text.english)
        val draft = sample(
            sequence = 1,
            eventId = EVENT,
            at = AT,
            before = emptyMap(),
            after = mapOf("revision" to JsonValue.Int64(1)),
        )
        val canonical = AuditCanonical.text(draft)
        assertEquals(expected, canonical)
        assertEquals(AuditCanonical.FIELD_COUNT, AuditCanonical.draftOf(canonical.toByteArray()).let { 27 })
        assertFalse(canonical.contains(", "))
        assertFalse(canonical.contains(": "))
        assertEquals(4, Regex("null").findAll(canonical).count())
        val hash = AuditHash.of(previous, canonical.toByteArray())
        assertEquals(expectedHash, hash)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteArray(32))
        digest.update(canonical.toByteArray(Charsets.UTF_8))
        assertEquals(expectedHash, digest.digest().joinToString("") { "%02x".format(it) })
        val again = AuditCanonical.draftOf(canonical.toByteArray())
        assertEquals(draft.eventId, again.eventId)
        assertEquals(draft.explanationAr, again.explanationAr)
        assertEquals(draft.explanationEn, again.explanationEn)
        assertEquals(1L, again.afterSafe.getValue("revision").let { (it as JsonValue.Int64).value })
    }

    @Test fun nestedKeysAreSortedAndStringsAreNfc() {
        val unsorted = linkedMapOf(
            "z" to JsonValue.Int64(2),
            "a" to JsonValue.Bool(true),
        )
        val composed = AuditCanonical.objectText(mapOf("label" to JsonValue.Text("caf\u00e9")))
        val decomposed = AuditCanonical.objectText(mapOf("label" to JsonValue.Text("cafe\u0301")))
        assertEquals(composed, decomposed)
        assertTrue(composed.contains("caf\u00e9"))
        assertFalse(decomposed.contains("e\\u0301"))
        val ordered = AuditCanonical.objectText(unsorted)
        assertEquals("{\"a\":true,\"z\":2}", ordered)
        val escaped = AuditCanonical.objectText(mapOf("reason" to JsonValue.Text("say \"hi\"\n")))
        assertEquals("{\"reason\":\"say \\\"hi\\\"\\n\"}", escaped)
    }

    @Test fun parserRejectsWhitespaceFractionsAndDuplicateKeys() {
        assertTrue(runCatching { CanonicalJson.parse(" [1]".toByteArray()) }.isFailure)
        assertTrue(runCatching { CanonicalJson.parse("[1.5]".toByteArray()) }.isFailure)
        assertTrue(runCatching { CanonicalJson.parse("[1e2]".toByteArray()) }.isFailure)
        assertTrue(runCatching { CanonicalJson.parse("{\"a\":1,\"a\":2}".toByteArray()) }.isFailure)
    }

    @Test fun tamperGapReorderReplayAndPredecessorAreNamed() {
        val first = link(1, EVENT, AuditHash.GENESIS)
        val second = link(2, EVENT_2, first.entryHash, at = AT + 1)
        val third = link(3, EVENT_3, second.entryHash, at = AT + 2)
        assertTrue(AuditCanonical.verify(listOf(first, second, third)).ok)

        val flipped = first.canonicalBytes.copyOf()
        val time = "1700000000000".toByteArray()
        val index = indexOf(flipped, time)
        assertTrue(index >= 0)
        flipped[index + time.size - 1] = '1'.code.toByte()
        val tampered = ChainLink(first.previousHash, first.entryHash, flipped)
        assertTrue(ChainFault.TAMPER in AuditCanonical.verify(listOf(tampered, second, third)).faults)

        val gap = AuditCanonical.verify(listOf(first, third))
        assertTrue(ChainFault.GAP in gap.faults)
        assertTrue(ChainFault.PREDECESSOR in gap.faults)

        val reordered = AuditCanonical.verify(listOf(first, third, second))
        assertTrue(ChainFault.REORDER in reordered.faults)
        assertFalse(reordered.ok)

        val replay = link(2, EVENT, first.entryHash, at = AT + 9)
        assertTrue(ChainFault.REPLAY in AuditCanonical.verify(listOf(first, replay)).faults)

        val forked = link(2, EVENT_2, AuditHash.GENESIS, at = AT + 1)
        val predecessor = AuditCanonical.verify(listOf(first, forked))
        assertTrue(ChainFault.PREDECESSOR in predecessor.faults || ChainFault.TAMPER in predecessor.faults)
        assertFalse(predecessor.ok)

        assertTrue(AuditCanonical.verify(emptyList()).ok)
    }

    @Test fun redactionDropsSecretsAndKeepsAllowlistedStatus() {
        val safe = AuditRedaction.redact(
            mapOf(
                "amount" to JsonValue.Int64(1500),
                "amountMinor" to JsonValue.Int64(1500),
                "reference" to JsonValue.Text("cash-12"),
                "phone" to JsonValue.Text("01001234567"),
                "token" to JsonValue.Text("abcdef"),
                "note" to JsonValue.Text("private note"),
                "code" to JsonValue.Text("ABCDEFGHJKMNPQ"),
                "normalizedSearchToken" to JsonValue.Text("present"),
                "currency" to JsonValue.Text("EGP"),
                "localStatus" to JsonValue.Text("paused"),
                "favoriteColor" to JsonValue.Text("blue"),
                "nested" to JsonValue.Obj(mapOf("token" to JsonValue.Text("nope"))),
                "remoteId" to JsonValue.Text("a".repeat(32)),
            ),
        )
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("amount") as JsonValue.Text).value)
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("phone") as JsonValue.Text).value)
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("token") as JsonValue.Text).value)
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("note") as JsonValue.Text).value)
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("code") as JsonValue.Text).value)
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("reference") as JsonValue.Text).value)
        assertEquals(AuditRedaction.REDACTED, (safe.getValue("remoteId") as JsonValue.Text).value)
        assertFalse(safe.containsKey("favoriteColor"))
        assertFalse(safe.containsKey("nested"))
        assertEquals("EGP", (safe.getValue("currency") as JsonValue.Text).value)
        assertEquals("paused", (safe.getValue("localStatus") as JsonValue.Text).value)
        val money = AuditRedaction.postedReversal("EGP")
        assertEquals("EGP", (money.getValue("currency") as JsonValue.Text).value)
        assertEquals(true, (money.getValue("reversal") as JsonValue.Bool).value)
        assertFalse(money.containsKey("amount"))
        val rendered = AuditCanonical.objectText(safe)
        assertFalse(rendered.contains("1500"))
        assertFalse(rendered.contains("01001234567"))
        assertFalse(rendered.contains("cash-12"))
        assertFalse(rendered.contains("private note"))
    }

    @Test fun exportKeepsBothLanguagesAndTheVerificationResult() {
        val first = link(1, EVENT, AuditHash.GENESIS)
        val report = AuditCanonical.verify(listOf(first))
        val markdown = AuditExport.markdown(listOf(first), report)
        assertTrue(markdown.contains("تم إنشاء مساحة العمل."))
        assertTrue(markdown.contains("Workspace was created."))
        assertTrue(markdown.contains("سليم"))
        assertTrue(markdown.contains("ok"))
        val json = AuditExport.json(listOf(first), report)
        assertTrue(json.startsWith("{\"ok\":true"))
        assertTrue(json.contains("Workspace was created."))
    }

    private fun link(sequence: Long, eventId: String, previous: String, at: Long = AT): ChainLink {
        val draft = sample(
            sequence = sequence,
            eventId = eventId,
            at = at,
            before = emptyMap(),
            after = mapOf("revision" to JsonValue.Int64(sequence)),
        )
        return AuditCanonical.link(draft, previous)
    }

    private fun sample(
        sequence: Long,
        eventId: String,
        at: Long,
        before: Map<String, JsonValue>,
        after: Map<String, JsonValue>,
    ): AuditDraft {
        val stamp = CairoClock.stamp(at)
        val text = AuditText.explain(AuditVocab.ACTION_CREATE, AuditVocab.ENTITY_WORKSPACE, AuditVocab.RESULT_SUCCESS)
        return AuditDraft(
            schemaVersion = AuditCanonical.SCHEMA_VERSION,
            eventId = eventId,
            chainId = CHAIN,
            sequence = sequence,
            deviceInstanceId = DEVICE,
            actorType = AuditVocab.ACTOR_OWNER,
            workspaceId = WORKSPACE,
            hubProfileId = null,
            occurredAtUtcMs = stamp.occurredAtUtcMs,
            cairoLocalIso = stamp.cairoLocalIso,
            cairoOffsetSeconds = stamp.cairoOffsetSeconds,
            zoneId = stamp.zoneId,
            screen = AuditVocab.SCREEN_NOTEBOOK,
            action = AuditVocab.ACTION_CREATE,
            entityType = AuditVocab.ENTITY_WORKSPACE,
            entityId = WORKSPACE,
            beforeSafe = before,
            afterSafe = after,
            result = AuditVocab.RESULT_SUCCESS,
            reasonCode = null,
            explanationAr = text.arabic,
            explanationEn = text.english,
            explanationTemplateVersion = text.templateVersion,
            correlationId = eventId,
            originalEventId = null,
            operationId = null,
            canonicalVersion = AuditCanonical.CANONICAL_VERSION,
        )
    }

    private fun resource(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(path) ?: error("missing $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        val last = haystack.size - needle.size
        for (start in 0..last) {
            if ((0 until needle.size).all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    private companion object {
        const val AT = 1_700_000_000_000L
        const val EVENT = "11111111-1111-4111-8111-111111111111"
        const val EVENT_2 = "55555555-5555-4555-8555-555555555555"
        const val EVENT_3 = "66666666-6666-4666-8666-666666666666"
        const val CHAIN = "22222222-2222-4222-8222-222222222222"
        const val DEVICE = "33333333-3333-4333-8333-333333333333"
        const val WORKSPACE = "44444444-4444-4444-8444-444444444444"
    }
}
