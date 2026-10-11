package com.pixelplus.owner.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The default backup key: non-exportable AES-256-GCM in the Android Keystore, separate from the audit and outbox keys.
 * It cannot recover a backup after phone loss, an uninstall or on another phone; the screen that offers a backup must say so.
 * If the key cannot do a round trip, [resolve] returns null and no backup is made (never a plaintext fallback).
 *
 * UNVERIFIED on a handset in this milestone: it follows `KeystoreOutboxSealer` exactly and is not called by any screen yet.
 */
internal object KeystoreBackupKey {
    private const val ALIAS = "pixel_plus_owner_backup_v1"

    fun resolve(): BackupSealer? = runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
        val sealer = AesGcmBackupSealer(keyStore.getKey(ALIAS, null) as SecretKey)
        val probe = byteArrayOf(1, 2, 3)
        val aad = byteArrayOf(4)
        check(sealer.open(aad, sealer.seal(aad, probe)).contentEquals(probe))
        sealer
    }.getOrNull()
}
