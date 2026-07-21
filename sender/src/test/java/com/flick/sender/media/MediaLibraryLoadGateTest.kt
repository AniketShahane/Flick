package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryLoadGateTest {
    @Test fun resultFromBeforePermissionRevocationCannotRestoreItems() {
        val gate = MediaLibraryLoadGate()
        val partial = gate.begin(MediaAccess.PARTIAL)
        gate.begin(MediaAccess.NONE)
        var items = emptyList<String>()

        assertFalse(gate.runIfLatest(partial) { items = listOf("stale") })
        assertTrue(items.isEmpty())
    }

    @Test fun olderReselectionCannotOverwriteTheNewerMediaStoreSnapshot() {
        val gate = MediaLibraryLoadGate()
        val first = gate.begin(MediaAccess.PARTIAL)
        val second = gate.begin(MediaAccess.PARTIAL)
        var items = emptyList<String>()

        assertTrue(gate.runIfLatest(second) { items = listOf("new selection") })
        assertFalse(gate.runIfLatest(first) { items = listOf("old selection") })
        assertEquals(listOf("new selection"), items)
    }

    @Test fun newestPermissionSnapshotCanPublish() {
        val gate = MediaLibraryLoadGate()
        val full = gate.begin(MediaAccess.FULL)
        var published = false

        assertTrue(gate.runIfLatest(full) { published = true })
        assertTrue(published)
    }
}
