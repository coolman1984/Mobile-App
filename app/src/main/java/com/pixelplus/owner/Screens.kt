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
internal fun LockScreen(secure: Boolean, onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("التطبيق مقفول", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("The app is locked")
                Text(if (secure) "افتح القفل بقفل الشاشة الخاص بهاتفك." else "هذا الهاتف بلا قفل شاشة. اضبط قفلًا في إعدادات الهاتف.")
                if (secure) Button(onClick = onUnlock) { Text("فتح القفل / Unlock") }
            }
        }
    }
}

@Composable
internal fun AppearanceScreen(current: AppearancePrefs, onChange: (AppearancePrefs) -> Unit) {
    var scale by remember(current.appFontScale) { mutableStateOf(current.appFontScale) }
    Text("اختر شكل التطبيق. يُحفظ على هذا الهاتف فقط.")
    Text("الوضع", fontWeight = FontWeight.Bold)
    listOf(ThemeMode.SYSTEM to "حسب الهاتف", ThemeMode.LIGHT to "فاتح", ThemeMode.DARK to "داكن").forEach { (mode, label) ->
        OutlinedButton(onClick = { onChange(current.copy(mode = mode)) }, modifier = Modifier.fillMaxWidth()) {
            Text((if (current.mode == mode) "✓ " else "") + label)
        }
    }
    Text("اللون", fontWeight = FontWeight.Bold)
    Palettes.all.forEach { palette ->
        OutlinedButton(onClick = { onChange(current.copy(paletteId = palette.id)) }, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Color(palette.light.primary)))
                Box(Modifier.size(18.dp).background(Color(palette.dark.primary)))
                Text((if (current.paletteId == palette.id) "✓ " else "") + palette.id)
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 31) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ألوان الهاتف (Android 12+)")
            Switch(checked = current.dynamicColour, onCheckedChange = { onChange(current.copy(dynamicColour = it)) })
        }
    }
    Text("حجم الخط: " + "%.2f".format(Locale.US, scale) + " × حجم الهاتف", fontWeight = FontWeight.Bold)
    Slider(
        value = scale,
        onValueChange = { scale = it },
        valueRange = Appearance.MIN_SCALE..Appearance.MAX_SCALE,
        onValueChangeFinished = { onChange(current.copy(appFontScale = scale)) },
    )
    Text("الخط الحالي هو خط الهاتف. خطوط إضافية غير متاحة بعد.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun LockSettingsScreen(current: LockPrefs, secure: Boolean, onChange: (LockPrefs) -> Unit) {
    Text("يطلب التطبيق قفل شاشة الهاتف عند فتحه وعند العودة إليه بعد المدة المختارة. لا يوجد رمز سري خاص بالتطبيق.")
    if (!secure) Text("هذا الهاتف بلا قفل شاشة، فلا يمكن للتطبيق أن يقفل نفسه. اضبط قفلًا في إعدادات الهاتف.", fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("قفل التطبيق")
        Switch(checked = current.enabled, onCheckedChange = { onChange(current.copy(enabled = it)) })
    }
    Text("بعد الخروج من التطبيق", fontWeight = FontWeight.Bold)
    listOf(
        LockTimeout.IMMEDIATE to "فورًا", LockTimeout.ONE_MINUTE to "بعد دقيقة", LockTimeout.FIVE_MINUTES to "بعد 5 دقائق", LockTimeout.FIFTEEN_MINUTES to "بعد 15 دقيقة",
    ).forEach { (timeout, label) ->
        OutlinedButton(onClick = { onChange(current.copy(timeout = timeout)) }, enabled = current.enabled, modifier = Modifier.fillMaxWidth()) {
            Text((if (current.timeout == timeout) "✓ " else "") + label)
        }
    }
    Text("المدة تُقاس بساعة الجهاز الداخلية، فتغيير تاريخ الهاتف لا يفتح القفل.", style = MaterialTheme.typography.bodySmall)
    Text("بصمة الإصبع داخل التطبيق غير متاحة بعد: تحتاج إذنًا إضافيًا لم يوافق عليه المالك.", style = MaterialTheme.typography.bodySmall)
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
        failed -> Text("تعذر فتح سجل النشاط على هذا الهاتف.")
        s == null -> Text("جارٍ التحميل.")
        else -> {
            Text(if (s.ok) "التحقق من السجل: سليم (${s.count} سطر)" else "التحقق من السجل: غير سليم. لا تعتمد عليه.", fontWeight = FontWeight.Bold)
            Text("Activity chain: " + if (s.ok) "ok" else "MISMATCH", style = MaterialTheme.typography.bodySmall)
            Text("السجل محلي على هذا الهاتف، ولا يُرسل إلى المركز إلا عندما تضغط زر الإرسال. لا يحتوي أسماء ولا أرقام هواتف ولا مبالغ ولا رموز.")
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.markdown)); copied = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (copied) "✓ نُسخ التصدير" else "نسخ التصدير (نص)")
            }
            if (config != null && config.isLive) {
                OutlinedButton(
                    enabled = !mirrorBusy,
                    onClick = {
                        mirrorBusy = true
                        mirrorNote = null
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        Thread {
                            val r = runCatching { com.pixelplus.owner.audit.AuditMirrorRunner.runOnce(context.applicationContext, config) }
                                .getOrElse { com.pixelplus.owner.audit.AuditMirrorRunner.Result.Blocked("حدث خطأ. لم يتغير شيء.", "Error. Nothing changed.") }
                            main.post {
                                mirrorBusy = false
                                mirrorNote = when (r) {
                                    is com.pixelplus.owner.audit.AuditMirrorRunner.Result.Sent -> "أُرسل ${r.count} سطر إلى المركز." + if (r.upToDate) "" else " اضغط مرة أخرى لإرسال الباقي."
                                    com.pixelplus.owner.audit.AuditMirrorRunner.Result.UpToDate -> "المركز يحمل السجل كاملًا."
                                    com.pixelplus.owner.audit.AuditMirrorRunner.Result.NotAvailable -> "هذا المركز لا يدعم نسخ السجل بعد. لم يُرسل شيء."
                                    com.pixelplus.owner.audit.AuditMirrorRunner.Result.NothingToSend -> "لا يوجد سجل لإرساله."
                                    is com.pixelplus.owner.audit.AuditMirrorRunner.Result.Blocked -> r.reasonAr + "\n" + r.reasonEn
                                }
                            }
                        }.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (mirrorBusy) "جارٍ الإرسال" else "إرسال السجل إلى المركز (يدويًا)") }
                mirrorNote?.let { Text(it) }
            }
            if (s.lines.isEmpty()) Text("لا توجد أنشطة بعد.")
            s.lines.forEach { line ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line.arabic, fontWeight = FontWeight.Bold)
                        Text(line.english)
                        Text(line.time + " · " + line.screen + " · " + line.result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (s.count > ActivityReader.MAX_LINES) Text("المعروض آخر ${ActivityReader.MAX_LINES} سطر. التصدير الكامل في النص المنسوخ.")
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
        restoreNote = "جارٍ فحص الملف."
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
                    is AndroidRestore.Prepared.Refused -> restoreNote = faultText(prepared.fault)
                    null -> restoreNote = "تعذر قراءة الملف أو أنه أكبر من الحد المسموح."
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
            }.getOrElse { AndroidBackup.Result.Failed("حدث خطأ غير متوقع. لم تتغير بياناتك.", "Unexpected error. Your data did not change.") }
            main.post {
                busy = false
                status = when (result) {
                    is AndroidBackup.Result.Done -> "تم حفظ النسخة (${result.bytes / 1024} كيلوبايت).\nBackup saved."
                    is AndroidBackup.Result.Failed -> result.reasonAr + "\n" + result.reasonEn
                }
            }
        }.start()
    }
    Text("يحفظ التطبيق نسخة مشفّرة من بياناته المحلية في ملف تختاره.")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("اقرأ قبل أن تنسخ", fontWeight = FontWeight.Bold)
            Text("النسخة مقفلة بمفتاح مرتبط بهذا الهاتف نفسه. إذا ضاع الهاتف أو مُسح التطبيق أو انتقلت إلى هاتف آخر فلن تُفتح.")
            Text("الاستعادة تعمل على هذا الهاتف فقط، وتُبقي نسخة أمان من بياناتك الحالية.")
            Text("لا تحتوي النسخة رموز المركز ولا أي مفتاح توقيع.")
        }
    }
    Button(
        enabled = !busy,
        onClick = { picker.launch("pixel-plus-owner-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".ppob") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "جارٍ النسخ" else "انسخ الآن / Back up now") }
    status?.let { Text(it) }
    if (Build.VERSION.SDK_INT < AndroidBackup.MIN_SDK) Text("النسخ الاحتياطي يحتاج أندرويد 11 أو أحدث.")
    Text("الاستعادة", fontWeight = FontWeight.Bold)
    OutlinedButton(enabled = !restoring, onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("اختر ملف نسخة للاستعادة") }
    restoreNote?.let { Text(it) }
    plan?.let { p ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("النسخة سليمة وتُفتح بمفتاح هذا الهاتف", fontWeight = FontWeight.Bold)
                Text("أُخذت: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }.format(Date(p.manifest.createdAtUtcMs)) + " (القاهرة)")
                p.notices.forEach { Text("• " + noticeText(it)) }
                Text("ستُحفظ نسخة أمان من بياناتك الحالية داخل التطبيق قبل أي تغيير.")
                Button(
                    enabled = !restoring,
                    onClick = {
                        restoring = true
                        reauthenticate { ok ->
                            if (!ok) { restoring = false; restoreNote = "لم يتم التحقق من هويتك. لم يتغير شيء."; return@reauthenticate }
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            Thread {
                                val r = runCatching { AndroidRestore.apply(context.applicationContext, p) }
                                    .getOrElse { AndroidRestore.Applied.Failed("حدث خطأ. لم تتغير بياناتك.", "Error. Your data did not change.") }
                                main.post {
                                    restoring = false
                                    plan = null
                                    restoreNote = when (r) {
                                        is AndroidRestore.Applied.Done -> "تمت الاستعادة. القرارات المنتظرة (${r.quarantined}) صارت «تحتاج مراجعة». أعد فتح التطبيق لتحديث الشاشات."
                                        is AndroidRestore.Applied.Failed -> r.reasonAr + "\n" + r.reasonEn
                                    }
                                }
                            }.start()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (restoring) "جارٍ الاستعادة" else "استعد الآن") }
            }
        }
    }
}

@Composable
internal fun KpiCards(kpi: KpiSummary) {
    fun n(tab: RequestTab) = (kpi.byTab[tab] ?: 0).toString()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ملخص الطلبات المعلّقة", fontWeight = FontWeight.Bold)
            KpiRow("تنتظر قرارك", n(RequestTab.WAITING))
            KpiRow("وافقت وتنتظر الكمبيوتر", n(RequestTab.APPROVED_WAITING_FOR_PC))
            KpiRow("موافقة انتهت مدتها", n(RequestTab.APPROVAL_EXPIRED))
            KpiRow("وصلت اليوم", kpi.createdToday.toString())
            if (kpi.unknown > 0) KpiRow("حالة غير معروفة", kpi.unknown.toString())
            Text("الموافقة تسجّل قرارك فقط ولا تعني أن الكود صدر.", style = MaterialTheme.typography.bodySmall)
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
        null to "الكل", RequestTab.WAITING to "تنتظر قرارك", RequestTab.APPROVED_WAITING_FOR_PC to "وافقت وتنتظر الكمبيوتر", RequestTab.APPROVAL_EXPIRED to "موافقة منتهية",
    ).forEach { (tab, label) ->
        OutlinedButton(onClick = { onSelect(tab) }, modifier = Modifier.fillMaxWidth()) { Text((if (selected == tab) "✓ " else "") + label) }
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
            val r = runCatching(op).getOrElse { BookResult.Refused("حدث خطأ. لم تتغير بياناتك.", "Error. Your data did not change.") }
            main.post {
                note = if (r is BookResult.Refused) r.ar + "\n" + r.en else null
                if (r == BookResult.Ok) { name = ""; phone = ""; reload += 1 }
            }
        }.start()
    }
    Text("دفتر العملاء خاص بهذا الهاتف. الأسماء والأرقام مشفّرة بمفتاح الهاتف ولا تُرسل للمركز ولا تدخل سجل النشاط.")
    if (unavailable) { Text("تعذر فتح دفتر العملاء أو تجهيز مفتاح الحماية على هذا الهاتف."); return }
    Text("عميل جديد", fontWeight = FontWeight.Bold)
    androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("الاسم") })
    androidx.compose.material3.OutlinedTextField(value = phone, onValueChange = { phone = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("رقم الهاتف (اختياري)") })
    Button(onClick = { val n = name; val p = phone; run { CustomerBook.add(context.applicationContext, n, p) } }, modifier = Modifier.fillMaxWidth()) { Text("إضافة") }
    note?.let { Text(it) }
    val current = list
    if (current == null) { Text("جارٍ التحميل."); return }
    androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("بحث بالاسم") })
    val shown = com.pixelplus.owner.domain.ArabicSearch.filterSorted(current.active, query) { it.name }
    if (shown.isEmpty()) Text(if (current.active.isEmpty()) "لا يوجد عملاء بعد." else "لا توجد نتيجة.")
    shown.forEach { c ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.name, fontWeight = FontWeight.Bold)
                c.phone?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tel = c.phone?.let { com.pixelplus.owner.domain.IntentUri.safe("tel:$it") }
                    if (tel != null) OutlinedButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse(tel))) }
                    }) { Text("اتصال") }
                    OutlinedButton(onClick = { openNotes = if (openNotes == c.id) null else c.id }) { Text(if (openNotes == c.id) "▾ ملاحظات" else "▸ ملاحظات") }
                    OutlinedButton(onClick = { run { CustomerBook.hide(context.applicationContext, c.id) } }) { Text("إخفاء") }
                }
                if (openNotes == c.id) NotesPanel(c.id)
            }
        }
    }
    if (current.hidden.isNotEmpty()) {
        OutlinedButton(onClick = { trash = !trash }, modifier = Modifier.fillMaxWidth()) { Text((if (trash) "▾ " else "▸ ") + "المخفيون (${current.hidden.size})") }
        if (trash) current.hidden.forEach { c ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name)
                    OutlinedButton(onClick = { run { CustomerBook.restore(context.applicationContext, c.id) } }) { Text("استرجاع") }
                }
            }
        }
    }
    Text("لا يُحذف شيء نهائيًا. الإخفاء يمكن التراجع عنه من المخفيين.", style = MaterialTheme.typography.bodySmall)
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
            val r = runCatching(op).getOrElse { BookResult.Refused("حدث خطأ. لم تتغير بياناتك.", "Error. Your data did not change.") }
            main.post {
                note = if (r is BookResult.Refused) r.ar + "\n" + r.en else null
                if (r == BookResult.Ok) { amount = ""; reference = ""; reload += 1 }
            }
        }.start()
    }
    Text("حسابات محلية على هذا الهاتف. لا تُرسل للمركز، ولا تُصدر ترخيصًا، ولا تُغيّر حالة أي طلب.")
    if (failed) { Text("تعذر فتح الحسابات أو تجهيز مفتاح الحماية على هذا الهاتف."); return }
    val o = overview
    if (o == null) { Text("جارٍ التحميل."); return }
    Text("إجمالي الشهر ${o.monthLabel} (بتوقيت القاهرة)", fontWeight = FontWeight.Bold)
    if (o.monthTotals.isEmpty()) Text("لا توجد قيود هذا الشهر.")
    o.monthTotals.forEach { (cur, minor) -> Text(cur + "  " + (Money.plain(minor, cur) ?: "—")) }
    Text("دفعة جديدة", fontWeight = FontWeight.Bold)
    if (o.customers.isEmpty()) Text("أضف عميلًا أولًا من تبويب العملاء.")
    o.customers.forEach { c ->
        OutlinedButton(onClick = { customerId = c.id }, modifier = Modifier.fillMaxWidth()) { Text((if (customerId == c.id) "✓ " else "") + c.name) }
    }
    androidx.compose.material3.OutlinedTextField(value = amount, onValueChange = { amount = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("المبلغ (مثل 1250.50)") })
    androidx.compose.material3.OutlinedTextField(value = currency, onValueChange = { currency = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("العملة (3 حروف)") })
    androidx.compose.material3.OutlinedTextField(value = reference, onValueChange = { reference = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("مرجع (اختياري، مشفّر)") })
    Button(
        enabled = customerId != null,
        onClick = { val c = customerId; val a = amount; val cur = currency; val r = reference; if (c != null) run { MoneyBook.post(context.applicationContext, c, a, cur, r) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("تسجيل الدفعة") }
    note?.let { Text(it) }
    Text("آخر القيود", fontWeight = FontWeight.Bold)
    if (o.entries.isEmpty()) Text("لا توجد قيود بعد.")
    o.entries.forEach { e ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.customerName + " · " + (Money.plain(e.amountMinor, e.currency) ?: "—") + " " + e.currency, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }.format(Date(e.receivedAtUtcMs)), style = MaterialTheme.typography.bodySmall)
                if (e.isReversal) Text("قيد عكسي لقيد سابق")
                if (e.reversed) Text("تم عكس هذا القيد")
                if (!e.isReversal && !e.reversed) OutlinedButton(onClick = { run { MoneyBook.reverse(context.applicationContext, e.id) } }) { Text("عكس القيد") }
            }
        }
    }
    Text("القيد المسجّل لا يُعدَّل ولا يُحذف. التصحيح يكون بقيد عكسي.", style = MaterialTheme.typography.bodySmall)
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
            val r = runCatching(op).getOrElse { BookResult.Refused("حدث خطأ. لم تتغير بياناتك.", "Error. Your data did not change.") }
            main.post {
                note = if (r is BookResult.Refused) r.ar + "\n" + r.en else null
                if (r == BookResult.Ok) { title = ""; reload += 1 }
            }
        }.start()
    }
    Text("متابعات خاصة بهذا الهاتف. التنبيه بموعدها داخل التطبيق فقط؛ لا يوجد تذكير يصلك وأنت خارجه بعد.")
    if (failed) { Text("تعذر فتح المتابعات أو تجهيز مفتاح الحماية على هذا الهاتف."); return }
    androidx.compose.material3.OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ما المطلوب؟") })
    androidx.compose.material3.OutlinedTextField(value = date, onValueChange = { date = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("التاريخ (2026-10-15)") })
    androidx.compose.material3.OutlinedTextField(value = time, onValueChange = { time = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("الوقت (14:30) بتوقيت القاهرة") })
    Button(onClick = { val t = title; val d = date; val h = time; run { FollowUpBook.add(context.applicationContext, t, null, d, h, 30) } }, modifier = Modifier.fillMaxWidth()) { Text("إضافة متابعة") }
    note?.let { Text(it) }
    val list = items
    if (list == null) { Text("جارٍ التحميل."); return }
    if (list.isEmpty()) Text("لا توجد متابعات.")
    val now = System.currentTimeMillis()
    list.forEach { f ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (f.done) "✓ " else "") + f.title, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }.format(Date(f.dueAtUtcMs)))
                if (!f.done && f.dueAtUtcMs <= now) Text("حان موعدها", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!f.done) Button(onClick = { run { FollowUpBook.complete(context.applicationContext, f.id) } }) { Text("تم") }
                    OutlinedButton(onClick = { run { FollowUpBook.hide(context.applicationContext, f.id) } }) { Text("إخفاء") }
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
            val r = runCatching(op).getOrElse { BookResult.Refused("حدث خطأ. لم تتغير بياناتك.", "Error. Your data did not change.") }
            main.post {
                message = if (r is BookResult.Refused) r.ar + "\n" + r.en else null
                if (r == BookResult.Ok) { text = ""; reload += 1 }
            }
        }.start()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes?.forEach { n ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(n.text, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { run { CustomerBook.hideNote(context.applicationContext, n.id) } }) { Text("إخفاء") }
            }
        }
        if (notes != null && notes!!.isEmpty()) Text("لا توجد ملاحظات.")
        androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ملاحظة جديدة (مشفّرة)") })
        Button(onClick = { val t = text; run { CustomerBook.addNote(context.applicationContext, customerId, t) } }, modifier = Modifier.fillMaxWidth()) { Text("حفظ الملاحظة") }
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

