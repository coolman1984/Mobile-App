package com.pixelplus.owner.audit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A separate device-bound key for outbox payloads.
 * It is not the audit key and it is not exported.
 * If this key cannot be opened, queue writes fail closed.
 * The token is never a column, and this key does not wrap one.
 */
internal object KeystoreOutboxSealer {
    private const val ALIAS = "pixel_plus_owner_outbox_v1"

    fun open(): AuditSealer {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
        val key = keyStore.getKey(ALIAS, null) as SecretKey
        return AesGcmAuditSealer(key, keyAliasVersion = 3, prefix = 3)
    }
}

internal object OutboxSeal {
    fun resolve(): AuditSealer {
        val own = runCatching {
            val candidate = KeystoreOutboxSealer.open()
            val probe = byteArrayOf(9, 8, 7)
            val sealed = candidate.seal(probe)
            check(candidate.open(sealed).contentEquals(probe))
            candidate
        }.getOrNull()
        if (own != null) return own
        // Never switch an existing payload to a different key after a Keystore failure.
        return UnavailableOutboxSealer
    }
}

internal object UnavailableOutboxSealer : AuditSealer {
    override val keyAliasVersion = -1
    override fun seal(plain: ByteArray): ByteArray = error("outbox_key_unavailable")
    override fun open(sealed: ByteArray): ByteArray = error("outbox_key_unavailable")
}
