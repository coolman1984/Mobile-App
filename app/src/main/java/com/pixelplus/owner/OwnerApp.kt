package com.pixelplus.owner

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.pixelplus.owner.domain.Kpi
import com.pixelplus.owner.domain.RequestFact
import com.pixelplus.owner.domain.RequestTab
import com.pixelplus.owner.domain.RequestTabs
import com.pixelplus.owner.navigation.DeepLinks
import com.pixelplus.owner.ui.theme.Appearance
import com.pixelplus.owner.navigation.Destinations
import com.pixelplus.owner.navigation.NavEvent
import com.pixelplus.owner.navigation.NavLayout
import com.pixelplus.owner.navigation.NavMode
import com.pixelplus.owner.navigation.NavReducer
import com.pixelplus.owner.navigation.NavState
import com.pixelplus.owner.navigation.RootTab
import com.pixelplus.owner.navigation.Route
import com.pixelplus.owner.ui.AppLanguage
import com.pixelplus.owner.ui.bothLines
import com.pixelplus.owner.ui.LocaleAudit
import com.pixelplus.owner.ui.LocaleStore
import com.pixelplus.owner.ui.hubAlertDetail
import com.pixelplus.owner.ui.labelOf
import com.pixelplus.owner.ui.liveRequestDetail
import com.pixelplus.owner.ui.localizedHubEvent
import com.pixelplus.owner.ui.localizedLicenceKind
import com.pixelplus.owner.ui.localizedOwnerDecision
import com.pixelplus.owner.ui.text
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class PendingDecision(val id: String, val action: OwnerAction, val kind: String)

