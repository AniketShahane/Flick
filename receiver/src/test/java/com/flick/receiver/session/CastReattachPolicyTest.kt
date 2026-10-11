package com.flick.receiver.session

import com.flick.receiver.net.ControlLossReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may pick a held film back up, and when a film is held at all. */
class CastReattachPolicyTest {

    @Test fun onlyADroppedSocketUnderAPlayingFilmInFrontIsHeld() {
        assertTrue(CastReattachPolicy.entersGrace(ControlLossReason.DROPPED, active = true, foreground = true))
        assertFalse(CastReattachPolicy.entersGrace(ControlLossReason.DROPPED, active = false, foreground = true))
        assertFalse(CastReattachPolicy.entersGrace(ControlLossReason.DROPPED, active = true, foreground = false))
    }

    /** This TV's own doing is never mistaken for a network fault worth waiting out. */
    @Test fun nothingThisTvDidOnPurposeIsHeld() {
        for (reason in ControlLossReason.values().filter { it != ControlLossReason.DROPPED }) {
            assertFalse(reason.wire, CastReattachPolicy.entersGrace(reason, active = true, foreground = true))
        }
    }

    @Test fun theSamePhoneCastAndUrlInsideTheWindowReadopt() {
        assertTrue(CastReattachPolicy.readopts(HELD, KEY, CAST, URL, nowElapsedMs = DEADLINE - 1L))
    }

    @Test fun anotherPhoneNeverReadopts() {
        assertFalse(CastReattachPolicy.readopts(HELD, "other-key", CAST, URL, nowElapsedMs = 0L))
    }

    @Test fun anotherCastNeverReadopts() {
        assertFalse(CastReattachPolicy.readopts(HELD, KEY, "other-cast", URL, nowElapsedMs = 0L))
    }

    /** A phone whose address moved serves from somewhere the running player cannot reach. */
    @Test fun aDifferentUrlNeverReadopts() {
        assertFalse(CastReattachPolicy.readopts(HELD, KEY, CAST, "http://192.168.42.18:8080/v/token", nowElapsedMs = 0L))
    }

    @Test fun theWindowIsExclusiveAtItsEnd() {
        assertFalse(CastReattachPolicy.readopts(HELD, KEY, CAST, URL, nowElapsedMs = DEADLINE))
    }

    @Test fun nothingHeldReadoptsNothing() {
        assertFalse(CastReattachPolicy.readopts(null, KEY, CAST, URL, nowElapsedMs = 0L))
    }

    /** A loss that could not name its phone can only run out. */
    @Test fun aHoldWithNoPhoneCannotBeReadopted() {
        assertFalse(CastReattachPolicy.readopts(HELD.copy(keyId = null), KEY, CAST, URL, nowElapsedMs = 0L))
    }

    @Test fun aTakenOverCastContinuesOnlyWhilePlayingTheSameFilm() {
        assertTrue(CastReattachPolicy.continues(CAST, URL, active = true, castId = CAST, url = URL))
        assertFalse(CastReattachPolicy.continues(CAST, URL, active = false, castId = CAST, url = URL))
        assertFalse(CastReattachPolicy.continues(CAST, URL, active = true, castId = "other-cast", url = URL))
        assertFalse(CastReattachPolicy.continues(CAST, URL, active = true, castId = CAST, url = "http://192.168.42.18:8080/v/token"))
        assertFalse(CastReattachPolicy.continues(null, null, active = true, castId = CAST, url = URL))
    }

    /** The phone gives up at 40 s; its last attempt has to land inside this hold. */
    @Test fun theHoldOutlastsThePhonesReattachWindow() {
        assertTrue(CastReattachPolicy.ORPHAN_GRACE_MS > 40_000L)
    }

    private companion object {
        const val KEY = "key-a"
        const val CAST = "cast-a"
        const val URL = "http://192.168.42.17:8080/v/token"
        const val DEADLINE = 45_000L
        val HELD = OrphanedCast(KEY, CAST, URL, DEADLINE)
    }
}
