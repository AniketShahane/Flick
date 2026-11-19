package com.flick.receiver.ui.library

import com.flick.receiver.library.LibraryCatalog
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryNavTest {

    @Test
    fun backPopsThenReturnsHomeThenLeavesItToTheApp() {
        val shows = LibraryNav.select(LibraryTab.SHOWS)
        val pushed = LibraryNav.push(shows, LibraryRoute.Show("tv:1"), serial = 1L)
        assertEquals(shows, LibraryNav.back(pushed))
        assertEquals(LibraryNav.root(), LibraryNav.back(shows))
        assertNull(LibraryNav.back(LibraryNav.root()))
    }

    @Test
    fun aTabKeepsOneSerialSoItsMemorySurvivesSwitching() {
        assertEquals(LibraryNav.select(LibraryTab.ALL).single().serial, LibraryNav.select(LibraryTab.ALL).single().serial)
        val serials = LibraryTab.entries.map { LibraryNav.tabEntry(it).serial }
        assertEquals(serials.size, serials.toSet().size)
    }

    @Test
    fun pushingTheShowingRouteAgainIsANoOp() {
        val stack = LibraryNav.push(LibraryNav.root(), LibraryRoute.Search, serial = 1L)
        assertSame(stack, LibraryNav.push(stack, LibraryRoute.Search, serial = 2L))
    }

    @Test
    fun depthIsBoundedAndKeepsTheRoot() {
        var stack = LibraryNav.root()
        for (i in 1..40) stack = LibraryNav.push(stack, LibraryRoute.Detail("id$i"), serial = i.toLong())
        assertEquals(LibraryNav.MAX_DEPTH, stack.size)
        assertEquals(LibraryNav.root().first(), stack.first())
        assertEquals(LibraryRoute.Detail("id40"), stack.last().route)
    }

    @Test
    fun pruningDropsPagesForItemsAndShowsThatAreGone() {
        val item = LibraryItem(id = "kept", name = "k.mkv", title = "k", sortKey = "k", find = "k", kind = LibraryItemKind.FILM)
        val catalog = LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", listOf(item))
        var stack = LibraryNav.select(LibraryTab.FILMS)
        stack = LibraryNav.push(stack, LibraryRoute.Show("tv:gone"), 1L)
        stack = LibraryNav.push(stack, LibraryRoute.Detail("kept"), 2L)
        stack = LibraryNav.push(stack, LibraryRoute.Detail("gone"), 3L)
        val pruned = LibraryNav.pruned(stack, catalog, showKeys = emptySet())
        assertEquals(listOf(LibraryRoute.Tab(LibraryTab.FILMS), LibraryRoute.Detail("kept")), pruned.map { it.route })
        assertSame(pruned, LibraryNav.pruned(pruned, catalog, emptySet()))
        assertEquals(LibraryTab.FILMS, LibraryNav.rootTab(pruned))
    }
}
