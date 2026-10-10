package com.pixelplus.owner

/**
 * Fake in-memory data only. This module must not acquire a network, signing or payment dependency.
 * Keep pure transition rules here so Claude can replace the demo repository behind a reviewed API.
 */
data class Company(val id: String, val name: String, val category: String)
enum class LicenceKind(val title: String) {
    TRIAL("تجربة"), MONTHLY("شهري"), PERPETUAL("دائم")
}
enum class RequestStatus(val title: String) {
    PENDING("في الانتظار"), REVIEWED("تمت المراجعة التجريبية"), REJECTED("مرفوض تجريبيًا")
}
data class ActivationRequest(
    val id: String,
    val companyId: String,
    val product: String,
    val deviceRef: String,
    val kind: LicenceKind,
    val status: RequestStatus = RequestStatus.PENDING
)
data class OwnerAlert(
    val id: String,
    val companyId: String,
    val title: String,
    val detail: String,
    val read: Boolean = false
)
data class DemoState(
    val companies: List<Company>,
    val requests: List<ActivationRequest>,
    val alerts: List<OwnerAlert>,
    val selectedCompanyId: String? = null
) {
    // A real issuing API does not exist in this foundation. NEVER treat REVIEWED as signed.
    val realIssuanceEnabled: Boolean get() = false

    fun visibleRequests(): List<ActivationRequest> =
        requests.filter { selectedCompanyId == null || it.companyId == selectedCompanyId }

    fun visibleAlerts(): List<OwnerAlert> =
        alerts.filter { selectedCompanyId == null || it.companyId == selectedCompanyId }

    fun chooseCompany(id: String?): DemoState =
        if (id == null || companies.any { it.id == id }) copy(selectedCompanyId = id) else this

    fun markRequest(id: String, next: RequestStatus): DemoState {
        if (next == RequestStatus.PENDING) return this
        val target = visibleRequests().firstOrNull { it.id == id } ?: return this
        if (target.status != RequestStatus.PENDING) return this
        return copy(requests = requests.map { if (it.id == id) it.copy(status = next) else it })
    }

    fun markAlertRead(id: String): DemoState {
        if (visibleAlerts().none { it.id == id }) return this
        return copy(alerts = alerts.map { if (it.id == id) it.copy(read = true) else it })
    }
}

object DemoSeed {
    fun state(): DemoState = DemoState(
        companies = listOf(
            Company("demo-1", "شركة تجريبية للأدوات", "تجارة"),
            Company("demo-2", "شركة تجريبية للنقل", "خدمات"),
            Company("demo-3", "شركة تجريبية للتعليم", "تعليم")
        ),
        requests = listOf(
            ActivationRequest("REQ-101", "demo-1", "الستور", "DEMO-A1", LicenceKind.TRIAL),
            ActivationRequest("REQ-102", "demo-1", "الستور", "DEMO-A2", LicenceKind.MONTHLY),
            ActivationRequest("REQ-103", "demo-2", "إدارة النقل", "DEMO-B1", LicenceKind.PERPETUAL),
            ActivationRequest("REQ-104", "demo-3", "إدارة الدروس", "DEMO-C1", LicenceKind.TRIAL)
        ),
        alerts = listOf(
            OwnerAlert("AL-1", "demo-1", "طلب تفعيل جديد", "جهاز تجريبي ينتظر المراجعة"),
            OwnerAlert("AL-2", "demo-2", "تنبيه نسخة احتياطية", "محاكاة تنبيه، وليس عطلًا حقيقيًا"),
            OwnerAlert("AL-3", "demo-3", "تجديد قريب", "محاكاة انتهاء اشتراك")
        )
    )
}
