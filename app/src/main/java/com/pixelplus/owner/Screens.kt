package com.pixelplus.owner

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pixelplus.owner.audit.ActivityReader
import com.pixelplus.owner.audit.HistoryRow
import com.pixelplus.owner.audit.MirrorResult
import com.pixelplus.owner.audit.OwnerReadClient
import com.pixelplus.owner.audit.ActivitySnapshot
import com.pixelplus.owner.backup.AndroidBackup
import com.pixelplus.owner.backup.AndroidRestore
import com.pixelplus.owner.data.BookResult
import com.pixelplus.owner.data.CustomerBook
import com.pixelplus.owner.data.CustomerList
import com.pixelplus.owner.data.FollowUpBook
import com.pixelplus.owner.data.FollowUpItem
import com.pixelplus.owner.data.MoneyBook
import com.pixelplus.owner.data.MoneyOverview
import com.pixelplus.owner.domain.Money
import com.pixelplus.owner.domain.KpiSummary
import com.pixelplus.owner.domain.RequestTab
import com.pixelplus.owner.security.LockTimeout
import com.pixelplus.owner.ui.theme.Appearance
import com.pixelplus.owner.ui.AppLanguage
import com.pixelplus.owner.ui.backupResultMessage
import com.pixelplus.owner.ui.labelOf
import com.pixelplus.owner.ui.localizedLicenceKind
import com.pixelplus.owner.ui.localizedOwnerDecision
import com.pixelplus.owner.ui.message
import com.pixelplus.owner.ui.mirrorResultMessage
import com.pixelplus.owner.ui.pluralOf
import com.pixelplus.owner.ui.restoreAppliedMessage
import com.pixelplus.owner.ui.text
import com.pixelplus.owner.ui.TextKey
import com.pixelplus.owner.ui.theme.AppearancePrefs
import com.pixelplus.owner.ui.theme.LockPrefs
import com.pixelplus.owner.ui.theme.Palettes
import com.pixelplus.owner.ui.theme.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Screens for M08 to M10 and the activity / backup pages. Decisions come from pure, tested code; these only draw them and call Android. */

@Composable
internal fun LanguageScreen(current: AppLanguage, onPick: (AppLanguage) -> Unit) {
    Text(labelOf("language_intro"))
    listOf(
        AppLanguage.ARABIC to "language_arabic",
        AppLanguage.ENGLISH to "language_english",
        AppLanguage.FOLLOW_SYSTEM to "language_follow_system",
    ).forEach { (lang, key) ->
        OutlinedButton(onClick = { onPick(lang) }, modifier = Modifier.fillMaxWidth()) {
            Text((if (current == lang) labelOf("selected_prefix") else "") + labelOf(key))
        }
    }
}

@Composable
internal fun LockScreen(secure: Boolean, onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(labelOf("lock_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(labelOf("lock_subtitle_en"))
                Text(labelOf(if (secure) "lock_secure_hint" else "lock_no_secure_hint"))
                if (secure) Button(onClick = onUnlock) { Text(labelOf("unlock_bilingual")) }
            }
        }
    }
}

@Composable
internal fun AppearanceScreen(current: AppearancePrefs, onChange: (AppearancePrefs) -> Unit) {
    var scale by remember(current.appFontScale) { mutableStateOf(current.appFontScale) }
    Text(labelOf("appearance_intro"))
    Text(labelOf("theme_mode"), fontWeight = FontWeight.Bold)
    listOf(ThemeMode.SYSTEM to "theme_system", ThemeMode.LIGHT to "theme_light", ThemeMode.DARK to "theme_dark").forEach { (mode, key) ->
        OutlinedButton(onClick = { onChange(current.copy(mode = mode)) }, modifier = Modifier.fillMaxWidth()) {
            Text((if (current.mode == mode) labelOf("selected_prefix") else "") + labelOf(key))
        }
    }
    Text(labelOf("palette"), fontWeight = FontWeight.Bold)
    Palettes.all.forEach { palette ->
        OutlinedButton(onClick = { onChange(current.copy(paletteId = palette.id)) }, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Color(palette.light.primary)))
                Box(Modifier.size(18.dp).background(Color(palette.dark.primary)))
                Text((if (current.paletteId == palette.id) labelOf("selected_prefix") else "") + palette.id)
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 31) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(labelOf("dynamic_colour"))
            Switch(checked = current.dynamicColour, onCheckedChange = { onChange(current.copy(dynamicColour = it)) })
        }
    }
    Text(labelOf("font_scale", "%.2f".format(Locale.US, scale)), fontWeight = FontWeight.Bold)
    Slider(
        value = scale,
        onValueChange = { scale = it },
        valueRange = Appearance.MIN_SCALE..Appearance.MAX_SCALE,
        onValueChangeFinished = { onChange(current.copy(appFontScale = scale)) },
    )
    Text(labelOf("font"), fontWeight = FontWeight.Bold)
    listOf(Appearance.DEFAULT_FONT to "font_system", Appearance.FONT_CAIRO to "font_cairo").forEach { (font, key) ->
        OutlinedButton(onClick = { onChange(current.copy(arabicFont = font, englishFont = font)) }, modifier = Modifier.fillMaxWidth()) {
            Text((if (current.arabicFont == font) labelOf("selected_prefix") else "") + labelOf(key))
        }
    }
}

