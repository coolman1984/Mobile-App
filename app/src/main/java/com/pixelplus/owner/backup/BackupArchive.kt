package com.pixelplus.owner.backup

import com.pixelplus.owner.audit.CanonicalJson
import com.pixelplus.owner.audit.JsonValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted backup archive (M06). No Android types.
 *
 * Layout: `PPOB` | format byte | manifest length (4, big endian) | manifest | nonce (12) | AES-256-GCM(payload).
 * The manifest is canonical JSON, readable without the key and authenticated: the magic, the format byte and the manifest bytes are the
 * GCM associated data, so changing any of them makes the open fail. The payload holds the named files (the database copy and the
 * logical JSON export); each file's size and SHA-256 are in the manifest and are checked after the open. The manifest holds sizes, hashes,
 * versions, audit chain heads and hub profile ids. Never a token, a key or a licence code.
 *
 * Default key: the non-exportable device key (see [KeystoreBackupKey]). It cannot recover after phone loss or an uninstall. Portable
 * recovery with an owner-held secret is **NOT IMPLEMENTED**.
 *
 * Streaming is NOT IMPLEMENTED: the archive is built in memory and refused above [BackupLimits.MAX_PAYLOAD_BYTES].
 */
object BackupLimits {
    const val FORMAT_VERSION: Int = 1
    const val MAX_MANIFEST_BYTES: Int = 64 * 1024
    const val MAX_PAYLOAD_BYTES: Int = 64 * 1024 * 1024
    const val MAX_FILES: Int = 8
    const val MAX_NAME_BYTES: Int = 64
    const val NONCE_BYTES: Int = 12
    const val TAG_BITS: Int = 128
    val MAGIC: ByteArray = byteArrayOf('P'.code.toByte(), 'P'.code.toByte(), 'O'.code.toByte(), 'B'.code.toByte())
    const val DATABASE: String = "database.db"
    const val LOGICAL: String = "export.json"
}

data class FileEntry(val size: Long, val sha256: String)

data class AuditHeadEntry(val sequence: Long, val entryHash: String)

data class BackupManifest(
    val formatVersion: Int,
    val snapshotId: String,
    val createdAtUtcMs: Long,
    val dbSchemaVersion: Int,
    val appVersion: String,
    /** "device" is the only mode implemented. */
    val keyMode: String,
    val files: Map<String, FileEntry>,
    val auditHeads: Map<String, AuditHeadEntry>,
    val hubProfileIds: Set<String>,
) {
    fun toJson(): JsonValue.Obj = JsonValue.Obj(
        mapOf(
            "formatVersion" to JsonValue.Int64(formatVersion.toLong()),
            "snapshotId" to JsonValue.Text(snapshotId),
            "createdAtUtcMs" to JsonValue.Int64(createdAtUtcMs),
            "dbSchemaVersion" to JsonValue.Int64(dbSchemaVersion.toLong()),
            "appVersion" to JsonValue.Text(appVersion),
            "keyMode" to JsonValue.Text(keyMode),
            "files" to JsonValue.Obj(files.mapValues { (_, f) ->
                JsonValue.Obj(mapOf("size" to JsonValue.Int64(f.size), "sha256" to JsonValue.Text(f.sha256)))
            }),
            "auditHeads" to JsonValue.Obj(auditHeads.mapValues { (_, h) ->
                JsonValue.Obj(mapOf("sequence" to JsonValue.Int64(h.sequence), "entryHash" to JsonValue.Text(h.entryHash)))
            }),
            "hubProfileIds" to JsonValue.Obj(hubProfileIds.associateWith { JsonValue.Bool(true) }),
        ),
    )

    fun bytes(): ByteArray = CanonicalJson.bytes(toJson())

    companion object {
        /** Null when the bytes are not a well-formed manifest. */
        fun parse(bytes: ByteArray): BackupManifest? = runCatching {
            val root = (CanonicalJson.parse(bytes) as JsonValue.Obj).fields
            if (!CanonicalJson.bytes(JsonValue.Obj(root)).contentEquals(bytes)) return null
            fun obj(v: JsonValue?) = (v as JsonValue.Obj).fields
            fun text(v: JsonValue?) = (v as JsonValue.Text).value
            fun int(v: JsonValue?) = (v as JsonValue.Int64).value
            BackupManifest(
                formatVersion = int(root["formatVersion"]).toInt(),
                snapshotId = text(root["snapshotId"]),
                createdAtUtcMs = int(root["createdAtUtcMs"]),
                dbSchemaVersion = int(root["dbSchemaVersion"]).toInt(),
                appVersion = text(root["appVersion"]),
                keyMode = text(root["keyMode"]),
                files = obj(root["files"]).mapValues { (_, v) -> obj(v).let { FileEntry(int(it["size"]), text(it["sha256"])) } },
                auditHeads = obj(root["auditHeads"]).mapValues { (_, v) -> obj(v).let { AuditHeadEntry(int(it["sequence"]), text(it["entryHash"])) } },
                hubProfileIds = obj(root["hubProfileIds"]).keys,
            )
        }.getOrNull()
    }
}

