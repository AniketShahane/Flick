package com.flick.sender.ui.screens

import com.flick.sender.model.ConnectionStatus
import com.flick.sender.net.PairedTv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Devices screen still explains that the TV needs Flick on it too.
 *
 * The fact is unlearnable from anywhere else in the app: a TV without the receiver never
 * advertises, so it is missing from the list rather than listed as unavailable, and a
 * phone whose owner has not been told is looking at a screen that reports nothing wrong.
 */
class TvAppNoteVisibilityTest {

    private fun paired() = PairedTv(
        name = "Living Room",
        host = "192.168.42.17",
        port = 8009,
        tvId = "tv-a",
    )

    /**
     * No record of either kind covers both of the phones that need this: the one that has
     * never paired, and the one whose attempts keep failing because the TV app is the
     * missing piece. Neither ever writes a record, and the second is who the note exists
     * for.
     */
    @Test
    fun aPhoneWithNoStoredPairingIsTold() {
        assertTrue(showTvAppNote(null, pairedEarlier = false))
    }

    @Test
    fun aStoredPairingRetiresIt() {
        assertFalse(showTvAppNote(paired(), pairedEarlier = true))
    }

    /**
     * The v1 user, mid-migration: a legacy record is retired only by a visible v2 pair at
     * the same host, so until that happens `connectedTv` is null while the phone has very
     * much reached a receiver before. Reading the v2 record alone would tell the one user
     * who knows better that their TV needs the app.
     */
    @Test
    fun aLegacyPairingWithNoV2RecordYetRetiresIt() {
        assertFalse(showTvAppNote(null, pairedEarlier = true))
    }

    /**
     * The returning user: paired weeks ago, casting nothing tonight. The record and the
     * live link disagree here deliberately — a rule that read the link would put the note
     * back on that user's screen every time they opened Devices.
     */
    @Test
    fun aPairingThatIsNotLiveRightNowStillCounts() {
        assertFalse(linkLive(ConnectionStatus.DISCONNECTED, paired()))
        assertFalse(showTvAppNote(paired(), pairedEarlier = true))
    }
}