@Composable
internal fun LockSettingsScreen(current: LockPrefs, secure: Boolean, onChange: (LockPrefs) -> Unit) {
    Text(labelOf("lock_intro"))
    if (!secure) Text(labelOf("lock_no_secure_warn"), fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(labelOf("app_lock"))
        Switch(checked = current.enabled, onCheckedChange = { onChange(current.copy(enabled = it)) })
    }
    Text(labelOf("after_leave"), fontWeight = FontWeight.Bold)
    listOf(
        LockTimeout.IMMEDIATE to "timeout_immediate",
        LockTimeout.ONE_MINUTE to "timeout_one_min",
        LockTimeout.FIVE_MINUTES to "timeout_five_min",
        LockTimeout.FIFTEEN_MINUTES to "timeout_fifteen_min",
    ).forEach { (timeout, key) ->
        OutlinedButton(onClick = { onChange(current.copy(timeout = timeout)) }, enabled = current.enabled, modifier = Modifier.fillMaxWidth()) {
            Text((if (current.timeout == timeout) labelOf("selected_prefix") else "") + labelOf(key))
        }
    }
    Text(labelOf("lock_monotonic_note"), style = MaterialTheme.typography.bodySmall)
    Text(labelOf("fingerprint_not_yet"), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ActivityScreen(config: HubConfig? = null) {
    val context = LocalContext.current
    var mirrorNote by remember { mutableStateOf<String?>(null) }
    var mirrorBusy by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var snapshot by remember { mutableStateOf<ActivitySnapshot?>(null) }
    var failed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val alive = AtomicBoolean(true)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val read = runCatching { ActivityReader.read(context.applicationContext) }.getOrNull()
            main.post { if (alive.get()) { snapshot = read; failed = read == null } }
        }.start()
        onDispose { alive.set(false) }
    }
    val s = snapshot
    when {
        failed -> Text(labelOf("activity_open_failed"))
        s == null -> Text(labelOf("loading"))
        else -> {
            Text(
                if (s.ok) labelOf("activity_verify_ok", s.count) else labelOf("activity_verify_bad"),
                fontWeight = FontWeight.Bold,
            )
            Text(labelOf("activity_chain", if (s.ok) "ok" else "MISMATCH"), style = MaterialTheme.typography.bodySmall)
            Text(labelOf("activity_local_note"))
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.markdown)); copied = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (copied) labelOf("export_copied") else labelOf("export_copy"))
            }
            if (config != null && config.isLive) {
                OutlinedButton(
                    enabled = !mirrorBusy,
                    onClick = {
                        mirrorBusy = true
                        mirrorNote = null
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        Thread {
                            val app = context.applicationContext
                            val r = runCatching { com.pixelplus.owner.audit.AuditMirrorRunner.runOnce(app, config) }
                                .getOrElse { com.pixelplus.owner.audit.AuditMirrorRunner.Result.Blocked(TextKey.of("mirror_error")) }
                            main.post {
                                mirrorBusy = false
                                mirrorNote = app.mirrorResultMessage(r)
                            }
                        }.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (mirrorBusy) labelOf("mirror_sending") else labelOf("mirror_send")) }
                mirrorNote?.let { Text(it) }
            }
            if (s.lines.isEmpty()) Text(labelOf("no_activity_yet"))
            s.lines.forEach { line ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line.arabic, fontWeight = FontWeight.Bold)
                        Text(line.english)
                        Text(line.time + " · " + line.screen + " · " + line.result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (s.count > ActivityReader.MAX_LINES) Text(labelOf("activity_truncated", ActivityReader.MAX_LINES))
        }
    }
}