/** Seals with a key that never leaves its holder. The nonce is random per call and travels in front of the ciphertext. */
interface BackupSealer {
    fun seal(aad: ByteArray, plain: ByteArray): ByteArray
    fun open(aad: ByteArray, sealed: ByteArray): ByteArray
}

class AesGcmBackupSealer(private val key: SecretKey) : BackupSealer {
    override fun seal(aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)   // the provider picks the nonce; a Keystore key requires it
        cipher.updateAAD(aad)
        val nonce = cipher.iv
        require(nonce.size == BackupLimits.NONCE_BYTES) { "backup nonce" }
        return nonce + cipher.doFinal(plain)
    }

    override fun open(aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > BackupLimits.NONCE_BYTES) { "backup seal" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BackupLimits.TAG_BITS, sealed.copyOfRange(0, BackupLimits.NONCE_BYTES)))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, BackupLimits.NONCE_BYTES, sealed.size - BackupLimits.NONCE_BYTES)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun fromRaw(raw: ByteArray): AesGcmBackupSealer {
            require(raw.size == 32) { "backup key" }
            return AesGcmBackupSealer(SecretKeySpec(raw, "AES"))
        }

        /** A fresh test key. Not stored. */
        fun ephemeral(): AesGcmBackupSealer = fromRaw(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

sealed class ArchiveResult {
    data class Built(val bytes: ByteArray) : ArchiveResult()
    data class Refused(val reason: BackupFault) : ArchiveResult()
}

enum class BackupFault {
    NOT_AN_ARCHIVE,
    UNSUPPORTED_FORMAT,
    TOO_LARGE,
    WRONG_KEY_OR_TAMPERED,
    BAD_MANIFEST,
    MISSING_FILE,
    CORRUPT_FILE,
    NEWER_SCHEMA,
    PLAINTEXT_LEAK,
}

object BackupArchive {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Builds the manifest from the files themselves, so sizes and hashes cannot disagree with the payload. */
    fun build(
        snapshotId: String,
        createdAtUtcMs: Long,
        dbSchemaVersion: Int,
        appVersion: String,
        auditHeads: Map<String, AuditHeadEntry>,
        hubProfileIds: Set<String>,
        files: Map<String, ByteArray>,
        sealer: BackupSealer,
    ): ArchiveResult {
        if (files.isEmpty() || files.size > BackupLimits.MAX_FILES) return ArchiveResult.Refused(BackupFault.TOO_LARGE)
        if (files.keys.any { !validName(it) }) return ArchiveResult.Refused(BackupFault.BAD_MANIFEST)
        val total = files.values.sumOf { it.size.toLong() }
        if (total > BackupLimits.MAX_PAYLOAD_BYTES) return ArchiveResult.Refused(BackupFault.TOO_LARGE)
        val manifest = BackupManifest(
            formatVersion = BackupLimits.FORMAT_VERSION, snapshotId = snapshotId, createdAtUtcMs = createdAtUtcMs,
            dbSchemaVersion = dbSchemaVersion, appVersion = appVersion, keyMode = "device",
            files = files.toSortedMap().mapValues { (_, b) -> FileEntry(b.size.toLong(), sha256(b)) },
            auditHeads = auditHeads, hubProfileIds = hubProfileIds,
        )
        val manifestBytes = manifest.bytes()
        if (manifestBytes.size > BackupLimits.MAX_MANIFEST_BYTES) return ArchiveResult.Refused(BackupFault.TOO_LARGE)
        val payload = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { d ->
                d.writeInt(files.size)
                files.toSortedMap().forEach { (name, bytes) ->
                    val n = name.toByteArray(Charsets.UTF_8)
                    d.writeShort(n.size); d.write(n); d.writeInt(bytes.size); d.write(bytes)
                }
            }
        }.toByteArray()
        val head = aadFor(manifestBytes)
        val sealed = sealer.seal(head, payload)
        val out = ByteArrayOutputStream()
        out.write(head); out.write(sealed)
        val archive = out.toByteArray()
        // the plain database must never be readable in the output: the SQLite magic is the cheap tell
        if (files.values.any { it.size >= 16 && indexOf(archive, it.copyOfRange(0, 16)) >= 0 }) return ArchiveResult.Refused(BackupFault.PLAINTEXT_LEAK)
        return ArchiveResult.Built(archive)
    }

    /** Reads only the plain header. Does not need the key; used to choose a file and to show its date and versions. */
    fun peek(archive: ByteArray): Pair<BackupManifest?, BackupFault?> {
        val parsed = header(archive) ?: return null to BackupFault.NOT_AN_ARCHIVE
        if (parsed.format != BackupLimits.FORMAT_VERSION) return null to BackupFault.UNSUPPORTED_FORMAT
        val manifest = BackupManifest.parse(parsed.manifest) ?: return null to BackupFault.BAD_MANIFEST
        if (manifest.formatVersion != parsed.format) return null to BackupFault.BAD_MANIFEST
        return manifest to null
    }

    /** Opens and verifies everything: key, authenticated header, file names, sizes and hashes. Nothing is written anywhere. */
    fun open(archive: ByteArray, sealer: BackupSealer): OpenResult {
        val (manifest, fault) = peek(archive)
        if (manifest == null) return OpenResult.Refused(fault ?: BackupFault.NOT_AN_ARCHIVE)
        val parsed = header(archive)!!
        val plain = try {
            sealer.open(archive.copyOfRange(0, parsed.bodyStart), archive.copyOfRange(parsed.bodyStart, archive.size))
        } catch (e: Exception) {
            return OpenResult.Refused(BackupFault.WRONG_KEY_OR_TAMPERED)   // one answer for a wrong key and a changed byte
        }
        if (plain.size > BackupLimits.MAX_PAYLOAD_BYTES + 4096) return OpenResult.Refused(BackupFault.TOO_LARGE)
        val files = try { readFiles(plain) } catch (e: Exception) { return OpenResult.Refused(BackupFault.CORRUPT_FILE) }
        if (files.keys != manifest.files.keys) {
            return OpenResult.Refused(if (manifest.files.keys.any { it !in files }) BackupFault.MISSING_FILE else BackupFault.CORRUPT_FILE)
        }
        for ((name, entry) in manifest.files) {
            val bytes = files.getValue(name)
            if (bytes.size.toLong() != entry.size || sha256(bytes) != entry.sha256) return OpenResult.Refused(BackupFault.CORRUPT_FILE)
        }
        return OpenResult.Opened(manifest, files)
    }

    private class Header(val format: Int, val manifest: ByteArray, val bodyStart: Int)

    private fun aadFor(manifest: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(BackupLimits.MAGIC); out.write(BackupLimits.FORMAT_VERSION)
        out.write(byteArrayOf((manifest.size ushr 24).toByte(), (manifest.size ushr 16).toByte(), (manifest.size ushr 8).toByte(), manifest.size.toByte()))
        out.write(manifest)
        return out.toByteArray()
    }

    private fun header(archive: ByteArray): Header? {
        if (archive.size < 9 + BackupLimits.NONCE_BYTES || !archive.copyOfRange(0, 4).contentEquals(BackupLimits.MAGIC)) return null
        val format = archive[4].toInt() and 0xff
        val length = ((archive[5].toInt() and 0xff) shl 24) or ((archive[6].toInt() and 0xff) shl 16) or
            ((archive[7].toInt() and 0xff) shl 8) or (archive[8].toInt() and 0xff)
        if (length <= 0 || length > BackupLimits.MAX_MANIFEST_BYTES || 9 + length + BackupLimits.NONCE_BYTES >= archive.size) return null
        return Header(format, archive.copyOfRange(9, 9 + length), 9 + length)
    }

    private fun readFiles(plain: ByteArray): Map<String, ByteArray> {
        val d = DataInputStream(ByteArrayInputStream(plain))
        val count = d.readInt()
        if (count !in 1..BackupLimits.MAX_FILES) throw EOFException("count")
        val out = linkedMapOf<String, ByteArray>()
        repeat(count) {
            val nameLen = d.readUnsignedShort()
            if (nameLen !in 1..BackupLimits.MAX_NAME_BYTES) throw EOFException("name")
            val name = String(ByteArray(nameLen).also { d.readFully(it) }, Charsets.UTF_8)
            val size = d.readInt()
            if (size < 0 || size > d.available()) throw EOFException("size")
            if (!validName(name) || name in out) throw EOFException("name")
            out[name] = ByteArray(size).also { d.readFully(it) }
        }
        if (d.available() != 0) throw EOFException("trailing")
        return out
    }

    private fun validName(name: String) = Regex("^[a-z0-9][a-z0-9._-]{0,63}$").matches(name) && ".." !in name

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

sealed class OpenResult {
    data class Opened(val manifest: BackupManifest, val files: Map<String, ByteArray>) : OpenResult()
    data class Refused(val reason: BackupFault) : OpenResult()
}
