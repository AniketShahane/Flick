package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a control link that has gone quiet is allowed to cost the viewer their film.
 *
 * The failure this answers happened on real hardware: a cast playing perfectly, a
 * subtitle attached mid-film, thirty-three seconds of control silence, and then a ping
 * timeout that ended a cast the phone was still serving at ~58 Mbps. The TV had not gone
 * anywhere and the film was on screen the whole time.
 */
class ControlRecoveryPolicyTest {

    /**
     * The cast that was lost. Bytes a second ago means the TV is pulling them, and that
     * is a better witness about right now than a watchdog whose shortest possible verdict
     * is thirty seconds old.
     */
    @Test fun aTvStillPullingBytesHasNotGoneAway() {
        assertTrue(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 120_000L, nowMs = 121_000L))
    }

    /**
     * The case the old behaviour was right about, and which must not change: nothing has
     * left this phone for the TV either, so the two witnesses agree.
     */
    @Test fun aMediaSocketThatWentQuietTooIsAnAbsentTv() {
        assertFalse(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 120_000L, nowMs = 180_000L))
    }

    /**
     * A cast that never moved a byte has proven nothing about the link. Zero is the
     * absence of a reading, not a reading of zero, and reading it as "long ago" is what
     * keeps a startup that never got going out of the recovery path.
     */
    @Test fun aCastThatNeverServedAByteIsNeverServing() {
        assertFalse(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 0L, nowMs = 0L))
        assertFalse(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 0L, nowMs = 5_000L))
    }

    /**
     * The window has to sit under the watchdog it second-guesses. The control link cannot
     * be reported lost sooner than 30 s of missing pong, so a TV that vanished has already
     * fallen out of this window by the time anything consults it — which is the whole
     * reason the media path may overrule the WebSocket at all.
     */
    @Test fun theServingWindowClosesLongBeforeAPingTimeoutCanFire() {
        assertTrue(ControlRecoveryPolicy.SERVING_WINDOW_MS < ControlClient.PING_INTERVAL_MS * 2)
    }

    /** A first loss, and one after a long healthy stretch, are both attempt one. */
    @Test fun aLossWithNoRecentRecoveryStartsTheRun() {
        assertEquals(1, ControlRecoveryPolicy.attempt(previousAttempts = 0, lastAttemptAtMs = 0L, nowMs = 5_000L))
        assertEquals(
            1,
            ControlRecoveryPolicy.attempt(previousAttempts = 2, lastAttemptAtMs = 1_000L, nowMs = 3_600_000L),
        )
    }

    /** Two inside the window are one broken link, not two incidents. */
    @Test fun lossesInsideTheWindowAccumulate() {
        assertEquals(2, ControlRecoveryPolicy.attempt(previousAttempts = 1, lastAttemptAtMs = 10_000L, nowMs = 30_000L))
        assertEquals(3, ControlRecoveryPolicy.attempt(previousAttempts = 2, lastAttemptAtMs = 30_000L, nowMs = 40_000L))
    }

    @Test fun aServingTvOnAnActiveCastIsRecovered() {
        assertTrue(
            ControlRecoveryPolicy.recovers(
                reachedActive = true,
                mediaServing = true,
                canDial = true,
                attempt = 1,
            ),
        )
    }

    /**
     * The bound. A third attempt inside the same minute would be this app reconnecting in
     * a loop the viewer can neither see nor stop, so the failure is surfaced instead —
     * retryable, which is what leaves them a way back.
     */
    @Test fun theRunIsBounded() {
        assertTrue(recoversAt(ControlRecoveryPolicy.MAX_ATTEMPTS))
        assertFalse(recoversAt(ControlRecoveryPolicy.MAX_ATTEMPTS + 1))
    }

    /**
     * Only a cast the TV was already playing. A startup that lost its control link has no
     * position worth resuming and no proof the receiver can play the file at all, so
     * re-dialing one would be retrying a cast the viewer has never seen work.
     */
    @Test fun aStartupThatNeverReachedTheFilmIsNotRecovered() {
        assertFalse(
            ControlRecoveryPolicy.recovers(
                reachedActive = false,
                mediaServing = true,
                canDial = true,
                attempt = 1,
            ),
        )
    }

    /** The idle media socket keeps the behaviour this phone has always had. */
    @Test fun anAbsentTvIsStillAnAbsentTv() {
        assertFalse(
            ControlRecoveryPolicy.recovers(
                reachedActive = true,
                mediaServing = false,
                canDial = true,
                attempt = 1,
            ),
        )
    }

    /** No stored pairing or no cast request means there is nothing to re-establish with. */
    @Test fun nothingToDialIsNothingToRecover() {
        assertFalse(
            ControlRecoveryPolicy.recovers(
                reachedActive = true,
                mediaServing = true,
                canDial = false,
                attempt = 1,
            ),
        )
    }

    private fun recoversAt(attempt: Int) = ControlRecoveryPolicy.recovers(
        reachedActive = true,
        mediaServing = true,
        canDial = true,
        attempt = attempt,
    )

    /**
     * The window that waits a router block out carries [recovers]' conservatism: a cast the
     * TV was playing, and something to dial with. It says nothing about the fault, because
     * at the moment a control socket dies nothing has been dialled yet.
     */
    @Test fun onlyAPlayingCastWithAPairingIsWaitedOut() {
        assertTrue(ControlRecoveryPolicy.waitsOutLoss(reachedActive = true, canDial = true))
        assertFalse(ControlRecoveryPolicy.waitsOutLoss(reachedActive = false, canDial = true))
        assertFalse(ControlRecoveryPolicy.waitsOutLoss(reachedActive = true, canDial = false))
    }

    /**
     * The measured fingerprint and nothing else. EHOSTUNREACH for a TV mDNS is still
     * advertising is the router declining to forward, and that fault clears itself; silence
     * is a TV that is switched off and an RST is one that is not listening, and neither
     * improves by being waited for.
     */
    @Test fun onlyTheNoRouteFingerprintKeepsTheWindowOpen() {
        assertTrue(waitsOn(DialFault.NO_ROUTE))
        assertFalse(waitsOn(DialFault.NO_ANSWER))
        assertFalse(waitsOn(DialFault.REFUSED))
        assertFalse(waitsOn(DialFault.NO_NETWORK))
        assertFalse(waitsOn(DialFault.REJECTED))
        assertFalse(waitsOn(null))
    }

    /**
     * The other half of that fingerprint, and the half a socket cannot supply. A TV that was
     * unplugged mid-film loses its neighbour entry on this phone, and from then on the
     * kernel answers a dial to it with the block's own EHOSTUNREACH — so the fault alone
     * would keep the window open, and the face under it promising the film back, for a TV
     * that is off.
     */
    @Test fun aTvThatAnswersNoAdvertisementIsNotWaitedFor() {
        assertFalse(waitsOn(DialFault.NO_ROUTE, freshlyAdvertised = false))
    }

    /**
     * Both measured blocks — 13 m 43 s and about 20 m — have to fall inside the window, or
     * the app would stop checking while the fault it was built for was still running.
     */
    @Test fun theWindowOutlastsTheBlocksThatWereMeasured() {
        assertTrue(ControlRecoveryPolicy.waiting(armedAtMs = 1_000L, nowMs = 1_000L + 13L * 60_000L + 43_000L))
        assertTrue(waitsOn(DialFault.NO_ROUTE, nowMs = 1_000L + 19L * 60_000L))
        assertFalse(waitsOn(DialFault.NO_ROUTE, nowMs = 1_000L + 21L * 60_000L))
    }

    private fun waitsOn(
        fault: DialFault?,
        freshlyAdvertised: Boolean = true,
        nowMs: Long = 61_000L,
    ) = ControlRecoveryPolicy.waitsOn(fault, freshlyAdvertised, armedAtMs = 1_000L, nowMs = nowMs)

    /** Zero is no window at all, not one that opened at the boot of the phone. */
    @Test fun anUnarmedWindowIsNotOpen() {
        assertFalse(ControlRecoveryPolicy.waiting(armedAtMs = 0L, nowMs = 0L))
        assertFalse(ControlRecoveryPolicy.waiting(armedAtMs = 0L, nowMs = 5_000L))
    }

    /**
     * The cadence: doubling, and then a plateau. The cap is the whole decision — a dial is
     * a few packets, so the only thing a longer plateau buys is a viewer sitting in front of
     * a path that came back minutes ago.
     */
    @Test fun theBackoffDoublesToTheCapAndStaysThere() {
        assertEquals(5_000L, ControlRecoveryPolicy.retryDelayMs(1, jitter = 0.0))
        assertEquals(10_000L, ControlRecoveryPolicy.retryDelayMs(2, jitter = 0.0))
        assertEquals(20_000L, ControlRecoveryPolicy.retryDelayMs(3, jitter = 0.0))
        assertEquals(ControlRecoveryPolicy.BLOCK_RETRY_CAP_MS, ControlRecoveryPolicy.retryDelayMs(4, jitter = 0.0))
        assertEquals(ControlRecoveryPolicy.BLOCK_RETRY_CAP_MS, ControlRecoveryPolicy.retryDelayMs(50, jitter = 0.0))
    }

    /**
     * Jitter only ever shortens: the cap is a promise about the worst case, and a phone
     * whose ticks fell into step with a periodic timer on the far side would sample the same
     * phase of it every time.
     */
    @Test fun jitterOnlyEverShortensTheWait() {
        for (attempt in 1..8) {
            val plain = ControlRecoveryPolicy.retryDelayMs(attempt, jitter = 0.0)
            for (draw in listOf(0.01, 0.5, 0.99, 1.0)) {
                val jittered = ControlRecoveryPolicy.retryDelayMs(attempt, draw)
                assertTrue("attempt $attempt draw $draw exceeded its wait", jittered <= plain)
                assertTrue("attempt $attempt draw $draw collapsed", jittered >= plain * 3 / 4)
            }
        }
    }

    /** A draw outside 0..1 is a caller bug and must not produce a negative wait. */
    @Test fun anImpossibleDrawStillProducesAWait() {
        assertEquals(5_000L, ControlRecoveryPolicy.retryDelayMs(1, jitter = -3.0))
        assertEquals(3_750L, ControlRecoveryPolicy.retryDelayMs(1, jitter = 4.0))
        assertTrue(ControlRecoveryPolicy.retryDelayMs(0, jitter = 0.0) > 0L)
    }

    /**
     * The window has to hold many more attempts than it has room for waits, or the cadence
     * would run out before the fault does.
     */
    @Test fun theCadenceFillsTheWindowManyTimesOver() {
        var spentMs = 0L
        var attempts = 0
        while (spentMs < ControlRecoveryPolicy.BLOCK_WINDOW_MS) {
            attempts += 1
            spentMs += ControlRecoveryPolicy.retryDelayMs(attempts, jitter = 0.0)
        }
        assertTrue("only $attempts attempts fit the window", attempts > 20)
    }
}
