package com.pixelplus.owner

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean

private enum class Tab(val title: String, val emoji: String) {
    HOME("الرئيسية", "🏠"),
    COMPANIES("الشركات", "🏢"),
    REQUESTS("الطلبات", "🔑"),
    ALERTS("التنبيهات", "🔔"),
    CODES("الأكواد", "🛡️"),
    SETTINGS("الإعدادات", "⚙️")
}

private data class PendingDecision(val id: String, val action: OwnerAction, val kind: String)

@Composable
fun OwnerApp(onRequestNotifications: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val store = remember { SecureHubStore(context.applicationContext) }
    val client = remember { HubClient(AndroidHubTransport()) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var config by remember { mutableStateOf(store.read()) }
    var demo by remember { mutableStateOf(DemoSeed.state()) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var generation by remember { mutableStateOf(0) }
    var liveRequests by remember { mutableStateOf<List<LicenceRequest>?>(null) }
    var liveEvents by remember { mutableStateOf<List<LicenceEvent>?>(null) }
    var pullSummary by remember { mutableStateOf<PullSummary?>(null) }
    var reqError by remember { mutableStateOf<String?>(null) }
    var alertsError by remember { mutableStateOf<String?>(null) }
    var pullError by remember { mutableStateOf<String?>(null) }
    var reqLoading by remember { mutableStateOf(false) }
    var alertsLoading by remember { mutableStateOf(false) }
    var pendingDecision by remember { mutableStateOf<PendingDecision?>(null) }
    var deciding by remember { mutableStateOf(false) }
    var decisionMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var settingsNote by remember { mutableStateOf<String?>(null) }
    var queueLine by remember { mutableStateOf("") }
    var cachedAt by remember { mutableStateOf<Long?>(null) }
    var savingSettings by remember { mutableStateOf(false) }

    DisposableEffect(config.isLive) {
        if (config.isLive) HubScheduler.ensure(context.applicationContext)
        onDispose { }
    }

    DisposableEffect(generation, config.baseUrl, config.ownerToken, config.pullToken) {
        if (!config.isLive) {
            liveRequests = null
            liveEvents = null
            pullSummary = null
            reqError = null
            alertsError = null
            pullError = null
            reqLoading = false
            alertsLoading = false
            queueLine = ""
            cachedAt = null
            onDispose { }
        } else {
            val alive = AtomicBoolean(true)
            val snapshot = config
            reqLoading = true
            alertsLoading = true
            queueLine = ""
            cachedAt = null
            reqError = null
            alertsError = null
            pullError = null
            liveRequests = null
            liveEvents = null
            pullSummary = null
            Thread {
                val fetched = runCatching { com.pixelplus.owner.sync.OwnerSync.requests(context.applicationContext, snapshot, client) }
                    .getOrElse { com.pixelplus.owner.sync.RequestSnapshot(HubResult.Rejected("cache_unavailable")) }
                val result = fetched.result
                val queue = runCatching { com.pixelplus.owner.sync.OwnerSync.queueLine(context.applicationContext, snapshot) }.getOrDefault("")
                if (!alive.get()) return@Thread
                main.post {
                    if (activity?.isDestroyed == true || !alive.get()) return@post
                    reqLoading = false
                    queueLine = queue
                    cachedAt = fetched.cachedAt
                    if (result is HubResult.Ok) liveRequests = result.value else reqError = failureCode(result)
                }
            }.start()
            Thread {
                val result = client.events(snapshot)
                if (!alive.get()) return@Thread
                main.post {
                    if (activity?.isDestroyed == true || !alive.get()) return@post
                    alertsLoading = false
                    if (result is HubResult.Ok) liveEvents = result.value else alertsError = failureCode(result)
                }
            }.start()
            if (snapshot.pullToken.isBlank()) {
                pullSummary = null
                pullError = null
            } else {
                Thread {
                    val result = client.pull(snapshot)
                    if (!alive.get()) return@Thread
                    main.post {
                        if (activity?.isDestroyed == true || !alive.get()) return@post
                        if (result is HubResult.Ok) pullSummary = result.value else pullError = failureCode(result)
                    }
                }.start()
            }
            onDispose { alive.set(false) }
        }
    }

    val requestsView = presentRequests(config, demo, reqLoading, reqError, liveRequests, completeSnapshot = liveRequests != null && cachedAt == null)
    val alertsView = presentAlerts(config, demo, alertsLoading, alertsError, liveEvents, pullSummary, pullError)
    val companiesView = presentCompanies(config, demo, liveRequests)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Text(item.emoji) },
                                label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            ) { inner ->
                Column(
                    modifier = Modifier
                        .padding(inner)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("بيكسل بلس", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("إدارة شركتك من الموبايل", style = MaterialTheme.typography.titleMedium)
                    if (config.isLive) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("المركز محفوظ / Hub configured", fontWeight = FontWeight.Bold)
                                Text("الخادم: ${hostOnly(config.baseUrl)}. هذا الهاتف لا يصدر كودًا ولا يحتفظ بمفتاح توقيع.")
                            }
                        }
                    } else {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("نسخة تجريبية ببيانات وهمية فقط", fontWeight = FontWeight.Bold)
                                Text("لم يُضبط المركز بعد. لا توجد موافقات حقيقية. أي تغيير هنا يختفي عند إغلاق التطبيق.")
                            }
                        }
                    }
                    if (!config.isLive) {
                        Text(
                            "العميل المعروض: " + (demo.companies.firstOrNull { it.id == demo.selectedCompanyId }?.name ?: "كل الشركات"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (config.isLive && cachedAt != null) SyncStatusText(com.pixelplus.owner.sync.SyncCopy.cachedSnapshot(cachedAt!!).display)
                    if (config.isLive && queueLine.isNotBlank()) SyncStatusText(queueLine)
                    decisionMessage?.let { message -> SyncStatusText(message) }
                    when (tab) {
                        Tab.HOME -> HomeScreen(homeLines(config, demo, liveRequests, liveEvents)) { tab = it }
                        Tab.COMPANIES -> CompaniesScreen(companiesView, demo) { demo = demo.chooseCompany(it) }
                        Tab.REQUESTS -> RequestsScreen(
                            view = requestsView,
                            deciding = deciding,
                            onDemo = { id, next -> demo = demo.markRequest(id, next) },
                            onDecide = { card, action -> pendingDecision = PendingDecision(card.id, action, card.kind) },
                            onRefresh = { generation += 1 }
                        )
                        Tab.ALERTS -> AlertsScreen(
                            view = alertsView,
                            onRead = { demo = demo.markAlertRead(it) },
                            onRefresh = { generation += 1 }
                        )
                        Tab.CODES -> CodesScreen()
                        Tab.SETTINGS -> SettingsScreen(
                            saved = config,
                            encryptionAvailable = store.encryptionAvailable,
                            testing = testing,
                            saving = savingSettings,
                            testNote = settingsNote,
                            onRequestNotifications = onRequestNotifications,
                            onSave = { next ->
                                savingSettings = true
                                Thread {
                                    val savedOk = store.save(next)
                                    main.post {
                                        if (activity?.isDestroyed == true) return@post
                                        savingSettings = false
                                        if (savedOk) {
                                            config = store.read()
                                            generation += 1
                                            HubScheduler.ensure(context.applicationContext)
                                        }
                                        decisionMessage = if (savedOk) "حُفظت الإعدادات على هذا الجهاز فقط.\nSettings saved on this device only." else "تعذر حفظ الإعدادات بأمان. أعد المحاولة.\nCould not save settings safely. Try again."
                                    }
                                }.start()
                            },
                            onClear = {
                                savingSettings = true
                                Thread {
                                    val cleared = store.clear()
                                    main.post {
                                        if (activity?.isDestroyed == true) return@post
                                        savingSettings = false
                                        if (cleared) {
                                            config = HubConfig("", "", "")
                                            demo = DemoSeed.state()
                                            HubScheduler.cancel(context.applicationContext)
                                            settingsNote = null
                                            decisionMessage = "مُسحت الإعدادات. التطبيق عاد إلى البيانات التجريبية.\nSettings cleared. The app returned to demo data."
                                        } else decisionMessage = "تعذر مسح الإعدادات بأمان.\nCould not clear settings safely."
                                    }
                                }.start()
                            },
                            onTest = { draft ->
                                testing = true
                                settingsNote = null
                                Thread {
                                    val result = client.pending(draft, limit = 1)
                                    val message = if (result is HubResult.Ok) {
                                        "تم الاتصال. الطلبات المعلّقة في هذه الصفحة: ${result.value.size}."
                                    } else {
                                        arabicFailure(failureCode(result))
                                    }
                                    main.post {
                                        if (activity?.isDestroyed == true) return@post
                                        testing = false
                                        settingsNote = message
                                    }
                                }.start()
                            }
                        )
                    }
                }
            }
            pendingDecision?.let { choice ->
                AlertDialog(
                    onDismissRequest = { if (!deciding) pendingDecision = null },
                    title = { Text(if (choice.action == OwnerAction.APPROVE) "تأكيد الموافقة" else "تأكيد الرفض") },
                    text = { Text(confirmText(choice)) },
                    confirmButton = {
                        TextButton(
                            enabled = !deciding,
                            onClick = {
                                val snapshot = config
                                deciding = true
                                pendingDecision = null
                                val request = liveRequests?.firstOrNull { it.id == choice.id }
                                Thread {
                                    val message = if (request == null) com.pixelplus.owner.sync.SyncCopy.settingsChanged().display else runCatching {
                                        com.pixelplus.owner.sync.OwnerSync.enqueue(context.applicationContext, snapshot, request, choice.action)
                                    }.getOrElse { "تعذر حفظ القرار بأمان. أعد المحاولة.\nCould not save the decision safely. Try again." }
                                    main.post {
                                        if (activity?.isDestroyed == true) return@post
                                        deciding = false
                                        decisionMessage = if (config == snapshot) message else com.pixelplus.owner.sync.SyncCopy.settingsChanged().display
                                        generation += 1
                                    }
                                }.start()
                            }
                        ) { Text("تأكيد") }
                    },
                    dismissButton = {
                        TextButton(enabled = !deciding, onClick = { pendingDecision = null }) { Text("إلغاء") }
                    }
                )
            }
        }
    }
}