private fun hubMessage(r: MirrorResult<*>): String = when (r) {
    MirrorResult.NotAvailable -> "هذا المركز لا يدعم هذه الشاشة بعد (يحتاج تحديث المركز)."
    MirrorResult.AuthBlocked -> "رفض المركز رمز المالك."
    is MirrorResult.Retry -> "تعذر الاتصال بالمركز. حاول لاحقًا."
    is MirrorResult.Ok -> ""
    else -> "رد المركز غير مفهوم، فلم يُعرض."
}

@Composable
internal fun LicenceHistoryScreen(config: HubConfig) {
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
                } else { error = hubMessage(r) }
            }
        }) { it.history(config, status, cursor, 50) }
        onDispose { alive.set(false) }
    }
    Text("كل طلبات الترخيص المحفوظة في المركز بكل حالاتها. عرض فقط؛ لا كود ولا مرجع دفع ولا اسم محل.")
    listOf<Pair<String?, String>>(null to "الكل", "pending" to "معلّقة", "issued" to "صدر كودها", "delivered" to "سُلّمت", "refused" to "مرفوضة", "expired" to "منتهية").forEach { (key, label) ->
        OutlinedButton(onClick = { status = key; more = null; rows = emptyList(); reload += 1 }, modifier = Modifier.fillMaxWidth()) { Text((if (status == key) "✓ " else "") + label) }
    }
    error?.let { Text(it) }
    if (loading && rows.isEmpty()) Text("جارٍ التحميل.")
    if (!loading && rows.isEmpty() && error == null) Text("لا توجد طلبات.")
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo") }
    rows.forEach { r ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.product + " · " + kindTitle(r.kind), fontWeight = FontWeight.Bold)
                Text("الحالة: " + r.status + (r.ownerDecision?.let { " · قرارك: " + decisionLabel(it) } ?: ""))
                Text("الجهاز: " + r.device + " · " + fmt.format(Date(r.createdAt * 1000)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    next?.let { cursor -> OutlinedButton(enabled = !loading, onClick = { more = cursor }, modifier = Modifier.fillMaxWidth()) { Text("المزيد") } }
}

@Composable
internal fun InstallsScreen(config: HubConfig) {
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
                if (r is MirrorResult.Ok) { ids = if (cursor == null) r.value.ids else ids + r.value.ids; next = r.value.nextAfterId; error = null } else error = hubMessage(r)
            }
        }) { it.installs(config, cursor, 100) }
        onDispose { alive.set(false) }
    }
    Text("التثبيتات المسجّلة في المركز: معرّفات فقط. لا يُعرض أي رمز أو بيان عن العملاء، ولا يُغيَّر شيء من هنا.")
    error?.let { Text(it) }
    if (loading && ids.isEmpty()) Text("جارٍ التحميل.")
    if (!loading && ids.isEmpty() && error == null) Text("لا توجد تثبيتات مسجّلة.")
    if (ids.isNotEmpty()) Text("المعروض: ${ids.size}" + if (next != null) " (يوجد المزيد)" else "", fontWeight = FontWeight.Bold)
    ids.forEach { Text(it.take(8) + "…" + it.takeLast(4)) }
    next?.let { cursor -> OutlinedButton(enabled = !loading, onClick = { after = cursor }, modifier = Modifier.fillMaxWidth()) { Text("المزيد") } }
    Text("هذه قائمة التسجيل التي أرسلها مركز التحكم فقط، وليست سجل النشاط الكامل.", style = MaterialTheme.typography.bodySmall)
}


