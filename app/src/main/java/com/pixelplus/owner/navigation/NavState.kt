package com.pixelplus.owner.navigation

/**
 * Five root stacks, each remembered while another is shown. Pure and deterministic: the same events give the same state.
 *
 * Back closes a sheet first, then the drawer, then the top detail of the current stack; a root other than Home goes back to Home;
 * Home asks to leave the app. Choosing the root that is already shown pops to its root (single top). Pushing the route that is
 * already on top does nothing, so a double tap or a recomposition cannot stack a screen twice.
 */
data class NavState(
    val root: RootTab = RootTab.HOME,
    val stacks: Map<RootTab, List<Route>> = RootTab.entries.associateWith { emptyList() },
    val drawerOpen: Boolean = false,
    val sheetOpen: Boolean = false,
) {
    val top: Route get() = stacks.getValue(root).lastOrNull() ?: Route.root(root)

    /**
     * What survives a rotation or the process being recreated: the shown root and every stack, as plain strings that hold only
     * paths (opaque ids, never a secret). Overlays are closed on restore. Entries that no longer parse are dropped.
     */
    fun encode(): List<String> = buildList {
        add(root.name)
        RootTab.entries.forEach { tab -> stacks.getValue(tab).forEach { add("${tab.name}|${it.path}") } }
    }

    companion object {
        const val MAX_RESTORED_DEPTH = 8

        fun decode(saved: List<String>?): NavState {
            val root = saved?.firstOrNull()?.let { name -> RootTab.entries.firstOrNull { it.name == name } } ?: return NavState()
            val stacks = RootTab.entries.associateWith { mutableListOf<Route>() }
            saved.drop(1).forEach { line ->
                val tab = RootTab.entries.firstOrNull { line.startsWith(it.name + "|") } ?: return@forEach
                val route = Route.parse(line.substring(tab.name.length + 1)) ?: return@forEach
                if (route.dest.root != tab || route.dest.pattern == tab.path) return@forEach
                val stack = stacks.getValue(tab)
                if (stack.size < MAX_RESTORED_DEPTH && stack.lastOrNull() != route) stack += route
            }
            return NavState(root = root, stacks = stacks.mapValues { it.value.toList() })
        }
    }
}

sealed class NavEvent {
    data class SelectRoot(val tab: RootTab) : NavEvent()
    data class Open(val route: Route) : NavEvent()
    data object Back : NavEvent()
    data object OpenDrawer : NavEvent()
    data object CloseDrawer : NavEvent()
    data object OpenSheet : NavEvent()
    data object CloseSheet : NavEvent()
}

data class NavResult(val state: NavState, val exit: Boolean = false)

object NavReducer {
    fun reduce(state: NavState, event: NavEvent): NavResult = when (event) {
        is NavEvent.SelectRoot -> NavResult(select(state, event.tab))
        is NavEvent.Open -> NavResult(open(state, event.route))
        NavEvent.OpenDrawer -> NavResult(state.copy(drawerOpen = true, sheetOpen = false))
        NavEvent.CloseDrawer -> NavResult(state.copy(drawerOpen = false))
        NavEvent.OpenSheet -> NavResult(state.copy(sheetOpen = true))
        NavEvent.CloseSheet -> NavResult(state.copy(sheetOpen = false))
        NavEvent.Back -> back(state)
    }

    private fun select(state: NavState, tab: RootTab): NavState {
        val closed = state.copy(drawerOpen = false, sheetOpen = false)
        return if (tab == state.root) closed.copy(stacks = closed.stacks + (tab to emptyList())) else closed.copy(root = tab)
    }

    private fun open(state: NavState, route: Route): NavState {
        val base = state.copy(drawerOpen = false, sheetOpen = false)
        val tab = route.dest.root
        if (route.dest.pattern == tab.path) return select(base, tab)
        val stack = base.stacks.getValue(tab)
        if (stack.lastOrNull() == route) return base.copy(root = tab)   // single top
        return base.copy(root = tab, stacks = base.stacks + (tab to stack + route))
    }

    private fun back(state: NavState): NavResult {
        if (state.sheetOpen) return NavResult(state.copy(sheetOpen = false))
        if (state.drawerOpen) return NavResult(state.copy(drawerOpen = false))
        val stack = state.stacks.getValue(state.root)
        if (stack.isNotEmpty()) return NavResult(state.copy(stacks = state.stacks + (state.root to stack.dropLast(1))))
        if (state.root != RootTab.HOME) return NavResult(state.copy(root = RootTab.HOME))
        return NavResult(state, exit = true)
    }
}

/** A navigation event the screen must act on exactly once, even if the layout is rebuilt (rotation, a width change) before it is consumed. */
class OneShot<T : Any> {
    private var pending: T? = null
    fun offer(value: T) { pending = value }
    fun consume(): T? = pending.also { pending = null }
}