@Composable
fun OwnerApp(prefs: OwnerPrefs, reauthenticate: ((Boolean) -> Unit) -> Unit = { it(true) }, onRequestNotifications: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val store = remember { SecureHubStore(context.applicationContext) }
    val client = remember { HubClient(AndroidHubTransport()) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var config by remember { mutableStateOf(store.read()) }
    var demo by remember { mutableStateOf(DemoSeed.state()) }
    var nav by rememberSaveable(
        stateSaver = Saver<NavState, ArrayList<String>>(save = { ArrayList(it.encode()) }, restore = { NavState.decode(it) }),
    ) { mutableStateOf(NavState()) }
    val dispatch: (NavEvent) -> Unit = { event -> nav = NavReducer.reduce(nav, event).state }
    var generation by remember { mutableStateOf(0) }
    var appearance by remember { mutableStateOf(prefs.appearance()) }
    var lockPrefs by remember { mutableStateOf(prefs.lock()) }
    var tabFilter by remember { mutableStateOf<RequestTab?>(null) }
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
    val localeStore = remember { LocaleStore(context.applicationContext) }
    var appLanguage by remember { mutableStateOf(localeStore.read()) }

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
    val alertsView = presentAlerts(context, config, demo, alertsLoading, alertsError, liveEvents, pullSummary, pullError)
    val companiesView = presentCompanies(context, config, demo, liveRequests)

    val resolved = Appearance.resolve(appearance, isSystemInDarkTheme(), LocalConfiguration.current.fontScale, Build.VERSION.SDK_INT)
    val requestFacts = liveRequests?.map { RequestFact(it.id, "pending", it.ownerDecision, it.createdAt) }
    val kpi = requestFacts?.let { Kpi.summarize(it, System.currentTimeMillis()) }
    val tabById = requestFacts?.associate { it.id to RequestTabs.of(it) }.orEmpty()
    val shownRequests = if (tabFilter == null || requestsView.demo) requestsView else requestsView.copy(cards = requestsView.cards.filter { tabById[it.id] == tabFilter })
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        OwnerTheme(resolved) {
            val mode = NavLayout.mode(LocalConfiguration.current.screenWidthDp)
            val badges = NavLayout.badges(
                pendingRequests = requestsView.cards.count { it.pending },
                unreadAlerts = alertsView.cards.count { !it.read },
                blockedQueue = 0,
            )
            val granted = DeepLinks.granted(config.isLive, REAL_ISSUANCE_ENABLED)
            BackHandler(
                enabled = nav.sheetOpen || nav.drawerOpen || nav.stacks.getValue(nav.root).isNotEmpty() || nav.root != RootTab.HOME,
            ) { dispatch(NavEvent.Back) }
            Scaffold(
                bottomBar = {
                    if (mode == NavMode.BOTTOM_BAR) RootBar(nav.root, badges) { dispatch(NavEvent.SelectRoot(it)) }
                }
            ) { inner ->
              Row(Modifier.padding(inner)) {
                if (mode == NavMode.RAIL) RootRail(nav.root, badges) { dispatch(NavEvent.SelectRoot(it)) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(labelOf("brand_short"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(labelOf("home_tagline"), style = MaterialTheme.typography.titleMedium)
                    if (config.isLive) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(labelOf("hub_configured_title"), fontWeight = FontWeight.Bold)
                                Text(labelOf("hub_configured_body", hostOnly(config.baseUrl)))
                            }
                        }
                    } else {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(labelOf("demo_banner_title"), fontWeight = FontWeight.Bold)
                                Text(labelOf("demo_banner_body"))
                            }
                        }
                    }
                    if (!config.isLive) {
                        val clientName = demo.companies.firstOrNull { it.id == demo.selectedCompanyId }?.name
                            ?: context.text("demo_all_companies")
                        Text(
                            labelOf("demo_client_label", clientName),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (config.isLive && cachedAt != null) SyncStatusText(com.pixelplus.owner.sync.SyncCopy.cachedSnapshot(context, cachedAt!!).display)
                    if (config.isLive && queueLine.isNotBlank()) SyncStatusText(queueLine)
                    decisionMessage?.let { message -> SyncStatusText(message) }
                    val here = nav.top
                    if (here.dest.root.path != here.dest.pattern) {
                        Text(labelOf(here.dest.labelKey), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    val blocked = reasonFor(here, granted)
                    when (if (blocked != null && here.dest.id != "codes") "unavailable" else here.dest.id) {
                        "unavailable" -> UnavailableScreen(here, blocked ?: "unavailable_not_built")
                        "home" -> {
                            kpi?.let { KpiCards(it) }
                            HomeScreen(homeLines(context, config, demo, liveRequests, liveEvents)) { dispatch(NavEvent.Open(it)) }
                        }
                        "more" -> MoreScreen { dispatch(NavEvent.Open(it)) }
                        "customers" -> CustomersScreen()
                        "requests" -> {
                          if (!requestsView.demo) RequestFilterButtons(tabFilter) { tabFilter = it }
                          RequestsScreen(
                            view = shownRequests,
                            deciding = deciding,
                            onDemo = { id, next -> demo = demo.markRequest(id, next) },
                            onDecide = { card, action -> pendingDecision = PendingDecision(card.id, action, card.kind) },
                            onRefresh = { generation += 1 }
                        )
                        }
                        "alerts" -> AlertsScreen(
                            view = alertsView,
                            onRead = { demo = demo.markAlertRead(it) },
                            onRefresh = { generation += 1 }
                        )
                        "codes" -> CodesScreen()
                        "settings" -> SettingsScreen(
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
                                        decisionMessage = if (savedOk) context.bothLines("settings_saved").display else context.bothLines("settings_save_failed").display
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
                                            decisionMessage = context.bothLines("settings_cleared").display
                                        } else decisionMessage = context.bothLines("settings_clear_failed").display
                                    }
                                }.start()
                            },
                            onTest = { draft ->
                                testing = true
                                settingsNote = null
                                Thread {
                                    val result = client.pending(draft, limit = 1)
                                    val message = if (result is HubResult.Ok) {
                                        context.text("hub_test_ok", result.value.size)
                                    } else {
                                        failureMessage(context, failureCode(result))
                                    }
                                    main.post {
                                        if (activity?.isDestroyed == true) return@post
                                        testing = false
                                        settingsNote = message
                                    }
                                }.start()
                            }
                        )
                        "settings_appearance" -> AppearanceScreen(appearance) { appearance = it; prefs.saveAppearance(it) }
                        "settings_language" -> LanguageScreen(appLanguage) { picked ->
                            val before = appLanguage
                            appLanguage = picked
                            CoroutineScope(Dispatchers.IO).launch {
                                localeStore.save(picked)
                                LocaleAudit.logChange(context.applicationContext, before, picked)
                            }
                        }
                        "settings_lock" -> LockSettingsScreen(lockPrefs, deviceIsSecure(context)) { lockPrefs = it; prefs.saveLock(it) }
                        "settings_backup" -> BackupScreen(reauthenticate)
                        "activity" -> ActivityScreen(config)
                        "licences" -> LicenceHistoryScreen(config)
                        "installs" -> InstallsScreen(config)
                        "money" -> MoneyScreen()
                        "followups" -> FollowUpsScreen()
                        else -> UnavailableScreen(here, "unavailable_not_built")
                    }
                }
              }
            }
            pendingDecision?.let { choice ->
                AlertDialog(
                    onDismissRequest = { if (!deciding) pendingDecision = null },
                    title = { Text(labelOf(if (choice.action == OwnerAction.APPROVE) "confirm_approve_title" else "confirm_refuse_title")) },
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
                                    val message = if (request == null) com.pixelplus.owner.sync.SyncCopy.settingsChanged(context).display else runCatching {
                                        com.pixelplus.owner.sync.OwnerSync.enqueue(context.applicationContext, snapshot, request, choice.action)
                                    }.getOrElse { context.bothLines("decision_save_failed").display }
                                    main.post {
                                        if (activity?.isDestroyed == true) return@post
                                        deciding = false
                                        decisionMessage = if (config == snapshot) message else com.pixelplus.owner.sync.SyncCopy.settingsChanged(context).display
                                        generation += 1
                                    }
                                }.start()
                            }
                        ) { Text(labelOf("confirm")) }
                    },
                    dismissButton = {
                        TextButton(enabled = !deciding, onClick = { pendingDecision = null }) { Text(labelOf("cancel")) }
                    }
                )
            }
        }
    }
}

