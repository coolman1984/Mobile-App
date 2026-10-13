package com.pixelplus.owner.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pixelplus.owner.AlertCard
import com.pixelplus.owner.RequestCard
import com.pixelplus.owner.audit.AuditMirrorRunner
import com.pixelplus.owner.backup.AndroidBackup
import com.pixelplus.owner.backup.AndroidRestore
import com.pixelplus.owner.data.BookResult
import com.pixelplus.owner.formatEpoch
import com.pixelplus.owner.sync.BothLines
import java.util.Locale

/** String resource name plus format args; resolved with the current locale at display time. */
data class TextKey(val key: String, val args: Array<out Any> = emptyArray()) {
    companion object {
        fun of(key: String, vararg args: Any): TextKey = TextKey(key, arrayOf(*args))
    }
}

fun Context.resolve(key: TextKey): String = text(key.key, *key.args)

fun BookResult.Refused.message(context: Context): String = context.resolve(reason)

fun Context.refused(key: String, vararg args: Any): BookResult.Refused = BookResult.Refused(TextKey.of(key, *args))

/** Localized string by resource name (same keys as `values/strings.xml`). */
fun Context.text(key: String, vararg args: Any): String {
    val id = resources.getIdentifier(key, "string", packageName)
    if (id == 0) return key
    return if (args.isEmpty()) getString(id) else getString(id, *args)
}

fun Context.pluralText(key: String, count: Int, vararg args: Any): String {
    val id = resources.getIdentifier(key, "plurals", packageName)
    if (id == 0) return key
    return if (args.isEmpty()) resources.getQuantityString(id, count, count) else resources.getQuantityString(id, count, *args)
}

/** Arabic and English lines for the same string key (both folders use the same name). */
fun Context.bothLines(key: String, vararg args: Any): BothLines {
    fun load(locale: Locale): String {
        val cfg = Configuration(resources.configuration).apply { setLocale(locale) }
        val ctx = createConfigurationContext(cfg)
        return ctx.text(key, *args)
    }
    return BothLines(load(Locale("ar")), load(Locale.ENGLISH))
}

@Composable
fun labelOf(key: String, vararg formatArgs: Any): String {
    val context = LocalContext.current
    return remember(key, formatArgs.contentHashCode(), context.resources.configuration) {
        context.text(key, *formatArgs)
    }
}

@Composable
fun pluralOf(key: String, count: Int, vararg formatArgs: Any): String {
    val context = LocalContext.current
    return remember(key, count, formatArgs.contentHashCode(), context.resources.configuration) {
        context.pluralText(key, count, *formatArgs)
    }
}

@StringRes
fun Context.stringId(key: String): Int = resources.getIdentifier(key, "string", packageName)

@PluralsRes
fun Context.pluralId(key: String): Int = resources.getIdentifier(key, "plurals", packageName)

fun Context.localizedDemoRequestStatus(status: String): String {
    val key = when (status) {
        "PENDING" -> "demo_status_pending"
        "REVIEWED" -> "demo_status_reviewed"
        "REJECTED" -> "demo_status_rejected"
        else -> return status
    }
    return text(key)
}

fun Context.localizedLicenceKind(kind: String): String {
    val key = when (kind) {
        "trial" -> "licence_kind_trial"
        "monthly" -> "licence_kind_monthly"
        "permanent" -> "licence_kind_permanent"
        else -> return kind
    }
    return text(key)
}

fun Context.localizedOwnerDecision(decision: String?): String {
    val key = when (decision) {
        null, "" -> "licence_decision_pending"
        "approved" -> "licence_decision_approved"
        "expired" -> "licence_decision_expired"
        "denied" -> "licence_decision_denied"
        else -> return decision
    }
    return text(key)
}

fun Context.localizedHubEvent(event: String): String {
    val key = when (event) {
        "created" -> "hub_event_created"
        "approved" -> "hub_event_approved"
        "refused" -> "hub_event_refused"
        "issued" -> "hub_event_issued"
        "delivered" -> "hub_event_delivered"
        "reissued" -> "hub_event_reissued"
        "tg_refused" -> "hub_event_tg_refused"
        "tg_stale" -> "hub_event_tg_stale"
        else -> return event
    }
    return text(key)
}

fun Context.mirrorResultMessage(result: AuditMirrorRunner.Result): String = when (result) {
    is AuditMirrorRunner.Result.Sent ->
        text("mirror_sent", result.count) + if (result.upToDate) "" else text("mirror_sent_more")
    AuditMirrorRunner.Result.UpToDate -> text("mirror_up_to_date")
    AuditMirrorRunner.Result.NotAvailable -> text("mirror_not_available")
    AuditMirrorRunner.Result.NothingToSend -> text("mirror_nothing")
    is AuditMirrorRunner.Result.Blocked -> resolve(result.reason)
}

fun Context.backupResultMessage(result: AndroidBackup.Result): String = when (result) {
    is AndroidBackup.Result.Done -> text("backup_saved", result.bytes / 1024)
    is AndroidBackup.Result.Failed -> resolve(result.reason)
}

fun Context.restoreAppliedMessage(result: AndroidRestore.Applied): String = when (result) {
    is AndroidRestore.Applied.Done -> text("restore_done", result.quarantined)
    is AndroidRestore.Applied.Failed -> resolve(result.reason)
}

fun Context.liveRequestDetail(card: RequestCard): String {
    require(!card.demo)
    return buildString {
        append(text("request_detail_device", card.device))
        append('\n').append(text("request_detail_shop", card.shop.ifBlank { "—" }))
        if (card.version.isNotBlank()) append('\n').append(text("request_detail_version", card.version))
        append('\n').append(text("request_detail_time", formatEpoch(card.createdAtEpoch)))
    }
}

fun Context.hubAlertDetail(card: AlertCard): String {
    require(!card.demo)
    return buildString {
        append(formatEpoch(card.eventAtEpoch))
        if (!card.requestId.isNullOrBlank()) append('\n').append(text("request_detail_request_id", card.requestId.take(8)))
        if (card.extraDetail.isNotBlank()) append('\n').append(card.extraDetail)
    }
}