@Composable
internal fun BackupScreen(reauthenticate: ((Boolean) -> Unit) -> Unit = { it(true) }) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf<com.pixelplus.owner.backup.RestorePlan?>(null) }
    var restoreNote by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        plan = null
        restoreNote = context.text("restore_checking")
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val read = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > com.pixelplus.owner.backup.BackupLimits.MAX_PAYLOAD_BYTES + 1_048_576) return@use null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            val prepared = if (read == null) null else runCatching { AndroidRestore.prepare(read) }.getOrNull()
            main.post {
                when (prepared) {
                    is AndroidRestore.Prepared.Ready -> { plan = prepared.plan; restoreNote = null }
                    is AndroidRestore.Prepared.Refused -> restoreNote = faultText(context.applicationContext, prepared.fault)
                    null -> restoreNote = context.text("restore_read_failed")
                }
            }
        }.start()
    }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = null
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val result = runCatching {
                AndroidBackup.run(context.applicationContext, uri, System.currentTimeMillis()) { UUID.randomUUID().toString() }
            }.getOrElse { AndroidBackup.Result.Failed(TextKey.of("backup_unexpected")) }
            main.post {
                busy = false
                status = context.applicationContext.backupResultMessage(result)
            }
        }.start()
    }
    Text(labelOf("backup_intro"))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(labelOf("backup_read_first"), fontWeight = FontWeight.Bold)
            Text(labelOf("backup_phone_key"))
            Text(labelOf("backup_restore_phone_only"))
            Text(labelOf("backup_no_tokens"))
        }
    }
    Button(
        enabled = !busy,
        onClick = { picker.launch("pixel-plus-owner-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".ppob") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) labelOf("backup_busy") else labelOf("backup_now")) }
    status?.let { Text(it) }
    if (Build.VERSION.SDK_INT < AndroidBackup.MIN_SDK) Text(labelOf("backup_min_sdk"))
    Text(labelOf("restore_section"), fontWeight = FontWeight.Bold)
    OutlinedButton(enabled = !restoring, onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(labelOf("restore_pick_file")) }
    restoreNote?.let { Text(it) }
    plan?.let { p ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(labelOf("restore_valid"), fontWeight = FontWeight.Bold)
                Text(
                    labelOf(
                        "restore_taken_at",
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }.format(Date(p.manifest.createdAtUtcMs)),
                    ),
                )
                p.notices.forEach { Text("• " + noticeText(context, it)) }
                Text(labelOf("restore_safety_note"))
                Button(
                    enabled = !restoring,
                    onClick = {
                        restoring = true
                        reauthenticate { ok ->
                            if (!ok) { restoring = false; restoreNote = context.text("restore_auth_failed"); return@reauthenticate }
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            Thread {
                                val r = runCatching { AndroidRestore.apply(context.applicationContext, p) }
                                    .getOrElse { AndroidRestore.Applied.Failed(TextKey.of("book_error_generic")) }
                                main.post {
                                    restoring = false
                                    plan = null
                                    restoreNote = context.applicationContext.restoreAppliedMessage(r)
                                }
                            }.start()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (restoring) labelOf("restore_busy") else labelOf("restore_now")) }
            }
        }
    }
}

