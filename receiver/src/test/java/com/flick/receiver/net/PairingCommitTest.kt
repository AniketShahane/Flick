package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCommitTest {
    @Test fun failedDurableWriteCannotConsumeOrPublishPairing() {
        var consumed = false
        assertFalse(commitPairing(commit = { false }, afterCommit = { consumed = true }))
        assertFalse(consumed)
        assertTrue(commitPairing(commit = { true }, afterCommit = { consumed = true }))
        assertTrue(consumed)
    }

    @Test fun failedForgetWriteLeavesExistingPairingStateUntouched() {
        var cleared = false
        assertFalse(commitForgetPairings(commit = { false }, afterCommit = { cleared = true }))
        assertFalse(cleared)
        assertTrue(commitForgetPairings(commit = { true }, afterCommit = { cleared = true }))
        assertTrue(cleared)
    }
}