private fun confirmText(choice: PendingDecision): String {
    val head = if (choice.action == OwnerAction.APPROVE) {
        "تأكيد الموافقة على هذا الطلب؟ الموافقة تسجّل قرارك فقط. إصدار الكود يبقى في برنامج التراخيص على الكمبيوتر."
    } else {
        "تأكيد رفض هذا الطلب؟ الرفض يغلق الطلب."
    }
    val extra = when (choice.kind) {
        "monthly", "permanent" -> " النوع المدفوع يحتاج أيضًا تأكيد الدفع على الكمبيوتر."
        "trial" -> if (choice.action == OwnerAction.APPROVE) " كود التجربة يصدر عندما يكون برنامج التراخيص مفتوحًا على الكمبيوتر." else ""
        else -> ""
    }
    return head + extra
}

@Composable
private fun HomeScreen(lines: List<HomeLine>, navigate: (Tab) -> Unit) {
    SectionTitle("ملخص اليوم")
    lines.forEach { line -> Metric(line.label, line.value) }
    Button(onClick = { navigate(Tab.REQUESTS) }, modifier = Modifier.fillMaxWidth()) {
        Text("افتح طلبات التفعيل")
    }
    OutlinedButton(onClick = { navigate(Tab.ALERTS) }, modifier = Modifier.fillMaxWidth()) {
        Text("راجع التنبيهات")
    }
    OutlinedButton(onClick = { navigate(Tab.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
        Text("الإعدادات")
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CompaniesScreen(view: CompaniesPresentation, demo: DemoState, choose: (String?) -> Unit) {
    SectionTitle(if (view.demo) "الشركات" else "المنتجات")
    view.note?.let { Text(it) }
    if (view.demo) {
        OutlinedButton(onClick = { choose(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (demo.selectedCompanyId == null) "✓ كل الشركات" else "اعرض كل الشركات")
        }
        demo.companies.forEach { company ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(company.name, fontWeight = FontWeight.Bold)
                    Text(company.category)
                    Text("طلبات: " + demo.requests.count { it.companyId == company.id })
                    OutlinedButton(onClick = { choose(company.id) }) {
                        Text(if (demo.selectedCompanyId == company.id) "✓ محددة" else "اختار الشركة")
                    }
                }
            }
        }
    } else if (view.names.isEmpty()) {
        Text("لا توجد طلبات معلّقة.")
    } else {
        view.names.forEach { name ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(name, modifier = Modifier.padding(14.dp), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RequestsScreen(
    view: RequestsPresentation,
    deciding: Boolean,
    onDemo: (String, RequestStatus) -> Unit,
    onDecide: (RequestCard, OwnerAction) -> Unit,
    onRefresh: () -> Unit
) {
    SectionTitle(if (view.demo) "طلبات التفعيل التجريبية" else "طلبات الترخيص المعلّقة")
    if (!view.demo) {
        Text("الموافقة تسجّل قرارك فقط. التوقيع على الكود يتم في برنامج التراخيص على الكمبيوتر.")
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("تحديث") }
    }
    if (view.loading) Text("جارٍ التحميل.")
    view.errorCode?.let { Text(arabicFailure(it)) }
    if (!view.loading && view.cards.isEmpty() && view.errorCode == null) {
        Text(if (view.demo) "مفيش طلبات للشركة دي." else "لا توجد طلبات معلّقة.")
    }
    view.cards.forEach { card ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.title, fontWeight = FontWeight.Bold)
                Text(card.detail)
                Text("الحالة: " + card.status)
                if (card.demo && card.pending) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDemo(card.id, RequestStatus.REVIEWED) }) { Text("مراجعة تجريبية") }
                        OutlinedButton(onClick = { onDemo(card.id, RequestStatus.REJECTED) }) { Text("رفض تجريبي") }
                    }
                }
                if (!card.demo && (card.canApprove || card.canReject)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (card.canApprove) {
                            Button(enabled = !deciding, onClick = { onDecide(card, OwnerAction.APPROVE) }) { Text("موافقة") }
                        }
                        if (card.canReject) {
                            OutlinedButton(enabled = !deciding, onClick = { onDecide(card, OwnerAction.REFUSE) }) { Text("رفض") }
                        }
                    }
                }
            }
        }
    }
    if (view.truncated) Text("هذه أقدم 50 طلبًا معلّقًا.")
    if (view.demo) {
        Text("المراجعة هنا محاكاة فقط. لا يوجد توقيع أو إرسال ترخيص.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlertsScreen(view: AlertsPresentation, onRead: (String) -> Unit, onRefresh: () -> Unit) {
    SectionTitle(if (view.demo) "التنبيهات التجريبية" else "سجل المركز")
    if (!view.demo) {
        Text("عرض فقط. لا يُحذف شيء من المركز من هذا الهاتف.")
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("تحديث") }
    }
    if (view.loading) Text("جارٍ التحميل.")
    view.errorCode?.let { Text(arabicFailure(it)) }
    view.pullNote?.let { Text(it) }
    view.pullSummary?.let { Text(it) }
    view.pullError?.let { Text(arabicFailure(it)) }
    if (!view.loading && view.cards.isEmpty() && view.errorCode == null) {
        Text(if (view.demo) "مفيش تنبيهات للشركة دي." else "لا توجد أحداث.")
    }
    view.cards.forEach { card ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.title, fontWeight = FontWeight.Bold)
                Text(card.detail)
                if (card.demo) {
                    OutlinedButton(onClick = { onRead(card.id) }, enabled = !card.read) {
                        Text(if (card.read) "✓ اتقرت" else "علّم كمقروء")
                    }
                }
            }
        }
    }
}