private fun faultText(f: com.pixelplus.owner.backup.BackupFault): String = when (f) {
    com.pixelplus.owner.backup.BackupFault.NOT_AN_ARCHIVE -> "هذا الملف ليس نسخة من التطبيق."
    com.pixelplus.owner.backup.BackupFault.UNSUPPORTED_FORMAT -> "صيغة هذه النسخة أحدث من هذا التطبيق."
    com.pixelplus.owner.backup.BackupFault.NEWER_SCHEMA -> "هذه النسخة من إصدار أحدث من التطبيق. حدّث التطبيق أولًا."
    com.pixelplus.owner.backup.BackupFault.WRONG_KEY_OR_TAMPERED -> "تعذر فتح النسخة: إما أنها من هاتف آخر أو أن الملف تغيّر."
    com.pixelplus.owner.backup.BackupFault.TOO_LARGE -> "الملف أكبر من الحد المسموح."
    else -> "الملف غير سليم، لم يتغير شيء."
}

private fun noticeText(n: com.pixelplus.owner.backup.RestoreNotice): String = when (n) {
    com.pixelplus.owner.backup.RestoreNotice.TOKENS_MUST_BE_ENTERED_AGAIN -> "رموز المركز لا تُحفظ في النسخة؛ المحفوظ على الهاتف حاليًا يبقى كما هو. اختبر الاتصال بعد الاستعادة."
    com.pixelplus.owner.backup.RestoreNotice.QUEUE_NEEDS_REVIEW -> "أي قرار كان ينتظر الإرسال يصير «يحتاج مراجعة» ولا يُرسل حتى تؤكده من جديد."
    com.pixelplus.owner.backup.RestoreNotice.NEW_AUDIT_CHAIN_STARTS -> "سجل النشاط المستعاد يُحفظ كما هو، ويُضاف له سطر يقول إن استعادة حدثت."
    com.pixelplus.owner.backup.RestoreNotice.SIGNING_STAYS_OFF -> "إصدار الأكواد يبقى مغلقًا."
    com.pixelplus.owner.backup.RestoreNotice.OLDER_SCHEMA_WILL_MIGRATE -> "النسخة أقدم؛ ستُحدَّث بياناتها تلقائيًا."
}
