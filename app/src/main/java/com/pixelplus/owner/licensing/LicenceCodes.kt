package com.pixelplus.owner.licensing

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * M14: the licence-code *reader*, byte for byte the factory's `packages/af-license` (codes.py). It only reads and checks. There is no
 * signing code in this file and none anywhere in the app: signing stays OFF (see [CodesGate]). No Android types.
 *
 * Layout (26 bytes, big endian, Python `>B4sBHHHB6s4sB2s`): version | product tag(4) | edition | issued day | first day | last day |
 * grace | device tag(6) | serial(4) | seats | key tag(2), then a 64-byte Ed25519 signature over `AF-LICENCE-CODE/1` + payload.
 * Text: Crockford base32, 144 symbols. Days count from 2024-01-01. A perpetual code stores 65535 as its last day.
 */
object OwnerBuild {
    /** The one switch for real issuing. A test fails if this is ever true in a general build. */
    const val REAL_ISSUANCE_ENABLED: Boolean = false
}

fun interface Ed25519Verifier {
    /** False for any signature that does not verify, and also when verification is not possible. Never throws. */
    fun verify(publicRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** The JDK's Ed25519 (Java 15+). Android has it from API 33; below that a phone would be told "unavailable", never "valid". */
object JdkEd25519 : Ed25519Verifier {
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    override fun verify(publicRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        if (publicRaw.size != 32 || signature.size != 64) false
        else {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + publicRaw))
            Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
        }
    } catch (e: Exception) {
        false
    }
}

data class CodeTerms(
    val edition: String,
    val issued: LocalDate,
    val firstDay: LocalDate,
    val lastDay: LocalDate?,
    val graceDays: Int,
    val seats: Int,
    val serial: String,
    val deviceBound: Boolean,
    val daysLeft: Long?,
)

data class CodeResult(val valid: Boolean, val state: String, val reason: String = "", val terms: CodeTerms? = null) {
    val fullAccess: Boolean get() = valid && (state == "active" || state == "grace")
}

object LicenceCodes {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PAYLOAD_LEN = 26
    const val CODE_LEN = 90
    const val SYMBOLS = 144
    private const val FOREVER = 65535
    private val EPOCH: LocalDate = LocalDate.of(2024, 1, 1)
    private val DOMAIN = "AF-LICENCE-CODE/1".toByteArray(Charsets.US_ASCII)
    private val EDITIONS = mapOf(1 to "trial", 2 to "standard", 3 to "pro", 4 to "perpetual")

    /** Upper case; spaces, dashes, dots, underscores and tabs dropped; O is 0, I and L are 1. Null if any other character remains. */
    fun normalize(text: String): String? {
        val out = StringBuilder()
        for (ch in text.uppercase()) {
            if (ch in " -_\t\r\n.") continue
            val c = when (ch) { 'O' -> '0'; 'I', 'L' -> '1'; else -> ch }
            if (c !in ALPHABET) return null
            out.append(c)
        }
        return out.toString()
    }

    private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun productTag(productId: String): ByteArray = sha256("AF-PRODUCT/1|".toByteArray(), productId.toByteArray(Charsets.UTF_8)).copyOf(4)

    fun deviceTag(device: String): ByteArray? {
        val norm = normalize(device) ?: return null
        if (norm.length != 10) return null
        return sha256("AF-DEVICE-TAG/1|".toByteArray(), norm.toByteArray(Charsets.US_ASCII)).copyOf(6)
    }

    /** The two-byte tag of a raw public key. Null when the base64url text is not decodable. */
    fun keyTag(publicB64: String): Pair<ByteArray, ByteArray>? = try {
        val raw = Base64.getUrlDecoder().decode(publicB64.trimEnd('='))
        sha256(raw).copyOf(2) to raw
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun unBase32(symbols: String): ByteArray {
        var bits = BigInteger.ZERO
        for (c in symbols) bits = bits.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(c).toLong()))
        bits = bits.shiftRight(symbols.length * 5 - CODE_LEN * 8)
        val raw = bits.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return ByteArray(CODE_LEN - raw.size) + raw
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun bad(reason: String) = CodeResult(false, "invalid", reason)