@Composable
internal fun KpiCards(kpi: KpiSummary) {
    fun n(tab: RequestTab) = (kpi.byTab[tab] ?: 0).toString()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(labelOf("kpi_pending_title"), fontWeight = FontWeight.Bold)
            KpiRow(labelOf("kpi_waiting"), n(RequestTab.WAITING))
            KpiRow(labelOf("kpi_approved_pc"), n(RequestTab.APPROVED_WAITING_FOR_PC))
            KpiRow(labelOf("kpi_expired"), n(RequestTab.APPROVAL_EXPIRED))
            KpiRow(labelOf("kpi_today"), kpi.createdToday.toString())
            if (kpi.unknown > 0) KpiRow(labelOf("kpi_unknown"), kpi.unknown.toString())
            Text(labelOf("kpi_approval_note"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun KpiRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun RequestFilterButtons(selected: RequestTab?, onSelect: (RequestTab?) -> Unit) {
    listOf<Pair<RequestTab?, String>>(
        null to "filter_all",
        RequestTab.WAITING to "filter_waiting",
        RequestTab.APPROVED_WAITING_FOR_PC to "filter_approved_pc",
        RequestTab.APPROVAL_EXPIRED to "filter_expired",
    ).forEach { (tab, key) ->
        OutlinedButton(onClick = { onSelect(tab) }, modifier = Modifier.fillMaxWidth()) {
            Text((if (selected == tab) labelOf("selected_prefix") else "") + labelOf(key))
        }
    }
}

@Composable
internal fun CustomersScreen() {
    val context = LocalContext.current
    var list by remember { mutableStateOf<CustomerList?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var trash by remember { mutableStateOf(false) }
    var openNotes by remember { mutableStateOf<String?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(reload) {
        val alive = AtomicBoolean(true)
        Thread {
            val loaded = runCatching { CustomerBook.load(context.applicationContext) }.getOrNull()
            main.post { if (alive.get()) { list = loaded; unavailable = loaded == null } }
        }.start()
        onDispose { alive.set(false) }
    }
    fun run(op: () -> BookResult) {
        Thread {
            val r = runCatching(op).getOrElse { BookResult.Refused(TextKey.of("book_error_generic")) }
            main.post {
                note = if (r is BookResult.Refused) r.message(context) else null
                if (r == BookResult.Ok) { name = ""; phone = ""; reload += 1 }
            }
        }.start()
    }
    Text(labelOf("customers_book_intro"))
    if (unavailable) { Text(labelOf("customers_unavailable")); return }
    Text(labelOf("new_customer"), fontWeight = FontWeight.Bold)
    androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("name_label")) })
    androidx.compose.material3.OutlinedTextField(value = phone, onValueChange = { phone = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("phone_optional")) })
    Button(onClick = { val n = name; val p = phone; run { CustomerBook.add(context.applicationContext, n, p) } }, modifier = Modifier.fillMaxWidth()) { Text(labelOf("add")) }
    note?.let { Text(it) }
    val current = list
    if (current == null) { Text(labelOf("loading")); return }
    androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("search_by_name")) })
    val shown = com.pixelplus.owner.domain.ArabicSearch.filterSorted(current.active, query) { it.name }
    if (shown.isEmpty()) Text(if (current.active.isEmpty()) labelOf("no_customers") else labelOf("no_search_results"))
    shown.forEach { c ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.name, fontWeight = FontWeight.Bold)
                c.phone?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tel = c.phone?.let { com.pixelplus.owner.domain.IntentUri.safe("tel:$it") }
                    if (tel != null) OutlinedButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse(tel))) }
                    }) { Text(labelOf("call")) }
                    OutlinedButton(onClick = { openNotes = if (openNotes == c.id) null else c.id }) {
                        Text(if (openNotes == c.id) labelOf("notes_collapse") else labelOf("notes_expand"))
                    }
                    OutlinedButton(onClick = { run { CustomerBook.hide(context.applicationContext, c.id) } }) { Text(labelOf("hide")) }
                }
                if (openNotes == c.id) NotesPanel(c.id)
            }
        }
    }
    if (current.hidden.isNotEmpty()) {
        OutlinedButton(onClick = { trash = !trash }, modifier = Modifier.fillMaxWidth()) {
            Text((if (trash) "▾ " else "▸ ") + labelOf("hidden_section", current.hidden.size))
        }
        if (trash) current.hidden.forEach { c ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name)
                    OutlinedButton(onClick = { run { CustomerBook.restore(context.applicationContext, c.id) } }) { Text(labelOf("restore_customer")) }
                }
            }
        }
    }
    Text(labelOf("customers_soft_delete_note"), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun MoneyScreen() {
    val context = LocalContext.current
    var overview by remember { mutableStateOf<MoneyOverview?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var customerId by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("EGP") }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(reload) {
        val alive = AtomicBoolean(true)
        Thread {
            val loaded = runCatching { MoneyBook.load(context.applicationContext, System.currentTimeMillis()) }.getOrNull()
            main.post { if (alive.get()) { overview = loaded; failed = loaded == null } }
        }.start()
        onDispose { alive.set(false) }
    }
    fun run(op: () -> BookResult) {
        Thread {
            val r = runCatching(op).getOrElse { BookResult.Refused(TextKey.of("book_error_generic")) }
            main.post {
                note = if (r is BookResult.Refused) r.message(context) else null
                if (r == BookResult.Ok) { amount = ""; reference = ""; reload += 1 }
            }
        }.start()
    }
    Text(labelOf("money_intro"))
    if (failed) { Text(labelOf("money_unavailable")); return }
    val o = overview
    if (o == null) { Text(labelOf("loading")); return }
    Text(labelOf("month_total", o.monthLabel), fontWeight = FontWeight.Bold)
    if (o.monthTotals.isEmpty()) Text(labelOf("no_entries_month"))
    o.monthTotals.forEach { (cur, minor) -> Text(cur + "  " + (Money.plain(minor, cur) ?: "—")) }
    Text(labelOf("dest_payment_new"), fontWeight = FontWeight.Bold)
    if (o.customers.isEmpty()) Text(labelOf("add_customer_first"))
    o.customers.forEach { c ->
        OutlinedButton(onClick = { customerId = c.id }, modifier = Modifier.fillMaxWidth()) {
            Text((if (customerId == c.id) labelOf("selected_prefix") else "") + c.name)
        }
    }
    androidx.compose.material3.OutlinedTextField(value = amount, onValueChange = { amount = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("amount_label")) })
    androidx.compose.material3.OutlinedTextField(value = currency, onValueChange = { currency = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("currency_label")) })
    androidx.compose.material3.OutlinedTextField(value = reference, onValueChange = { reference = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("reference_optional")) })
    Button(
        enabled = customerId != null,
        onClick = { val c = customerId; val a = amount; val cur = currency; val r = reference; if (c != null) run { MoneyBook.post(context.applicationContext, c, a, cur, r) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(labelOf("post_payment")) }
    note?.let { Text(it) }
    Text(labelOf("recent_entries"), fontWeight = FontWeight.Bold)
    if (o.entries.isEmpty()) Text(labelOf("no_entries"))
    o.entries.forEach { e ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.customerName + " · " + (Money.plain(e.amountMinor, e.currency) ?: "—") + " " + e.currency, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }.format(Date(e.receivedAtUtcMs)), style = MaterialTheme.typography.bodySmall)
                if (e.isReversal) Text(labelOf("reversal_entry"))
                if (e.reversed) Text(labelOf("entry_reversed"))
                if (!e.isReversal && !e.reversed) OutlinedButton(onClick = { run { MoneyBook.reverse(context.applicationContext, e.id) } }) { Text(labelOf("reverse_entry")) }
            }
        }
    }
    Text(labelOf("money_immutable_note"), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FollowUpsScreen() {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<FollowUpItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(reload) {
        val alive = AtomicBoolean(true)
        Thread {
            val loaded = runCatching { FollowUpBook.load(context.applicationContext) }.getOrNull()
            main.post { if (alive.get()) { items = loaded; failed = loaded == null } }
        }.start()
        onDispose { alive.set(false) }
    }
    fun run(op: () -> BookResult) {
        Thread {
            val r = runCatching(op).getOrElse { BookResult.Refused(TextKey.of("book_error_generic")) }
            main.post {
                note = if (r is BookResult.Refused) r.message(context) else null
                if (r == BookResult.Ok) { title = ""; reload += 1 }
            }
        }.start()
    }
    Text(labelOf("followups_intro"))
    if (failed) { Text(labelOf("followups_unavailable")); return }
    androidx.compose.material3.OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("followup_what")) })
    androidx.compose.material3.OutlinedTextField(value = date, onValueChange = { date = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("followup_date")) })
    androidx.compose.material3.OutlinedTextField(value = time, onValueChange = { time = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(labelOf("followup_time")) })
    Button(onClick = { val t = title; val d = date; val h = time; run { FollowUpBook.add(context.applicationContext, t, null, d, h, 30) } }, modifier = Modifier.fillMaxWidth()) { Text(labelOf("add_followup")) }
    note?.let { Text(it) }
    val list = items
    if (list == null) { Text(labelOf("loading")); return }
    if (list.isEmpty()) Text(labelOf("no_followups"))
    val now = System.currentTimeMillis()
    list.forEach { f ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (f.done) "✓ " else "") + f.title, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }.format(Date(f.dueAtUtcMs)))
                if (!f.done && f.dueAtUtcMs <= now) Text(labelOf("followup_due"), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!f.done) Button(onClick = { run { FollowUpBook.complete(context.applicationContext, f.id) } }) { Text(labelOf("done")) }
                    OutlinedButton(onClick = { run { FollowUpBook.hide(context.applicationContext, f.id) } }) { Text(labelOf("hide")) }
                }
            }
        }
    }
}

