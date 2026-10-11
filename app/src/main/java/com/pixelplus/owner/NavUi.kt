package com.pixelplus.owner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixelplus.owner.navigation.Capability
import com.pixelplus.owner.navigation.Destinations
import com.pixelplus.owner.navigation.NavLayout
import com.pixelplus.owner.navigation.Route
import com.pixelplus.owner.navigation.RootTab

/**
 * Compose pieces for the M07 navigation model. All decisions live in `navigation/` (pure, tested); this file only draws them.
 * Strings come from `values` / `values-en` by key.
 */

/** Real code issuing is off in this build. The licence-codes screens stay "unavailable". */
internal const val REAL_ISSUANCE_ENABLED = false

/** Destinations that have a real screen in this build. Every other destination shows an honest "not available yet". */
internal val BUILT_DESTINATIONS = setOf("home", "requests", "customers", "alerts", "more", "settings", "codes")

@Composable
internal fun labelOf(key: String): String {
    val context = LocalContext.current
    return remember(key, context.resources.configuration) {
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        if (id != 0) context.getString(id) else key
    }
}

private fun emoji(tab: RootTab) = when (tab) {
    RootTab.HOME -> "🏠"
    RootTab.REQUESTS -> "🔑"
    RootTab.CUSTOMERS -> "🏢"
    RootTab.ALERTS -> "🔔"
    RootTab.MORE -> "⋯"
}

@Composable
private fun RootIcon(tab: RootTab, badge: String?) {
    if (badge == null) {
        Text(emoji(tab))
    } else {
        BadgedBox(badge = { Badge { Text(badge) } }) { Text(emoji(tab)) }
    }
}

@Composable
internal fun RootBar(selected: RootTab, badges: Map<RootTab, Int?>, onSelect: (RootTab) -> Unit) {
    val overflow = labelOf("badge_overflow")
    NavigationBar {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { RootIcon(tab, NavLayout.badgeText(badges[tab], overflow)) },
                label = { Text(labelOf("nav_" + tab.path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun RootRail(selected: RootTab, badges: Map<RootTab, Int?>, onSelect: (RootTab) -> Unit) {
    val overflow = labelOf("badge_overflow")
    NavigationRail {
        RootTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { RootIcon(tab, NavLayout.badgeText(badges[tab], overflow)) },
                label = { Text(labelOf("nav_" + tab.path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** The More tab: every top-level entry of each category. An entry without a screen yet says so; it still opens and explains. */
@Composable
internal fun MoreScreen(open: (Route) -> Unit) {
    val suffix = labelOf("more_unavailable_suffix")
    Destinations.moreMenu().forEach { (category, items) ->
        Text(labelOf(category.labelKey), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        items.forEach { dest ->
            val built = dest.id in BUILT_DESTINATIONS
            OutlinedButton(onClick = { open(Route(dest)) }, modifier = Modifier.fillMaxWidth()) {
                Text(labelOf(dest.labelKey) + if (built) "" else " · $suffix")
            }
        }
    }
}

/** Shown for a destination that is gated or has no screen yet. Never a half-open screen. */
@Composable
internal fun UnavailableScreen(route: Route, reasonKey: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(labelOf(route.dest.labelKey), fontWeight = FontWeight.Bold)
            Text(labelOf(reasonKey))
        }
    }
}

internal fun reasonFor(route: Route, granted: Set<Capability>): String? {
    val need = route.dest.capability
    return when {
        need == Capability.SIGNING && need !in granted -> "unavailable_signing_off"
        need != null && need !in granted -> "unavailable_not_connected"
        route.dest.id !in BUILT_DESTINATIONS -> "unavailable_not_built"
        else -> null
    }
}