    /** Never throws on bad input. [trustedKeys]: base64url raw Ed25519 public keys. */
    fun read(
        text: String,
        trustedKeys: List<String>,
        productId: String,
        device: String?,
        today: LocalDate,
        verifier: Ed25519Verifier = JdkEd25519,
    ): CodeResult {
        val norm = normalize(text) ?: return bad("bad_character")
        if (norm.length != SYMBOLS) return bad("wrong_length")
        val raw = unBase32(norm)
        val payload = raw.copyOfRange(0, PAYLOAD_LEN)
        val signature = raw.copyOfRange(PAYLOAD_LEN, CODE_LEN)
        if ((payload[0].toInt() and 0xff) != 1) return bad("unknown_version")
        val ptag = payload.copyOfRange(1, 5)
        val edition = payload[5].toInt() and 0xff
        val issued = u16(payload, 6)
        val first = u16(payload, 8)
        val last = u16(payload, 10)
        val grace = payload[12].toInt() and 0xff
        val dtag = payload.copyOfRange(13, 19)
        val serial = payload.copyOfRange(19, 23)
        val seats = payload[23].toInt() and 0xff
        val ktag = payload.copyOfRange(24, 26)
        // the last trusted key with a given tag wins, as in the Python dictionary
        val keys = LinkedHashMap<String, ByteArray>()
        trustedKeys.mapNotNull(::keyTag).forEach { (tag, rawKey) -> keys[tag.joinToString("") { "%02x".format(it) }] = rawKey }
        val publicRaw = keys[ktag.joinToString("") { "%02x".format(it) }] ?: return bad("unknown_key")
        if (!verifier.verify(publicRaw, DOMAIN + payload, signature)) return bad("bad_signature")
        if (!ptag.contentEquals(productTag(productId))) return bad("wrong_product")
        val name = EDITIONS[edition] ?: return bad("unknown_edition")
        val bound = !dtag.contentEquals(ByteArray(6))
        val perpetual = name == "perpetual"
        fun day(n: Int) = EPOCH.plusDays(n.toLong())
        // like the Python reader: a refusal for another device still shows the terms, with the raw last day and no days-left yet
        val shown = CodeTerms(
            name, day(issued), day(first), day(last), grace, seats,
            serial.joinToString("") { "%02X".format(it) }, bound, null,
        )
        if (bound) {
            val mine = device?.let(::deviceTag)
            if (mine == null || !mine.contentEquals(dtag)) return CodeResult(false, "invalid", "other_device", shown)
        }
        if (perpetual) {
            val terms = shown.copy(lastDay = null, daysLeft = null)
            if (!bound) return CodeResult(false, "invalid", "unbound_perpetual", terms)
            return if (today < day(first)) CodeResult(true, "not_yet_valid", "starts_later", terms) else CodeResult(true, "active", "", terms)
        }
        val terms = shown.copy(daysLeft = ChronoUnit.DAYS.between(today, day(last)) + 1)
        if (today < day(first)) return CodeResult(true, "not_yet_valid", "starts_later", terms)
        if (!today.isAfter(day(last))) return CodeResult(true, "active", "", terms)
        if (!today.isAfter(day(last).plusDays(grace.toLong()))) return CodeResult(true, "grace", "renew_soon", terms)
        return CodeResult(true, "expired", "renew_to_unlock_paid_actions", terms)
    }
}

/** What a phone can honestly say about signing, per the plan's table. Signing is also behind [OwnerBuild.REAL_ISSUANCE_ENABLED]. */
enum class HardwareSupport { UNAVAILABLE_OLD_OS, UNAVAILABLE_SOFTWARE_ONLY, CANDIDATE_TEE }

object Hardware {
    const val ED25519_FROM_API = 33

    /** [securityLevel] is what the Keystore reports for a test key: "tee", "strongbox", "software" or null when it could not be made. */
    fun classify(sdk: Int, securityLevel: String?): HardwareSupport = when {
        sdk < ED25519_FROM_API -> HardwareSupport.UNAVAILABLE_OLD_OS
        securityLevel == "tee" || securityLevel == "strongbox" -> HardwareSupport.CANDIDATE_TEE
        else -> HardwareSupport.UNAVAILABLE_SOFTWARE_ONLY
    }
}

data class GateInputs(
    val build: Boolean = OwnerBuild.REAL_ISSUANCE_ENABLED,
    val requestedByOwner: Boolean,
    val hardware: HardwareSupport,
    val productKeyTrusted: Boolean,
    val paymentEvidence: Boolean,
    val freshStrongAuth: Boolean,
    val requestReconciled: Boolean,
)

sealed class GateResult {
    data object Open : GateResult()
    data class Closed(val reasons: List<String>) : GateResult()
}

object CodesGate {
    /** Every condition must hold. The first reason is always `build_off` while the build switch is off, whatever the owner asked for. */
    fun evaluate(i: GateInputs): GateResult {
        val reasons = buildList {
            if (!i.build) add("build_off")
            if (!i.requestedByOwner) add("not_requested")
            if (i.hardware != HardwareSupport.CANDIDATE_TEE) add("hardware")
            if (!i.productKeyTrusted) add("product_not_trusting_key")
            if (!i.paymentEvidence) add("no_payment_evidence")
            if (!i.freshStrongAuth) add("no_fresh_auth")
            if (!i.requestReconciled) add("not_reconciled")
        }
        return if (reasons.isEmpty()) GateResult.Open else GateResult.Closed(reasons)
    }
}

/** Nothing can sign except through this wrapper, and it never reaches [impl] while the gate is closed. */
fun interface SignerImpl {
    fun sign(payload: ByteArray): ByteArray
}

sealed class SignOutcome {
    data class Signed(val signature: ByteArray) : SignOutcome()
    data class Refused(val reasons: List<String>) : SignOutcome()
}

class GatedSigner(private val inputs: () -> GateInputs, private val impl: SignerImpl) {
    fun sign(payload: ByteArray): SignOutcome = when (val gate = CodesGate.evaluate(inputs())) {
        is GateResult.Open -> SignOutcome.Signed(impl.sign(payload))
        is GateResult.Closed -> SignOutcome.Refused(gate.reasons)
    }
}
