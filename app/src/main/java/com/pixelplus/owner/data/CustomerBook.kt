package com.pixelplus.owner.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.pixelplus.owner.audit.AesGcmAuditSealer
import com.pixelplus.owner.audit.AuditSealer
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.RecordResult
import com.pixelplus.owner.domain.ArabicSearch
import com.pixelplus.owner.ui.refused
import com.pixelplus.owner.ui.text
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The owner's private customer book (M11, first slice): names and phones in the local notebook, sealed with their own device-bound key.
 * Every change is audited and can be undone through the notebook's own rules. Nothing here is sent to the hub, and no name or phone
 * ever enters the audit chain. If the key cannot do a round trip nothing is stored (no plaintext fallback).
 *
 * Not in this slice: notes, follow-up reminders, branches, links to hub installs (they need new tables).
 */
data class CustomerRow(val id: String, val name: String, val phone: String?, val hidden: Boolean)

data class CustomerList(val active: List<CustomerRow>, val hidden: List<CustomerRow>)

sealed class BookResult {
    data object Ok : BookResult()
    data class Refused(val reason: com.pixelplus.owner.ui.TextKey) : BookResult()
}

internal object ContactSeal {
    private const val ALIAS = "pixel_plus_owner_contacts_v1"

    fun resolve(): AuditSealer? = runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
        val sealer = AesGcmAuditSealer(keyStore.getKey(ALIAS, null) as SecretKey, keyAliasVersion = 4, prefix = 4)
        val probe = byteArrayOf(5, 6, 7)
        check(sealer.open(sealer.seal(probe)).contentEquals(probe))
        sealer
    }.getOrNull()
}

object CustomerBook {
    private val PHONE = Regex("^\\+?[0-9]{5,15}$")
    /** Digits only, Arabic-Indic digits accepted. Null for text that is not a phone number. */
    fun cleanPhone(raw: String): String? {
        val s = ArabicSearch.normalize(raw).replace(" ", "").replace("-", "")
        return s.takeIf { PHONE.matches(it) }
    }

    internal fun workspaceId(app: Context): String {
        val sp = app.getSharedPreferences("owner_prefs", Context.MODE_PRIVATE)
        return sp.getString("workspace_id", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("workspace_id", it).apply() }
    }

    internal fun <T> withBook(context: Context, block: (OwnerDatabase, String) -> T): T? {
        val app = context.applicationContext
        val database = runCatching { OwnerDatabase.open(app) }.getOrNull() ?: return null
        try {
            val id = workspaceId(app)
            if (database.ownerDao().workspace(id) == null) {
                database.records().createWorkspace(id, "Pixel Plus Owner", System.currentTimeMillis())
            }
            return block(database, id)
        } finally {
            database.close()
        }
    }

    fun load(context: Context): CustomerList? {
        val app = context.applicationContext
        val sealer = ContactSeal.resolve() ?: return null
        return withBook(context) { db, workspace ->
            fun row(e: com.pixelplus.owner.data.db.CustomerEntity, hidden: Boolean) = CustomerRow(
                id = e.id,
                name = runCatching { String(sealer.open(e.encryptedDisplayName), Charsets.UTF_8) }.getOrDefault(app.text("unknown_value")),
                phone = e.encryptedPhone?.let { runCatching { String(sealer.open(it), Charsets.UTF_8) }.getOrNull() },
                hidden = hidden,
            )
            val dao = db.ownerDao()
            CustomerList(dao.activeCustomers(workspace).map { row(it, false) }, dao.hiddenCustomers(workspace).map { row(it, true) })
        }
    }

    fun add(context: Context, rawName: String, rawPhone: String): BookResult {
        val app = context.applicationContext
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 80 || name.any { it.isISOControl() }) return app.refused("book_name_invalid")
        val phone = if (rawPhone.isBlank()) null else (cleanPhone(rawPhone) ?: return app.refused("book_phone_invalid"))
        val sealer = ContactSeal.resolve() ?: return app.refused("book_no_key")
        val result = withBook(context) { db, workspace ->
            db.records().createCustomer(
                id = UUID.randomUUID().toString(), workspaceId = workspace,
                encryptedDisplayName = sealer.seal(name.toByteArray(Charsets.UTF_8)),
                nowUtcMs = System.currentTimeMillis(),
                encryptedPhone = phone?.let { sealer.seal(it.toByteArray(Charsets.UTF_8)) },
            )
        }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_customer_save_failed")
    }

    fun hide(context: Context, id: String): BookResult = change(context) { db -> db.records().softDeleteCustomer(id, System.currentTimeMillis()) }

    fun restore(context: Context, id: String): BookResult = change(context) { db -> db.records().restoreCustomer(id, System.currentTimeMillis()) }

    private fun change(context: Context, op: (OwnerDatabase) -> RecordResult): BookResult {
        val app = context.applicationContext
        val result = withBook(context) { db, _ -> op(db) }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_no_change")
    }

    data class NoteRow(val id: String, val text: String, val updatedAtUtcMs: Long)

    fun notes(context: Context, customerId: String): List<NoteRow>? {
        val sealer = ContactSeal.resolve() ?: return null
        return withBook(context) { db, _ ->
            db.ownerDao().activeNotes(customerId).map {
                NoteRow(it.id, runCatching { String(sealer.open(it.encryptedBody), Charsets.UTF_8) }.getOrDefault("؟"), it.updatedAtUtcMs)
            }
        }
    }

    fun addNote(context: Context, customerId: String, rawText: String): BookResult {
        val text = rawText.trim()
        val app = context.applicationContext
        if (text.isEmpty() || text.length > 2000) return app.refused("book_note_invalid")
        val sealer = ContactSeal.resolve() ?: return app.refused("book_no_key")
        val result = withBook(context) { db, workspace ->
            db.records().createNote(UUID.randomUUID().toString(), workspace, customerId, sealer.seal(text.toByteArray(Charsets.UTF_8)), System.currentTimeMillis())
        }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_note_save_failed")
    }

    fun hideNote(context: Context, id: String): BookResult = change(context) { db -> db.records().softDeleteNote(id, System.currentTimeMillis()) }
}
