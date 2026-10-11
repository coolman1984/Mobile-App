package com.pixelplus.owner.navigation

/**
 * Every destination of the owner app, in one table (M07). No Android types, no Navigation Compose: the app does not ship that library.
 *
 * A path holds only opaque IDs (`{id}`), never a token, a contact text, a money reference or a code ([Route.parse] refuses those).
 * `labelKey` is the name of a string resource that exists in `values` (Arabic) and `values-en`; a test holds the three in step.
 * A destination whose [capability] is not granted explains why; it never half-opens. This table is generated once from the plan's
 * navigation map (IMPLEMENTATION_PLAN section 3); edit it and the two string files together.
 */
enum class RootTab(val path: String) {
    HOME("home"), REQUESTS("requests"), CUSTOMERS("customers"), ALERTS("alerts"), MORE("more"),
}

enum class MoreCategory(val labelKey: String) {
    BUSINESS("cat_business"),
    OPERATIONS("cat_operations"),
    SYSTEM("cat_system"),
}

/** What a destination needs before it can open. [SIGNING] is never granted in this build. */
enum class Capability { LIVE_HUB, SIGNING }

data class Dest(
    val id: String,
    val pattern: String,
    val root: RootTab,
    val moreCategory: MoreCategory?,
    val capability: Capability?,
) {
    val labelKey: String get() = if (this.root.path == pattern) "nav_$id" else "dest_$id"
    val hasId: Boolean get() = "{id}" in pattern
}

object Destinations {
    val all: List<Dest> = listOf(
    Dest("home", "home", RootTab.HOME, null, null),
    Dest("requests", "requests", RootTab.REQUESTS, null, null),
    Dest("customers", "customers", RootTab.CUSTOMERS, null, null),
    Dest("alerts", "alerts", RootTab.ALERTS, null, null),
    Dest("more", "more", RootTab.MORE, null, null),
    Dest("request", "request/{id}", RootTab.REQUESTS, null, null),
    Dest("customer", "customer/{id}", RootTab.CUSTOMERS, null, null),
    Dest("note", "note/{id}", RootTab.CUSTOMERS, null, null),
    Dest("task", "task/{id}", RootTab.CUSTOMERS, null, null),
    Dest("customer_install", "customer-install/{id}", RootTab.CUSTOMERS, null, Capability.LIVE_HUB),
    Dest("licences", "licences", RootTab.MORE, MoreCategory.BUSINESS, Capability.LIVE_HUB),
    Dest("licence", "licence/{id}", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("expiry_calendar", "expiry-calendar", RootTab.MORE, MoreCategory.BUSINESS, Capability.LIVE_HUB),
    Dest("money", "money", RootTab.MORE, MoreCategory.BUSINESS, null),
    Dest("payment_new", "payment/new", RootTab.MORE, null, null),
    Dest("payment_correction", "payment/{id}/correction", RootTab.MORE, null, null),
    Dest("products", "products", RootTab.MORE, MoreCategory.BUSINESS, null),
    Dest("product", "product/{id}", RootTab.MORE, null, null),
    Dest("installs", "installs", RootTab.MORE, MoreCategory.OPERATIONS, Capability.LIVE_HUB),
    Dest("install", "install/{id}", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("registration", "registration/{id}", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("telemetry", "telemetry", RootTab.MORE, MoreCategory.OPERATIONS, Capability.LIVE_HUB),
    Dest("tickets", "tickets", RootTab.MORE, MoreCategory.OPERATIONS, Capability.LIVE_HUB),
    Dest("ticket", "ticket/{id}", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("support", "support", RootTab.MORE, MoreCategory.OPERATIONS, Capability.LIVE_HUB),
    Dest("grant", "grant/{id}", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("repair", "repair/{id}", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("activity", "activity", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("audit_entry", "audit/{id}", RootTab.MORE, null, null),
    Dest("audit_export", "audit/export", RootTab.MORE, null, null),
    Dest("sync", "sync", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("operation", "operation/{id}", RootTab.MORE, null, null),
    Dest("trash", "trash", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("settings", "settings", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("settings_hub", "settings/hub", RootTab.MORE, null, null),
    Dest("settings_appearance", "settings/appearance", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("settings_language", "settings/language", RootTab.MORE, null, null),
    Dest("settings_lock", "settings/lock", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("settings_notifications", "settings/notifications", RootTab.MORE, null, null),
    Dest("settings_backup", "settings/backup", RootTab.MORE, MoreCategory.SYSTEM, null),
    Dest("settings_alert_rules", "settings/alert-rules", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("settings_agent_access", "settings/agent-access", RootTab.MORE, null, Capability.LIVE_HUB),
    Dest("settings_help", "settings/help", RootTab.MORE, null, null),
    Dest("codes", "codes", RootTab.MORE, MoreCategory.SYSTEM, Capability.SIGNING),
    Dest("codes_verify", "codes/verify", RootTab.MORE, null, Capability.SIGNING),
    Dest("codes_public_key", "codes/public-key", RootTab.MORE, null, Capability.SIGNING),
    Dest("codes_recovery", "codes/recovery", RootTab.MORE, null, Capability.SIGNING),
    Dest("codes_issue", "codes/issue", RootTab.MORE, null, Capability.SIGNING),
    Dest("codes_review", "codes/review", RootTab.MORE, null, Capability.SIGNING),
    Dest("codes_result", "codes/result", RootTab.MORE, null, Capability.SIGNING),
    )

    val byId: Map<String, Dest> = all.associateBy { it.id }

    fun root(tab: RootTab): Dest = all.first { it.pattern == tab.path }

    /** The "More" tab: the top-level entries of each category, in table order. */
    fun moreMenu(): Map<MoreCategory, List<Dest>> =
        MoreCategory.entries.associateWith { cat -> all.filter { it.moreCategory == cat } }
}
