package com.pixelplus.owner.audit

import android.content.Context
import java.util.UUID

/** New ids for audit events. A UUID here is not a hardware identifier. */
fun interface IdSource {
    fun next(): String
}

object RandomIdSource : IdSource {
    override fun next(): String = UUID.randomUUID().toString()
}

/**
 * How this open database seals audit bodies and mints ids.
 * [sealedAtRest] is false when the keystore could not provide a key.
 */
class AuditRuntime(
    val ids: IdSource,
    val sealer: AuditSealer,
) {
    val sealedAtRest: Boolean get() = sealer.keyAliasVersion > 0

    companion object {
        fun resolve(context: Context, ids: IdSource = RandomIdSource): AuditRuntime {
            val sealer = runCatching {
                val candidate = KeystoreAuditSealer.open()
                val probe = byteArrayOf(1, 2, 3)
                val sealed = candidate.seal(probe)
                check(candidate.open(sealed).contentEquals(probe))
                candidate
            }.getOrNull() ?: PlainAuditSealer
            return AuditRuntime(ids, sealer)
        }
    }
}
