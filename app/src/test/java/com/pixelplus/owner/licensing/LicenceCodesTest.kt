package com.pixelplus.owner.licensing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Kotlin reader against what the factory's own Python reader answered for the same codes (tools/gen_code_vectors.py). */
class LicenceCodesTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("licensing/vectors-v1.json")!!.bufferedReader().use { it.readText() },
    ).jsonObject
    private val pub = vectors.getValue("publicKey").jsonPrimitive.content
    private val other = vectors.getValue("otherPublicKey").jsonPrimitive.content

    private fun text(o: JsonObject, k: String): String? = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun assertSame(label: String, expect: JsonObject, got: CodeResult) {
        assertEquals("$label valid", expect.getValue("valid").jsonPrimitive.content.toBoolean(), got.valid)
        assertEquals("$label state", text(expect, "state"), got.state)
        assertEquals("$label reason", text(expect, "reason"), got.reason)
        val terms = expect.getValue("terms").jsonObject
        if (terms.isEmpty()) { assertNull("$label terms", got.terms); return }
        val t = got.terms!!
        assertEquals("$label edition", text(terms, "edition"), t.edition)
        assertEquals("$label issued", text(terms, "issued"), t.issued.toString())
        assertEquals("$label first", text(terms, "first_day"), t.firstDay.toString())
        assertEquals("$label last", text(terms, "last_day"), t.lastDay?.toString())
        assertEquals("$label grace", terms.getValue("grace_days").jsonPrimitive.content.toInt(), t.graceDays)
        assertEquals("$label seats", terms.getValue("seats").jsonPrimitive.content.toInt(), t.seats)
        assertEquals("$label serial", text(terms, "serial"), t.serial)
        assertEquals("$label bound", terms.getValue("device_bound").jsonPrimitive.content.toBoolean(), t.deviceBound)
        assertEquals("$label daysLeft", text(terms, "days_left")?.toLong(), t.daysLeft)
    }

    @Test fun everyRecordedCaseGivesTheSameAnswerAsThePythonReader() {
        var checked = 0
        for (case in vectors.getValue("cases").jsonArray.map { it.jsonObject }) {
            val code = text(case, "code")!!
            assertEquals(167, code.length)
            for (c in case.getValue("checks").jsonArray.map { it.jsonObject }) {
                val key = if (text(c, "key") == "pub") pub else other
                val got = LicenceCodes.read(code, listOf(key), text(c, "product")!!, text(c, "device"), LocalDate.parse(text(c, "today")))
                assertSame("${text(case, "name")} ${text(c, "label")} ${text(c, "today")}", c.getValue("expect").jsonObject, got)
                checked++
            }
        }
        assertEquals(3 * 9 * 5, checked)
    }

    @Test fun mutationsAndSloppyTypingMatchToo() {
        val cases = vectors.getValue("cases").jsonArray
        val device = "7KD2M-QX9TP"
        for (m in vectors.getValue("mutations").jsonArray.map { it.jsonObject }) {
            val got = LicenceCodes.read(text(m, "text")!!, listOf(pub), "al-store", device, LocalDate.of(2026, 10, 2))
            assertSame(text(m, "label")!!, m.getValue("expect").jsonObject, got)
        }
        assertTrue(cases.size == 3)
    }

    @Test fun theStatesFollowTheDatesIncludingGraceAndPerpetual() {
        fun case(name: String) = vectors.getValue("cases").jsonArray.map { it.jsonObject }.first { text(it, "name") == name }
        fun read(name: String, device: String?, day: String) = LicenceCodes.read(text(case(name), "code")!!, listOf(pub), "al-store", device, LocalDate.parse(day))
        assertEquals("not_yet_valid", read("trial_bound", "7KD2M-QX9TP", "2026-09-30").state)
        assertEquals("active", read("trial_bound", "7KD2M-QX9TP", "2026-10-14").state)
        assertEquals("expired", read("trial_bound", "7KD2M-QX9TP", "2026-10-15").state)
        assertEquals("active", read("standard_grace", null, "2026-10-30").state)
        assertEquals("grace", read("standard_grace", null, "2026-11-02").state)
        assertEquals("expired", read("standard_grace", null, "2026-11-03").state)
        val forever = read("perpetual_bound", "7KD2M-QX9TP", "2030-01-01")
        assertEquals("active", forever.state); assertNull(forever.terms!!.lastDay); assertNull(forever.terms!!.daysLeft)
        assertTrue(forever.fullAccess)
        assertFalse(read("trial_bound", "7KD2M-QX9TP", "2026-10-15").fullAccess)
    }

    @Test fun anUnavailableVerifierNeverMeansValid() {
        val code = vectors.getValue("cases").jsonArray.first().jsonObject.getValue("code").jsonPrimitive.content
        val r = LicenceCodes.read(code, listOf(pub), "al-store", "7KD2M-QX9TP", LocalDate.of(2026, 10, 2)) { _, _, _ -> false }
        assertEquals(CodeResult(false, "invalid", "bad_signature"), r)
    }

    @Test fun malformedKeysAndInputNeverThrow() {
        val code = vectors.getValue("cases").jsonArray.first().jsonObject.getValue("code").jsonPrimitive.content
        val day = LocalDate.of(2026, 10, 2)
        assertEquals("unknown_key", LicenceCodes.read(code, listOf("!!!", ""), "al-store", null, day).reason)
        assertEquals("unknown_key", LicenceCodes.read(code, emptyList(), "al-store", null, day).reason)
        assertEquals("bad_character", LicenceCodes.read("é", listOf(pub), "al-store", null, day).reason)
        assertEquals("wrong_length", LicenceCodes.read("", listOf(pub), "al-store", null, day).reason)
        assertEquals("wrong_length", LicenceCodes.read("0".repeat(145), listOf(pub), "al-store", null, day).reason)
        assertEquals("other_device", LicenceCodes.read(code, listOf(pub), "al-store", "short", day).reason)
        assertEquals("other_device", LicenceCodes.read(code, listOf(pub), "al-store", "", day).reason)
    }

    // ---- the gate ----

    private fun open() = GateInputs(build = true, requestedByOwner = true, hardware = HardwareSupport.CANDIDATE_TEE, productKeyTrusted = true,
        paymentEvidence = true, freshStrongAuth = true, requestReconciled = true)

    @Test fun theBuildSwitchIsOffInThisBuild() {
        assertFalse("real issuing must stay off in a general build", OwnerBuild.REAL_ISSUANCE_ENABLED)
    }

    @Test fun noCombinationOpensTheGateWhileTheBuildSwitchIsOff() {
        val flags = listOf(false, true)
        var closed = 0
        for (req in flags) for (hw in HardwareSupport.entries) for (trust in flags) for (pay in flags) for (auth in flags) for (rec in flags) {
            val r = CodesGate.evaluate(GateInputs(false, req, hw, trust, pay, auth, rec))
            assertTrue(r is GateResult.Closed && r.reasons.first() == "build_off")
            closed++
        }
        assertEquals(2 * 3 * 2 * 2 * 2 * 2, closed)
        assertTrue("with every condition met and the build on it does open", CodesGate.evaluate(open()) == GateResult.Open)
        assertTrue(CodesGate.evaluate(GateInputs(requestedByOwner = true, hardware = HardwareSupport.CANDIDATE_TEE, productKeyTrusted = true,
            paymentEvidence = true, freshStrongAuth = true, requestReconciled = true)) is GateResult.Closed)
    }

    @Test fun eachMissingConditionClosesTheGateOnItsOwn() {
        val base = open()
        val cases = mapOf(
            "not_requested" to base.copy(requestedByOwner = false), "hardware" to base.copy(hardware = HardwareSupport.UNAVAILABLE_SOFTWARE_ONLY),
            "product_not_trusting_key" to base.copy(productKeyTrusted = false), "no_payment_evidence" to base.copy(paymentEvidence = false),
            "no_fresh_auth" to base.copy(freshStrongAuth = false), "not_reconciled" to base.copy(requestReconciled = false),
        )
        for ((reason, inputs) in cases) assertEquals(GateResult.Closed(listOf(reason)), CodesGate.evaluate(inputs))
    }

    @Test fun theSignerImplementationIsNeverReachedWhileClosed() {
        var calls = 0
        val impl = SignerImpl { calls++; ByteArray(64) }
        val closed = GatedSigner({ open().copy(build = false) }, impl)
        assertTrue(closed.sign(ByteArray(26)) is SignOutcome.Refused)
        assertEquals(0, calls)
        val real = GatedSigner({ GateInputs(requestedByOwner = true, hardware = HardwareSupport.CANDIDATE_TEE, productKeyTrusted = true, paymentEvidence = true, freshStrongAuth = true, requestReconciled = true) }, impl)
        assertTrue(real.sign(ByteArray(26)) is SignOutcome.Refused)
        assertEquals(0, calls)
        assertEquals(1, (GatedSigner({ open() }, impl).sign(ByteArray(26)) as SignOutcome.Signed).signature.let { calls })
    }

    @Test fun hardwareIsClassifiedByApiAndRealSecurityLevel() {
        assertEquals(HardwareSupport.UNAVAILABLE_OLD_OS, Hardware.classify(32, "tee"))
        assertEquals(HardwareSupport.UNAVAILABLE_SOFTWARE_ONLY, Hardware.classify(34, "software"))
        assertEquals(HardwareSupport.UNAVAILABLE_SOFTWARE_ONLY, Hardware.classify(34, null))
        assertEquals(HardwareSupport.CANDIDATE_TEE, Hardware.classify(33, "tee"))
        assertEquals(HardwareSupport.CANDIDATE_TEE, Hardware.classify(35, "strongbox"))
    }
}