@Composable
private fun confirmText(choice: PendingDecision): String {
    val ctx = LocalContext.current
    val head = ctx.text(if (choice.action == OwnerAction.APPROVE) "confirm_approve_body" else "confirm_refuse_body")
    val extra = when (choice.kind) {
        "monthly", "permanent" -> ctx.text("confirm_paid_extra")
        "trial" -> if (choice.action == OwnerAction.APPROVE) ctx.text("confirm_trial_extra") else ""
        else -> ""
    }
    return head + extra
}

@Composable
private fun HomeScreen(lines: List<HomeLine>, navigate: (Route) -> Unit) {
    SectionTitle(labelOf("home_summary_title"))
    lines.forEach { line -> Metric(line.label, line.value) }
    Button(onClick = { navigate(Route.root(RootTab.REQUESTS)) }, modifier = Modifier.fillMaxWidth()) {
        Text(labelOf("open_activation_requests"))
    }
    OutlinedButton(onClick = { navigate(Route.root(RootTab.ALERTS)) }, modifier = Modifier.fillMaxWidth()) {
        Text(labelOf("review_alerts"))
    }
    OutlinedButton(onClick = { navigate(Route(Destinations.byId.getValue("settings"))) }, modifier = Modifier.fillMaxWidth()) {
        Text(labelOf("settings"))
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
    SectionTitle(labelOf(if (view.demo) "companies" else "products"))
    view.note?.let { Text(it) }
    if (view.demo) {
        OutlinedButton(onClick = { choose(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (demo.selectedCompanyId == null) labelOf("all_companies_selected") else labelOf("show_all_companies"))
        }
        demo.companies.forEach { company ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(company.name, fontWeight = FontWeight.Bold)
                    Text(company.category)
                    Text(labelOf("requests_count", demo.requests.count { it.companyId == company.id }))
                    OutlinedButton(onClick = { choose(company.id) }) {
                        Text(if (demo.selectedCompanyId == company.id) labelOf("company_selected") else labelOf("choose_company"))
                    }
                }
            }
        }
    } else if (view.names.isEmpty()) {
        Text(labelOf("no_pending_requests"))
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
    SectionTitle(labelOf(if (view.demo) "demo_requests_title" else "live_requests_title"))
    if (!view.demo) {
        Text(labelOf("approval_records_only"))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(labelOf("refresh")) }
    }
    if (view.loading) Text(labelOf("loading"))
    view.errorCode?.let { Text(failureMessage(LocalContext.current, it)) }
    if (!view.loading && view.cards.isEmpty() && view.errorCode == null) {
        Text(if (view.demo) labelOf("no_requests_demo") else labelOf("no_pending_requests"))
    }
    val context = LocalContext.current
    view.cards.forEach { card ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (card.demo) card.title else card.title + " • " + context.localizedLicenceKind(card.kind),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (card.demo) {
                        val parts = card.detail.split("\t", limit = 2)
                        context.text("request_detail_kind_demo", parts.first(), parts.getOrElse(1) { "" })
                    } else {
                        context.liveRequestDetail(card)
                    },
                )
                Text(
                    if (card.demo) {
                        labelOf("status_prefix", card.status)
                    } else {
                        labelOf("status_prefix", context.localizedOwnerDecision(card.ownerDecision))
                    },
                )
                if (card.demo && card.pending) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDemo(card.id, RequestStatus.REVIEWED) }) { Text(labelOf("demo_review")) }
                        OutlinedButton(onClick = { onDemo(card.id, RequestStatus.REJECTED) }) { Text(labelOf("demo_reject")) }
                    }
                }
                if (!card.demo && (card.canApprove || card.canReject)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (card.canApprove) {
                            Button(enabled = !deciding, onClick = { onDecide(card, OwnerAction.APPROVE) }) { Text(labelOf("approve")) }
                        }
                        if (card.canReject) {
                            OutlinedButton(enabled = !deciding, onClick = { onDecide(card, OwnerAction.REFUSE) }) { Text(labelOf("refuse")) }
                        }
                    }
                }
            }
        }
    }
    if (view.truncated) Text(labelOf("truncated_requests"))
    if (view.demo) {
        Text(labelOf("demo_review_note"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlertsScreen(view: AlertsPresentation, onRead: (String) -> Unit, onRefresh: () -> Unit) {
    SectionTitle(labelOf(if (view.demo) "demo_alerts_title" else "hub_log_title"))
    if (!view.demo) {
        Text(labelOf("read_only_hub"))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(labelOf("refresh")) }
    }
    if (view.loading) Text(labelOf("loading"))
    view.errorCode?.let { Text(failureMessage(LocalContext.current, it)) }
    view.pullNote?.let { Text(it) }
    view.pullSummary?.let { Text(it) }
    view.pullError?.let { Text(failureMessage(LocalContext.current, it)) }
    if (!view.loading && view.cards.isEmpty() && view.errorCode == null) {
        Text(if (view.demo) labelOf("no_alerts_demo") else labelOf("no_events"))
    }
    val context = LocalContext.current
    view.cards.forEach { card ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (card.demo) card.title else context.localizedHubEvent(card.eventKind), fontWeight = FontWeight.Bold)
                Text(if (card.demo) card.detail else context.hubAlertDetail(card))
                if (card.demo) {
                    OutlinedButton(onClick = { onRead(card.id) }, enabled = !card.read) {
                        Text(if (card.read) labelOf("alert_read") else labelOf("mark_read"))
                    }
                }
            }
        }
    }
}

