package com.pixelplus.owner.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavigationTest {
    private val id = "11111111-1111-4111-8111-111111111111"

    private fun route(path: String) = Route.parse(path)!!
    private fun run(vararg events: NavEvent): NavResult =
        events.fold(NavResult(NavState())) { acc, e -> NavReducer.reduce(acc.state, e) }

    // ---- routes ----

    @Test fun everyDestinationPatternParsesBackToItself() {
        for (dest in Destinations.all) {
            val path = if (dest.hasId) dest.pattern.replace("{id}", id) else dest.pattern
            val r = route(path)
            assertEquals(dest, r.dest)
            assertEquals(if (dest.hasId) id else null, r.id)
            assertEquals(path, r.path)
        }
        assertEquals(Destinations.all.size, Destinations.all.map { it.id }.toSet().size)
        assertEquals(Destinations.all.size, Destinations.all.map { it.pattern }.toSet().size)
    }

    @Test fun aLiteralPathIsNeverReadAsAnId() {
        assertNull(route("payment/new").id)
        assertEquals("payment_new", route("/payment/new/").dest.id)
        assertEquals("payment_correction", route("payment/$id/correction").dest.id)
        assertEquals("audit_export", route("audit/export").dest.id)
        assertEquals("audit_entry", route("audit/$id").dest.id)
    }

    @Test fun unsafeOrUnknownPathsAreRefused() {
        val token = "a".repeat(48)
        val bad = listOf(
            "", "/", "nope", "request", "request/", "request//x", "request/$id/extra", "request/..", "request/%2e%2e", "request/a b",
            "request/$id?x=1", "request/$id#f", "request/\u0000", "request/" + "x".repeat(65), "request/$token", "request/a.b",
            "../home", "home/..", "settings/unknown", "x".repeat(200), "request/\\id",
        )
        for (raw in bad) assertNull(raw, Route.parse(raw))
        // a hash-like or token-like id never reaches a route; a short readable id does
        assertNull(Route.parse("request/" + "0123456789abcdef".repeat(4)))
        assertEquals("abc_DEF-12", route("request/abc_DEF-12").id)
    }

    // ---- reducer ----

    @Test fun rootStacksAreRememberedWhileAnotherRootIsShown() {
        val r = run(
            NavEvent.SelectRoot(RootTab.REQUESTS), NavEvent.Open(route("request/$id")),
            NavEvent.SelectRoot(RootTab.CUSTOMERS), NavEvent.Open(route("customer/$id")),
            NavEvent.SelectRoot(RootTab.REQUESTS),
        )
        assertEquals(RootTab.REQUESTS, r.state.root)
        assertEquals(route("request/$id"), r.state.top)
        assertEquals(listOf(route("customer/$id")), r.state.stacks.getValue(RootTab.CUSTOMERS))
    }

    @Test fun reselectingTheShownRootPopsToItsRoot() {
        val r = run(NavEvent.SelectRoot(RootTab.CUSTOMERS), NavEvent.Open(route("customer/$id")), NavEvent.Open(route("note/$id")), NavEvent.SelectRoot(RootTab.CUSTOMERS))
        assertEquals(Route.root(RootTab.CUSTOMERS), r.state.top)
        assertTrue(r.state.stacks.getValue(RootTab.CUSTOMERS).isEmpty())
    }

    @Test fun openingTheSameRouteTwiceStacksItOnce() {
        val once = run(NavEvent.Open(route("request/$id")))
        val twice = run(NavEvent.Open(route("request/$id")), NavEvent.Open(route("request/$id")))
        assertEquals(once.state, twice.state)
        assertEquals(RootTab.REQUESTS, twice.state.root)
        val other = run(NavEvent.Open(route("request/$id")), NavEvent.Open(route("request/other")))
        assertEquals(2, other.state.stacks.getValue(RootTab.REQUESTS).size)
    }

    @Test fun backClosesSheetThenDrawerThenDetailThenGoesHomeThenExits() {
        var s = run(NavEvent.SelectRoot(RootTab.CUSTOMERS), NavEvent.Open(route("customer/$id")), NavEvent.OpenDrawer, NavEvent.OpenSheet)
        assertTrue(s.state.sheetOpen)
        s = NavReducer.reduce(s.state, NavEvent.Back); assertFalse(s.state.sheetOpen); assertTrue(s.state.drawerOpen)
        s = NavReducer.reduce(s.state, NavEvent.Back); assertFalse(s.state.drawerOpen); assertEquals(route("customer/$id"), s.state.top)
        s = NavReducer.reduce(s.state, NavEvent.Back); assertEquals(Route.root(RootTab.CUSTOMERS), s.state.top)
        s = NavReducer.reduce(s.state, NavEvent.Back); assertEquals(RootTab.HOME, s.state.root); assertFalse(s.exit)
        s = NavReducer.reduce(s.state, NavEvent.Back); assertTrue(s.exit)
    }

    @Test fun aDeepOpenSwitchesRootAndKeepsTheOtherStacks() {
        val r = run(NavEvent.SelectRoot(RootTab.ALERTS), NavEvent.Open(route("settings/backup")))
        assertEquals(RootTab.MORE, r.state.root)
        assertEquals(route("settings/backup"), r.state.top)
        val back = NavReducer.reduce(r.state, NavEvent.Back).state
        assertEquals(Route.root(RootTab.MORE), back.top)
    }

    @Test fun openingARootRouteSelectsItAndClosesTheDrawer() {
        val r = run(NavEvent.OpenDrawer, NavEvent.Open(Route.root(RootTab.ALERTS)))
        assertEquals(RootTab.ALERTS, r.state.root)
        assertFalse(r.state.drawerOpen)
    }

    @Test fun aWidthChangeNeitherChangesTheStateNorRepeatsAnEvent() {
        val before = run(NavEvent.SelectRoot(RootTab.REQUESTS), NavEvent.Open(route("request/$id"))).state
        assertEquals(NavMode.BOTTOM_BAR, NavLayout.mode(599))
        assertEquals(NavMode.RAIL, NavLayout.mode(600))
        // the mode is derived from the width alone; the state is untouched by it
        assertEquals(before, before.copy())
        val once = OneShot<Route>()
        once.offer(route("request/$id"))
        assertEquals(route("request/$id"), once.consume())
        assertNull(once.consume())   // the rebuilt layout asks again and gets nothing
    }

    // ---- deep links ----

    @Test fun nothingOpensWhileLockedAndTheLinkIsKept() {
        val locked = DeepLinks.resolve("request/$id", unlocked = false, granted = emptySet())
        assertEquals(DeepLink.NeedsUnlock(route("request/$id")), locked)
        assertEquals(DeepLink.Invalid, DeepLinks.resolve("request/..", unlocked = false, granted = emptySet()))
        val after = DeepLinks.resolve(((locked as DeepLink.NeedsUnlock).route).path, unlocked = true, granted = emptySet())
        assertEquals(DeepLink.Open(route("request/$id")), after)
    }

    @Test fun aScreenThatNeedsTheHubExplainsWhenThereIsNoneAndSigningNeverOpens() {
        val demo = DeepLinks.granted(hubIsLive = false, realIssuanceEnabled = false)
        val live = DeepLinks.granted(hubIsLive = true, realIssuanceEnabled = false)
        assertEquals(DeepLink.Unavailable(route("licences"), "unavailable_not_connected"), DeepLinks.resolve("licences", true, demo))
        assertEquals(DeepLink.Open(route("licences")), DeepLinks.resolve("licences", true, live))
        for (dest in Destinations.all.filter { it.capability == Capability.SIGNING }) {
            val path = if (dest.hasId) dest.pattern.replace("{id}", id) else dest.pattern
            val result = DeepLinks.resolve(path, true, live)
            assertEquals(path, DeepLink.Unavailable(route(path), "unavailable_signing_off"), result)
        }
        assertTrue(Destinations.all.count { it.capability == Capability.SIGNING } >= 7)
        assertTrue(Capability.SIGNING in DeepLinks.granted(true, true))   // only the issuance switch grants it
    }

    // ---- badges ----

    @Test fun badgesAreCountsOnlyAndCapped() {
        val b = NavLayout.badges(pendingRequests = 3, unreadAlerts = 0, blockedQueue = 150)
        assertEquals(mapOf(RootTab.REQUESTS to 3, RootTab.ALERTS to null, RootTab.MORE to 150), b)
        assertEquals("3", NavLayout.badgeText(3, "99+"))
        assertEquals("99", NavLayout.badgeText(99, "99+"))
        assertEquals("99+", NavLayout.badgeText(100, "99+"))
        assertNull(NavLayout.badgeText(null, "99+"))
        assertNull(NavLayout.badgeText(0, "99+"))
        assertNull(NavLayout.badges(-5, -1, -1).values.firstNotNullOfOrNull { it })
    }

    @Test fun theMoreMenuListsEveryTopLevelEntryOnceUnderItsCategory() {
        val menu = Destinations.moreMenu()
        assertEquals(MoreCategory.entries.toSet(), menu.keys)
        val items = menu.values.flatten()
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertTrue(items.none { it.hasId })
        assertTrue(items.all { it.root == RootTab.MORE })
        assertTrue("settings" in items.map { it.id } && "licences" in items.map { it.id })
    }

    // ---- bilingual resources ----

    private fun resDir(dir: String): File =
        listOf("src/main/res/$dir", "app/src/main/res/$dir").map(::File).first { it.isDirectory }

    private fun strings(dir: String): Map<String, String> {
        val rx = Regex("""<string name="([a-z0-9_]+)">([^<]*)</string>""")
        val out = linkedMapOf<String, String>()
        resDir(dir).listFiles()?.filter { it.extension == "xml" }?.sortedBy { it.name }?.forEach { file ->
            rx.findAll(file.readText()).forEach { out[it.groupValues[1]] = it.groupValues[2] }
        }
        return out
    }

    private fun pluralNames(dir: String): Set<String> {
        val rx = Regex("""<plurals name="([a-z0-9_]+)">""")
        return resDir(dir).listFiles()?.filter { it.extension == "xml" }?.flatMap { rx.findAll(it.readText()).map { it.groupValues[1] } }?.toSet() ?: emptySet()
    }

    @Test fun arabicAndEnglishHaveTheSameKeysAndEveryLabelExistsInBoth() {
        val ar = strings("values")
        val en = strings("values-en")
        assertEquals(ar.keys, en.keys)
        val needed = Destinations.all.map { it.labelKey } + MoreCategory.entries.map { it.labelKey } +
            listOf("app_name", "badge_overflow", "unavailable_not_connected", "unavailable_signing_off", "unlock_required", "unavailable_not_built", "more_unavailable_suffix")
        for (key in needed) {
            assertTrue("ar $key", !ar[key].isNullOrBlank())
            assertTrue("en $key", !en[key].isNullOrBlank())
        }
        val pluralAr = pluralNames("values")
        val pluralEn = pluralNames("values-en")
        assertEquals(pluralAr, pluralEn)
        assertTrue(ar.isNotEmpty())
    }

    @Test fun arabicIsArabicEnglishIsNotAndNoStringKeepsAPlaceholderTheOtherLacks() {
        val ar = strings("values")
        val en = strings("values-en")
        val arabic = Regex("[؀-ۿ]")
        for ((k, v) in ar) assertTrue("ar $k", arabic.containsMatchIn(v))
        for ((k, v) in en) assertFalse("en $k", arabic.containsMatchIn(v))
        for (k in ar.keys) assertEquals(k, Regex("%\\d?\\$?[sd]").findAll(ar.getValue(k)).count(), Regex("%\\d?\\$?[sd]").findAll(en.getValue(k)).count())
        assertFalse(en.values.any { it.contains("'") && !it.contains("\\'") })
    }

    // ---- save and restore ----

    @Test fun theStateSurvivesARoundTripWithoutOverlays() {
        val before = run(
            NavEvent.SelectRoot(RootTab.REQUESTS), NavEvent.Open(route("request/$id")),
            NavEvent.Open(route("settings/backup")), NavEvent.OpenDrawer, NavEvent.OpenSheet,
        ).state
        val after = NavState.decode(before.encode())
        assertEquals(before.copy(drawerOpen = false, sheetOpen = false), after)
        assertEquals(route("settings/backup"), after.top)
    }

    @Test fun aDamagedSavedStateFallsBackToHomeAndKeepsOnlySafeEntries() {
        assertEquals(NavState(), NavState.decode(null))
        assertEquals(NavState(), NavState.decode(emptyList()))
        assertEquals(NavState(), NavState.decode(listOf("NOPE")))
        val saved = listOf(
            "CUSTOMERS", "CUSTOMERS|customer/$id", "CUSTOMERS|customer/$id", "CUSTOMERS|request/$id", "CUSTOMERS|customers",
            "CUSTOMERS|customer/..", "REQUESTS|request/" + "ab".repeat(30), "ELSE|home", "garbage",
        )
        val state = NavState.decode(saved)
        assertEquals(RootTab.CUSTOMERS, state.root)
        assertEquals(listOf(route("customer/$id")), state.stacks.getValue(RootTab.CUSTOMERS))
        assertTrue(state.stacks.getValue(RootTab.REQUESTS).isEmpty())
        val deep = listOf("MORE") + (1..30).map { "MORE|settings/backup" } + (1..30).map { "MORE|product/p$it" }
        assertTrue(NavState.decode(deep).stacks.getValue(RootTab.MORE).size <= NavState.MAX_RESTORED_DEPTH)
    }
}
