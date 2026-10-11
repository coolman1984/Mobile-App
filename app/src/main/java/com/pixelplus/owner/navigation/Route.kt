package com.pixelplus.owner.navigation

/**
 * A place in the app: a destination plus at most one opaque id. No Android types.
 *
 * [parse] is the only way to make a Route from outside text (a deep link, a saved state, a notification). It refuses anything that
 * could carry a secret or escape the table: ids are 1 to 64 characters of letters, digits, `_` and `-`, a long hex string (a token or a
 * hash) is refused, and so is a query, a fragment, a dot-dot or an unknown path.
 */
data class Route(val dest: Dest, val id: String? = null) {
    val path: String
        get() = if (id == null) dest.pattern else dest.pattern.replace("{id}", id)

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val LONG_HEX = Regex("^[0-9a-fA-F]{40,}$")

        fun root(tab: RootTab) = Route(Destinations.root(tab))

        fun parse(raw: String): Route? {
            if (raw.length > 160 || raw.any { it.isISOControl() || it == '?' || it == '#' || it == '%' || it == '\\' }) return null
            val parts = raw.trim('/').split('/')
            if (parts.any { it.isEmpty() }) return null
            // literal patterns win over {id} patterns, so `payment/new` is never read as an id
            Destinations.all.firstOrNull { !it.hasId && it.pattern == parts.joinToString("/") }?.let { return Route(it) }
            for (dest in Destinations.all.filter { it.hasId }) {
                val shape = dest.pattern.split('/')
                if (shape.size != parts.size) continue
                var id: String? = null
                var ok = true
                for (i in shape.indices) {
                    if (shape[i] == "{id}") { id = parts[i]; if (!ID.matches(parts[i]) || LONG_HEX.matches(parts[i])) ok = false }
                    else if (shape[i] != parts[i]) ok = false
                }
                if (ok && id != null) return Route(dest, id)
            }
            return null
        }
    }
}
