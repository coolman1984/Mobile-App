package com.pixelplus.owner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private enum class Tab(val title: String, val emoji: String) {
    HOME("الرئيسية", "🏠"),
    COMPANIES("الشركات", "🏢"),
    REQUESTS("الطلبات", "🔑"),
    ALERTS("التنبيهات", "🔔"),
    CODES("الأكواد", "🛡️")
}

@Composable
fun OwnerApp() {
    var state by remember { mutableStateOf(DemoSeed.state()) }
    var tab by remember { mutableStateOf(Tab.HOME) }
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🧪 نسخة تجريبية ببيانات وهمية فقط", fontWeight = FontWeight.Bold)
                            Text("لا توجد اتصالات أو موافقات أو أكواد حقيقية. أي تغيير هنا يختفي عند إغلاق التطبيق.")
                        }
                    }
                    Text(
                        "العميل المعروض: " + (state.companies.firstOrNull { it.id == state.selectedCompanyId }?.name ?: "كل الشركات"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    when (tab) {
                        Tab.HOME -> HomeScreen(state) { tab = it }
                        Tab.COMPANIES -> CompaniesScreen(state) { state = state.chooseCompany(it) }
                        Tab.REQUESTS -> RequestsScreen(state) { id, next -> state = state.markRequest(id, next) }
                        Tab.ALERTS -> AlertsScreen(state) { state = state.markAlertRead(it) }
                        Tab.CODES -> CodesScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(state: DemoState, navigate: (Tab) -> Unit) {
    SectionTitle("ملخص اليوم")
    Metric("الشركات", state.companies.size.toString())
    Metric("طلبات المراجعة", state.visibleRequests().count { it.status == RequestStatus.PENDING }.toString())
    Metric("تنبيهات غير مقروءة", state.visibleAlerts().count { !it.read }.toString())
    Button(onClick = { navigate(Tab.REQUESTS) }, modifier = Modifier.fillMaxWidth()) {
        Text("افتح طلبات التفعيل")
    }
    OutlinedButton(onClick = { navigate(Tab.ALERTS) }, modifier = Modifier.fillMaxWidth()) {
        Text("راجع التنبيهات")
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
private fun CompaniesScreen(state: DemoState, choose: (String?) -> Unit) {
    SectionTitle("الشركات")
    OutlinedButton(onClick = { choose(null) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.selectedCompanyId == null) "✓ كل الشركات" else "اعرض كل الشركات")
    }
    state.companies.forEach { company ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(company.name, fontWeight = FontWeight.Bold)
                Text(company.category)
                Text("طلبات: " + state.requests.count { it.companyId == company.id })
                OutlinedButton(onClick = { choose(company.id) }) {
                    Text(if (state.selectedCompanyId == company.id) "✓ محددة" else "اختار الشركة")
                }
            }
        }
    }
}

@Composable
private fun RequestsScreen(state: DemoState, update: (String, RequestStatus) -> Unit) {
    SectionTitle("طلبات التفعيل التجريبية")
    if (state.visibleRequests().isEmpty()) Text("مفيش طلبات للشركة دي.")
    state.visibleRequests().forEach { req ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(req.id + " • " + req.product, fontWeight = FontWeight.Bold)
                Text("النوع: " + req.kind.title + " | الجهاز: " + req.deviceRef)
                Text("الحالة: " + req.status.title)
                if (req.status == RequestStatus.PENDING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { update(req.id, RequestStatus.REVIEWED) }) {
                            Text("مراجعة تجريبية")
                        }
                        OutlinedButton(onClick = { update(req.id, RequestStatus.REJECTED) }) {
                            Text("رفض تجريبي")
                        }
                    }
                }
            }
        }
    }
    Text("⚠️ المراجعة هنا محاكاة فقط. لا يوجد توقيع أو إرسال ترخيص.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AlertsScreen(state: DemoState, read: (String) -> Unit) {
    SectionTitle("التنبيهات التجريبية")
    if (state.visibleAlerts().isEmpty()) Text("مفيش تنبيهات للشركة دي.")
    state.visibleAlerts().forEach { alert ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alert.title, fontWeight = FontWeight.Bold)
                Text(alert.detail)
                OutlinedButton(onClick = { read(alert.id) }, enabled = !alert.read) {
                    Text(if (alert.read) "✓ اتقرت" else "علّم كمقروء")
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
            Text("🔒 إصدار التراخيص الحقيقية غير مفعّل", fontWeight = FontWeight.Bold)
            Text("مافيش مفتاح خاص محفوظ هنا. قبل التفعيل لازم تسجيل الموبايل وتوزيع مفتاحه العام على البرامج وإثبات الدفع للتراخيص المدفوعة.")
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("إصدار كود حقيقي")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    HorizontalDivider()
}