@Composable
private fun NotesPanel(customerId: String) {
    val context = LocalContext.current
    var notes by remember(customerId) { mutableStateOf<List<CustomerBook.NoteRow>?>(null) }
    var reload by remember(customerId) { mutableStateOf(0) }
    var text by remember(customerId) { mutableStateOf("") }
    var message by remember(customerId) { mutableStateOf<String?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(customerId, reload) {
        val alive = AtomicBoolean(true)
        Thread {
            val loaded = runCatching { CustomerBook.notes(context.applicationContext, customerId) }.getOrNull()
            main.post { if (alive.get()) notes = loaded }
        }.start()
        onDispose { alive.set(false) }
    }
    fun run(op: () -> BookResult) {
        Thread {
            val r = runCatching(op).getOrElse { BookResult.Refused(TextKey.of("book_error_generic")) }
            main.post {
                message = if (r is BookResult.Refused) r.message(context) else null
                if (r == BookResult.Ok) { text = ""; reload += 1 }
            }
        }.start()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes?.forEach { n ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(n.text, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { run { CustomerBook.hideNote(context.applicationContext, n.id) } }) { Text(labelOf("hide")) }
            }
        }
        if (notes != null && notes!!.isEmpty()) Text(labelOf("no_notes"))
        androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text(labelOf("new_note_encrypted")) })
        Button(onClick = { val t = text; run { CustomerBook.addNote(context.applicationContext, customerId, t) } }, modifier = Modifier.fillMaxWidth()) { Text(labelOf("save_note")) }
        message?.let { Text(it) }
    }
}

