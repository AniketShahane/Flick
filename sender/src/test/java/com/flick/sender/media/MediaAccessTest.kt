package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccessTest {
    @Test fun fullAccessWinsWhenAndroidReportsBothGrants() {
        assertEquals(MediaAccess.FULL, MediaAccess.fromGrants(fullGranted = true, partialGranted = true))
        assertFalse(MediaAccess.FULL.canReselect)
    }

    @Test fun selectedOnlyAccessKeepsReselectionAvailable() {
        assertEquals(MediaAccess.PARTIAL, MediaAccess.fromGrants(fullGranted = false, partialGranted = true))
        assertTrue(MediaAccess.PARTIAL.canReselect)
    }

    @Test fun noGrantDoesNotExposeTheLibraryOrReselectionAction() {
        assertEquals(MediaAccess.NONE, MediaAccess.fromGrants(fullGranted = false, partialGranted = false))
        assertFalse(MediaAccess.NONE.canReselect)
    }

    @Test fun partialAccessSelectsMoreWhileFullAccessRefreshesWithoutAnotherPermissionFlow() {
        assertEquals(MediaLibraryAction.SELECT_MORE, MediaLibraryActionPolicy.forAccess(MediaAccess.PARTIAL))
        assertEquals(MediaLibraryAction.REFRESH, MediaLibraryActionPolicy.forAccess(MediaAccess.FULL))
        assertEquals(MediaLibraryAction.HIDDEN, MediaLibraryActionPolicy.forAccess(MediaAccess.NONE))
    }
}