@Composable
private fun CodesScreen() {
    SectionTitle(labelOf("codes_section"))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(labelOf("codes_disabled_title"), fontWeight = FontWeight.Bold)
            Text(labelOf("codes_disabled_body"))
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(labelOf("issue_real_code"))
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
    SectionTitle(labelOf("settings"))
    if (!encryptionAvailable) {
        Text(labelOf("encryption_unavailable"))
    }
    Text(labelOf("hub_settings_intro"))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(labelOf("hub_url_label")) },
        placeholder = { Text(labelOf("hub_url_placeholder")) }
    )
    OutlinedTextField(
        value = owner,
        onValueChange = { owner = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(labelOf("owner_token_label")) },
        placeholder = {
            Text(if (saved.ownerToken.isBlank()) labelOf("paste_token_only") else labelOf("token_saved_leave_blank"))
        }
    )
    OutlinedTextField(
        value = pull,
        onValueChange = { pull = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(labelOf("pull_token_label")) },
        placeholder = {
            Text(if (saved.pullToken.isBlank()) labelOf("optional") else labelOf("token_saved_leave_blank"))
        }
    )
    Text(labelOf("no_bearer"))
    if (saved.ownerToken.isNotBlank()) Text(labelOf("owner_token_saved"))
    if (saved.pullToken.isNotBlank()) {
        Text(labelOf("pull_token_saved"))
        OutlinedButton(onClick = { clearPull = !clearPull }, modifier = Modifier.fillMaxWidth()) {
            Text(if (clearPull) labelOf("pull_will_clear") else labelOf("clear_pull_on_save"))
        }
    }
    formNote?.let { Text(it) }
    testNote?.let { Text(it) }
    Button(
        enabled = !testing && !saving,
        onClick = {
            when (val plan = planSave(saved, url, owner, pull, clearPull)) {
                is SavePlan.Reject -> formNote = failureMessage(LocalContext.current, plan.reason)
                is SavePlan.Accept -> {
                    formNote = null
                    onTest(plan.config)
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (testing) labelOf("testing_connection") else labelOf("test_connection")) }
    Button(
        enabled = !saving && !testing,
        onClick = {
            when (val plan = planSave(saved, url, owner, pull, clearPull)) {
                is SavePlan.Reject -> formNote = failureMessage(LocalContext.current, plan.reason)
                is SavePlan.Accept -> {
                    onSave(plan.config)
                    formNote = null
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(labelOf("save")) }
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
    ) { Text(labelOf("clear_settings")) }
    OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
        Text(labelOf("allow_local_notifications"))
    }
    Text(labelOf("hub_poll_note"))
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