private fun <T> hubCall(config: HubConfig, onDone: (MirrorResult<T>) -> Unit, call: (OwnerReadClient) -> MirrorResult<T>) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    Thread {
        val r = runCatching { call(OwnerReadClient(AndroidHubTransport())) }.getOrElse { MirrorResult.Retry("error") }
        main.post { onDone(r) }
    }.start()
}

private fun hubMessage(context: android.content.Context, r: MirrorResult<*>): String = when (r) {
    MirrorResult.NotAvailable -> context.text("hub_screen_unsupported")
    MirrorResult.AuthBlocked -> context.text("hub_auth_blocked")
    is MirrorResult.Retry -> context.text("hub_retry")
    is MirrorResult.Ok -> ""
    else -> context.text("hub_bad_response")
}

@Composable
internal fun LicenceHistoryScreen(config: HubConfig) {
    val context = LocalContext.current
    val app = context.applicationContext
    var status by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var more by remember { mutableStateOf<String?>(null) }
    DisposableEffect(status, reload, more, config.baseUrl) {
        loading = true
        val alive = AtomicBoolean(true)
        val cursor = more
        hubCall(config, { r ->
            if (alive.get()) {
                loading = false
                if (r is MirrorResult.Ok) {
                    rows = if (cursor == null) r.value.rows else rows + r.value.rows
                    next = r.value.nextAfter
                    error = null
                } else { error = hubMessage(app, r) }
            }
        }) { it.history(config, status, cursor, 50) }
        onDispose { alive.set(false) }
    }
    Text(labelOf("licence_history_intro"))
    listOf<Pair<String?, String>>(
        null to "licence_filter_all",
        "pending" to "licence_filter_pending",
        "issued" to "licence_filter_issued",
        "delivered" to "licence_filter_delivered",
        "refused" to "licence_filter_refused",
        "expired" to "licence_filter_expired",
    ).forEach { (key, labelKey) ->
        OutlinedButton(onClick = { status = key; more = null; rows = emptyList(); reload += 1 }, modifier = Modifier.fillMaxWidth()) {
            Text((if (status == key) labelOf("selected_prefix") else "") + labelOf(labelKey))
        }
    }
    error?.let { Text(it) }
    if (loading && rows.isEmpty()) Text(labelOf("loading"))
    if (!loading && rows.isEmpty() && error == null) Text(labelOf("no_licence_requests"))
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }
    rows.forEach { r ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.product + " · " + context.localizedLicenceKind(r.kind), fontWeight = FontWeight.Bold)
                Text(
                    context.text(
                        "licence_status_line",
                        r.status,
                        r.ownerDecision?.let { context.text("your_decision", context.localizedOwnerDecision(it)) } ?: "",
                    ),
                )
                Text(labelOf("device_line", r.device, fmt.format(Date(r.createdAt * 1000))), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    next?.let { cursor -> OutlinedButton(enabled = !loading, onClick = { more = cursor }, modifier = Modifier.fillMaxWidth()) { Text(labelOf("more")) } }
}

