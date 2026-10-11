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
import com.pixelplus.owner.audit.ActivitySnapshot
import com.pixelplus.owner.backup.AndroidBackup
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
internal fun ActivityScreen() {
    val context = LocalContext.current
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
            Text("السجل محلي على هذا الهاتف ولا يُرسل إلى المركز بعد. لا يحتوي أسماء ولا أرقام هواتف ولا مبالغ ولا رموز.")
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.markdown)); copied = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (copied) "✓ نُسخ التصدير" else "نسخ التصدير (نص)")
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
internal fun BackupScreen() {
    val context = LocalContext.current
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
            Text("الاستعادة من داخل التطبيق غير متاحة بعد.")
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
