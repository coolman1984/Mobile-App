package com.pixelplus.owner.ui

import android.content.Context
import com.pixelplus.owner.audit.AuditVocab
import com.pixelplus.owner.audit.JsonValue
import com.pixelplus.owner.data.db.AuditSpec
import com.pixelplus.owner.data.db.AuditWriter
import com.pixelplus.owner.data.db.OwnerDatabase

/** Records a language change in the audit chain (safe values only). */
object LocaleAudit {
    fun logChange(context: Context, before: AppLanguage, after: AppLanguage) {
        if (before == after) return
        Thread {
            runCatching {
                val db = OwnerDatabase.open(context.applicationContext)
                try {
                    val workspace = db.openHelper.readableDatabase.query(
                        "SELECT id FROM workspaces ORDER BY createdAtUtcMs LIMIT 1",
                    ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return
                    val now = System.currentTimeMillis()
                    db.runInTransaction {
                        AuditWriter(db).append(
                            AuditSpec(
                                screen = AuditVocab.SCREEN_SETTINGS,
                                action = AuditVocab.ACTION_UPDATE,
                                entityType = AuditVocab.ENTITY_WORKSPACE,
                                entityId = workspace,
                                workspaceId = workspace,
                                hubProfileId = null,
                                nowUtcMs = now,
                                before = mapOf("locale" to JsonValue.Text(before.storageValue)),
                                after = mapOf("locale" to JsonValue.Text(after.storageValue)),
                                result = AuditVocab.RESULT_SUCCESS,
                                reasonCode = "locale",
                            ),
                        )
                    }
                } finally {
                    db.close()
                }
            }
        }.start()
    }
}