@Composable
internal fun InstallsScreen(config: HubConfig) {
    val context = LocalContext.current
    val app = context.applicationContext
    var ids by remember { mutableStateOf<List<String>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var after by remember { mutableStateOf<String?>(null) }
    DisposableEffect(after, config.baseUrl) {
        loading = true
        val alive = AtomicBoolean(true)
        val cursor = after
        hubCall(config, { r ->
            if (alive.get()) {
                loading = false
                if (r is MirrorResult.Ok) { ids = if (cursor == null) r.value.ids else ids + r.value.ids; next = r.value.nextAfterId; error = null } else error = hubMessage(app, r)
            }
        }) { it.installs(config, cursor, 100) }
        onDispose { alive.set(false) }
    }
    Text(labelOf("installs_intro"))
    error?.let { Text(it) }
    if (loading && ids.isEmpty()) Text(labelOf("loading"))
    if (!loading && ids.isEmpty() && error == null) Text(labelOf("no_installs"))
    if (ids.isNotEmpty()) {
        Text(
            labelOf("installs_shown", ids.size, if (next != null) labelOf("installs_more_hint") else ""),
            fontWeight = FontWeight.Bold,
        )
    }
    ids.forEach { Text(it.take(8) + "…" + it.takeLast(4)) }
    next?.let { cursor -> OutlinedButton(enabled = !loading, onClick = { after = cursor }, modifier = Modifier.fillMaxWidth()) { Text(labelOf("more")) } }
    Text(labelOf("installs_footer"), style = MaterialTheme.typography.bodySmall)
}


private fun faultText(context: android.content.Context, f: com.pixelplus.owner.backup.BackupFault): String = context.text(
        when (f) {
            com.pixelplus.owner.backup.BackupFault.NOT_AN_ARCHIVE -> "fault_not_archive"
            com.pixelplus.owner.backup.BackupFault.UNSUPPORTED_FORMAT -> "fault_unsupported"
            com.pixelplus.owner.backup.BackupFault.NEWER_SCHEMA -> "fault_newer_schema"
            com.pixelplus.owner.backup.BackupFault.WRONG_KEY_OR_TAMPERED -> "fault_wrong_key"
            com.pixelplus.owner.backup.BackupFault.TOO_LARGE -> "fault_too_large"
            else -> "fault_generic"
        },
)

private fun noticeText(context: android.content.Context, n: com.pixelplus.owner.backup.RestoreNotice): String = context.text(
        when (n) {
            com.pixelplus.owner.backup.RestoreNotice.TOKENS_MUST_BE_ENTERED_AGAIN -> "notice_tokens"
            com.pixelplus.owner.backup.RestoreNotice.QUEUE_NEEDS_REVIEW -> "notice_queue"
            com.pixelplus.owner.backup.RestoreNotice.NEW_AUDIT_CHAIN_STARTS -> "notice_audit"
            com.pixelplus.owner.backup.RestoreNotice.SIGNING_STAYS_OFF -> "notice_signing"
            com.pixelplus.owner.backup.RestoreNotice.OLDER_SCHEMA_WILL_MIGRATE -> "notice_migrate"
        },
)
