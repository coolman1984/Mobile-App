package com.pixelplus.owner.audit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Device-bound AES-GCM key for the audit body.
 * The key is not exported. If the keystore cannot create it, the caller
 * keeps the safe canonical bytes unsealed and says so.
 */
internal object KeystoreAuditSealer {
    private const val ALIAS = "pixel_plus_owner_audit_v1"

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
        return AesGcmAuditSealer(key, keyAliasVersion = 2, prefix = 2)
    }
}
