package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StillMemoryTest {

    @Test fun aVerdictComesBackOut() {
        val memory = StillMemory(limit = 4)
        memory.remember("a", StillVerdict.ProviderThumbnail)
        memory.remember("b", StillVerdict.Searched(2_000L, passed = true))
        assertEquals(StillVerdict.ProviderThumbnail, memory.verdict("a"))
        assertEquals(StillVerdict.Searched(2_000L, passed = true), memory.verdict("b"))
        assertNull(memory.verdict("c"))
    }

    @Test fun theEldestVerdictIsTheOneDropped() {
        // A library holds thousands of files and the memo holds hundreds; what it drops
        // costs one search the next time that file is looked at.
        val memory = StillMemory(limit = 3)
        for (key in listOf("a", "b", "c")) memory.remember(key, StillVerdict.ProviderThumbnail)
        memory.remember("d", StillVerdict.ProviderThumbnail)
        assertNull(memory.verdict("a"))
        assertNotNull(memory.verdict("b"))
        assertNotNull(memory.verdict("c"))
        assertNotNull(memory.verdict("d"))
    }

    @Test fun rewritingAVerdictAtTheBoundDropsNobody() {
        // Evicting on every write at the bound cost an unrelated file its verdict — and so
        // one search — for a key that was already here and took no new room.
        val memory = StillMemory(limit = 3)
        for (key in listOf("a", "b", "c")) memory.remember(key, StillVerdict.ProviderThumbnail)
        memory.remember("c", StillVerdict.Searched(9_000L, passed = false))
        assertNotNull(memory.verdict("a"))
        assertNotNull(memory.verdict("b"))
        assertEquals(StillVerdict.Searched(9_000L, passed = false), memory.verdict("c"))
    }

    @Test fun aForgottenVerdictIsGone() {
        // What a stale verdict is dropped with: the next sighting searches again.
        val memory = StillMemory(limit = 3)
        memory.remember("a", StillVerdict.Searched(1_000L, passed = true))
        memory.forget("a")
        assertNull(memory.verdict("a"))
    }
}