@Composable
private fun CodesScreen() {
    SectionTitle("إصدار الأكواد")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("إصدار التراخيص الحقيقية غير مفعّل", fontWeight = FontWeight.Bold)
            Text("مافيش مفتاح خاص محفوظ هنا. الموافقة من شاشة الطلبات تسجّل القرار فقط. التوقيع يبقى في برنامج التراخيص على الكمبيوتر.")
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("إصدار كود حقيقي")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    saved: HubConfig,
    encryptionAvailable: Boolean,
    testing: Boolean,
    saving: Boolean,
    testNote: String?,
    onRequestNotifications: () -> Unit,
    onSave: (HubConfig) -> Unit,
    onClear: () -> Unit,
    onTest: (HubConfig) -> Unit
) {
    var url by remember(saved.baseUrl) { mutableStateOf(saved.baseUrl) }
    var owner by remember { mutableStateOf("") }
    var pull by remember { mutableStateOf("") }
    var clearPull by remember { mutableStateOf(false) }
    var formNote by remember { mutableStateOf<String?>(null) }
    DisposableEffect(saved) {
        owner = ""
        pull = ""
        clearPull = false
        onDispose { }
    }
    SectionTitle("الإعدادات")
    if (!encryptionAvailable) {
        Text("تعذر تجهيز الخزنة على هذا الجهاز. لن يُحفظ أي رمز.")
    }
    Text("الصق عنوان المركز ورمز المالك. يُحفظان في خزنة هذا الهاتف فقط، ولا يُكتبان داخل التطبيق.")
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("عنوان المركز") },
        placeholder = { Text("https://example.workers.dev") }
    )
    OutlinedTextField(
        value = owner,
        onValueChange = { owner = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text("رمز المالك") },
        placeholder = {
            Text(if (saved.ownerToken.isBlank()) "الصق الرمز وحده" else "محفوظ. اتركه فارغًا للإبقاء عليه")
        }
    )
    OutlinedTextField(
        value = pull,
        onValueChange = { pull = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text("رمز سحب القياسات (اختياري)") },
        placeholder = {
            Text(if (saved.pullToken.isBlank()) "اختياري" else "محفوظ. اتركه فارغًا للإبقاء عليه")
        }
    )
    Text("الصق الرمز وحده، من غير كلمة Bearer.")
    if (saved.ownerToken.isNotBlank()) Text("رمز المالك محفوظ على هذا الجهاز.")
    if (saved.pullToken.isNotBlank()) {
        Text("رمز سحب القياسات محفوظ.")
        OutlinedButton(onClick = { clearPull = !clearPull }, modifier = Modifier.fillMaxWidth()) {
            Text(if (clearPull) "✓ سيُحذف رمز السحب عند الحفظ" else "حذف رمز سحب القياسات عند الحفظ")
        }
    }
    formNote?.let { Text(it) }
    testNote?.let { Text(it) }
    Button(
        enabled = !testing && !saving,
        onClick = {
            when (val plan = planSave(saved, url, owner, pull, clearPull)) {
                is SavePlan.Reject -> formNote = arabicFailure(plan.reason)
                is SavePlan.Accept -> {
                    formNote = null
                    onTest(plan.config)
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (testing) "جارٍ الاختبار" else "اختبار الاتصال") }
    Button(
        enabled = !saving && !testing,
        onClick = {
            when (val plan = planSave(saved, url, owner, pull, clearPull)) {
                is SavePlan.Reject -> formNote = arabicFailure(plan.reason)
                is SavePlan.Accept -> {
                    onSave(plan.config)
                    formNote = null
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("حفظ") }
    OutlinedButton(
        onClick = {
            onClear()
            url = ""
            owner = ""
            pull = ""
            clearPull = false
            formNote = null
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("مسح الإعدادات") }
    OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
        Text("السماح بالإشعارات المحلية")
    }
    Text("يفحص التطبيق الطلبات كل 15 دقيقة عند وجود شبكة، ويُعلمك بالجديد فقط. لا يُستخدم Firebase.")
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    HorizontalDivider()
}

@Composable
private fun SyncStatusText(message: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        message.lines().forEach { line ->
            val arabic = line.any { it in '\u0600'..'\u06ff' }
            CompositionLocalProvider(LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Text(line, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            }
        }
    }
}
