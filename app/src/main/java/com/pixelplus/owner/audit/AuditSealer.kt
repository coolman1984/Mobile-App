package com.pixelplus.owner.audit

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps the canonical bytes before they are stored.
 *
 * Version 0 keeps the safe text behind a marker byte. It is used only when
 * the Android Keystore cannot create a key. Version 1 and 2 are AES-GCM
 * with a random 12-byte nonce. This file does not invent a cipher.
 */
interface AuditSealer {
    val keyAliasVersion: Int
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

object PlainAuditSealer : AuditSealer {
    override val keyAliasVersion: Int = 0

    override fun seal(plain: ByteArray): ByteArray = byteArrayOf(PREFIX) + plain

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty() && sealed[0] == PREFIX) { "audit seal" }
        return sealed.copyOfRange(1, sealed.size)
    }

    private const val PREFIX: Byte = 0
}

class AesGcmAuditSealer(
    private val key: SecretKey,
    override val keyAliasVersion: Int,
    private val prefix: Byte,
) : AuditSealer {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        require(nonce.size == NONCE_BYTES) { "audit nonce" }
        val body = cipher.doFinal(plain)
        return byteArrayOf(prefix) + nonce + body
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + NONCE_BYTES && sealed[0] == prefix) { "audit seal" }
        val nonce = sealed.copyOfRange(1, 1 + NONCE_BYTES)
        val body = sealed.copyOfRange(1 + NONCE_BYTES, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(body)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun fromRaw(raw: ByteArray): AesGcmAuditSealer {
            require(raw.size == 32) { "audit key" }
            return AesGcmAuditSealer(SecretKeySpec(raw, "AES"), keyAliasVersion = 1, prefix = 1)
        }

        /** A fresh test key. Not stored, and not used by the phone. */
        fun ephemeral(): AesGcmAuditSealer {
            val raw = ByteArray(32)
            SecureRandom().nextBytes(raw)
            return fromRaw(raw)
        }
    }
}
