package com.pixelplus.owner.audit

import android.content.Context
import com.pixelplus.owner.data.db.OwnerDatabase

/** One line of the activity list. Both explanations are plain text written by the app from safe templates. */
data class ActivityLine(val time: String, val screen: String, val result: String, val arabic: String, val english: String)

data class ActivitySnapshot(
    val ok: Boolean,
    val count: Int,
    /** Newest first, at most [MAX_LINES]. */
    val lines: List<ActivityLine>,
    val markdown: String,
    val json: ByteArray,
    val head: AuditHeadSummary?,
)

data class AuditHeadSummary(val chainId: String, val sequence: Long, val entryHash: String)

object ActivityReader {
    const val MAX_LINES = 100

    /** Reads and verifies the whole local chain. Call off the main thread. Null when the notebook cannot be opened. */
    fun read(context: Context): ActivitySnapshot? {
        val database = runCatching { OwnerDatabase.open(context.applicationContext) }.getOrNull() ?: return null
        try {
            val dao = database.auditDao()
            val chain = dao.chain() ?: return ActivitySnapshot(true, 0, emptyList(), AuditExport.markdown(emptyList(), AuditCanonical.verify(emptyList())), AuditExport.json(emptyList(), AuditCanonical.verify(emptyList())).toByteArray(Charsets.UTF_8), null)
            val sealer = database.auditRuntime.sealer
            val rows = dao.entries(chain.chainId)
            val links = rows.map { row ->
                val opened = runCatching {
                    check(row.keyAliasVersion == sealer.keyAliasVersion)
                    sealer.open(row.canonicalCiphertext)
                }.getOrDefault(ByteArray(0))
                ChainLink(row.previousHash, row.entryHash, opened)
            }
            val report = AuditCanonical.verify(links)
            val lines = rows.asReversed().take(MAX_LINES).map {
                ActivityLine(it.cairoLocalIso, it.screen, it.result, it.explanationAr, it.explanationEn)
            }
            val last = rows.lastOrNull()
            return ActivitySnapshot(
                ok = report.ok,
                count = rows.size,
                lines = lines,
                markdown = AuditExport.markdown(links, report),
                json = AuditExport.json(links, report).toByteArray(Charsets.UTF_8),
                head = last?.let { AuditHeadSummary(chain.chainId, it.sequence, it.entryHash) },
            )
        } finally {
            database.close()
        }
    }
}
