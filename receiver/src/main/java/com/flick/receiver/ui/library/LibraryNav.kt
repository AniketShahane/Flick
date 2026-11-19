package com.flick.receiver.ui.library

import com.flick.receiver.library.LibraryCatalog

internal enum class LibraryTab { HOME, SHOWS, FILMS, ALL }

/** A place inside the library screen. */
internal sealed interface LibraryRoute {
    data class Tab(val tab: LibraryTab) : LibraryRoute
    data class Show(val showKey: String) : LibraryRoute
    data class Detail(val itemId: String) : LibraryRoute
    data object Search : LibraryRoute
}

/**
 * One back-stack entry. [serial] keys the entry's remembered scroll and focus, so a route
 * pushed twice (a show opened from Home and again from Search) keeps two memories. A tab's
 * serial is fixed, so its memory survives switching away and back.
 */
internal data class LibraryEntry(val serial: Long, val route: LibraryRoute)

/**
 * The library's internal back stack. The top bar switches tabs by replacing the stack; Show,
 * Detail and Search are pushed over whatever is showing. Back pops; at a tab other than Home
 * it returns to Home; at Home it is not the library's to handle, so the app's own Back
 * applies, exactly as it does on the idle screen.
 */
internal object LibraryNav {

    /** Deep enough for Search → Show → Detail and back; a loop of opens cannot grow without bound. */
    const val MAX_DEPTH = 12

    fun tabEntry(tab: LibraryTab): LibraryEntry = LibraryEntry(-(tab.ordinal + 1L), LibraryRoute.Tab(tab))

    fun root(): List<LibraryEntry> = listOf(tabEntry(LibraryTab.HOME))

    fun select(tab: LibraryTab): List<LibraryEntry> = listOf(tabEntry(tab))

    fun push(stack: List<LibraryEntry>, route: LibraryRoute, serial: Long): List<LibraryEntry> {
        if (stack.lastOrNull()?.route == route) return stack
        val next = stack + LibraryEntry(serial, route)
        return if (next.size > MAX_DEPTH) listOf(next.first()) + next.takeLast(MAX_DEPTH - 1) else next
    }

    /** The stack after Back, or null when Back is not the library's to handle. */
    fun back(stack: List<LibraryEntry>): List<LibraryEntry>? = when {
        stack.size > 1 -> stack.dropLast(1)
        stack.singleOrNull()?.route != LibraryRoute.Tab(LibraryTab.HOME) -> root()
        else -> null
    }

    /**
     * [stack] without entries whose item or show the phone no longer lists — a patch removed
     * the file, or the last episode of a show. The root tab always stays.
     */
    fun pruned(stack: List<LibraryEntry>, catalog: LibraryCatalog, showKeys: Set<String>): List<LibraryEntry> {
        val kept = stack.filterIndexed { index, entry ->
            index == 0 || when (val route = entry.route) {
                is LibraryRoute.Detail -> catalog[route.itemId] != null
                is LibraryRoute.Show -> route.showKey in showKeys
                else -> true
            }
        }
        return if (kept.size == stack.size) stack else kept
    }

    /** The tab under [stack]'s pushed pages; the one a page's breadcrumb names. */
    fun rootTab(stack: List<LibraryEntry>): LibraryTab =
        (stack.firstOrNull()?.route as? LibraryRoute.Tab)?.tab ?: LibraryTab.HOME
}
