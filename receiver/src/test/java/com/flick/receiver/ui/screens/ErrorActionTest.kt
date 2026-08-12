package com.flick.receiver.ui.screens

import com.flick.receiver.session.ReceiverErrorFace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one key on the terminal screen may not offer more than the state behind it.
 *
 * "End session" over a control link that is provably answering is the same overreach
 * the before-the-first-frame copy exists to undo: the pre-flight probe raises the
 * unreachable face while the socket that delivered the load is still up, and only the
 * phone's file server failed.
 */
class ErrorActionTest {

    @Test fun aProbeRefusalOverALiveLinkOffersStandbyRatherThanAnEnding() {
        assertFalse(ReceiverErrorFace.PHONE_UNREACHABLE.endsTheSession(beforeReady = true))
    }

    @Test fun aPhoneThatStoppedAnsweringMidFilmStillOffersToEndTheSession() {
        assertTrue(ReceiverErrorFace.PHONE_UNREACHABLE.endsTheSession(beforeReady = false))
    }

    /** The link is gone on both sides of the first frame, so this one never changes. */
    @Test fun aLostLinkEndsTheSessionWheneverItWentAway() {
        assertTrue(ReceiverErrorFace.LINK_LOST.endsTheSession(beforeReady = true))
        assertTrue(ReceiverErrorFace.LINK_LOST.endsTheSession(beforeReady = false))
    }

    /** Every other face has nothing left to end, whenever it was raised. */
    @Test fun noOtherFaceClaimsThereIsASessionToEnd() {
        for (face in ReceiverErrorFace.entries) {
            if (face == ReceiverErrorFace.PHONE_UNREACHABLE || face == ReceiverErrorFace.LINK_LOST) continue
            assertFalse("$face beforeReady", face.endsTheSession(beforeReady = true))
            assertFalse("$face midFilm", face.endsTheSession(beforeReady = false))
        }
    }
}
