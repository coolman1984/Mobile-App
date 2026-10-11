package com.pixelplus.owner.navigation

/**
 * Decides what a deep link may do. Nothing opens while the app is locked: the link is kept, the owner unlocks, and the link is decided
 * again with the capabilities that are granted at that moment. An unknown or unsafe path is refused whether the app is locked or not.
 */
sealed class DeepLink {
    data class Open(val route: Route) : DeepLink()
    data class NeedsUnlock(val route: Route) : DeepLink()
    data class Unavailable(val route: Route, val reasonKey: String) : DeepLink()
    data object Invalid : DeepLink()
}

object DeepLinks {
    fun resolve(raw: String, unlocked: Boolean, granted: Set<Capability>): DeepLink {
        val route = Route.parse(raw) ?: return DeepLink.Invalid
        if (!unlocked) return DeepLink.NeedsUnlock(route)
        val need = route.dest.capability ?: return DeepLink.Open(route)
        if (need in granted) return DeepLink.Open(route)
        return DeepLink.Unavailable(route, if (need == Capability.SIGNING) "unavailable_signing_off" else "unavailable_not_connected")
    }

    /** What this build grants. Signing is never in the set while `realIssuanceEnabled` is false. */
    fun granted(hubIsLive: Boolean, realIssuanceEnabled: Boolean): Set<Capability> = buildSet {
        if (hubIsLive) add(Capability.LIVE_HUB)
        if (realIssuanceEnabled) add(Capability.SIGNING)
    }
}
