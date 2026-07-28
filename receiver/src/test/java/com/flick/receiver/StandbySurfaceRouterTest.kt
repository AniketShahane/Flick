package com.flick.receiver

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.PairingSurface
import com.flick.receiver.session.MediaStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one invariant `PairingManager` states about itself and cannot enforce alone:
 * a code is never valid while nothing on screen renders it.
 *
 * The manager owns the closing half — `closeSurface` drops the code, and a seal
 * refuses every reopen. The opening half is here, because only the app knows which
 * surface the router is about to draw. `onForeground` used to guess it from the
 * paired count and was wrong in exactly one reachable state: Settings, opened from
 * the pair screen a factory-fresh TV cannot leave any other way, still on screen
 * after a screensaver took the activity through stop/start.
 */
class StandbySurfaceRouterTest {
    private val standby = PairingSurface.Standby
    private val open = PairingSurface.Open("0000", 7L, 1_000L)
    private val locked = PairingSurface.Locked(7L, 1_000L)
    private val sealed = PairingSurface.Sealed
    private val success = PairingSurface.Success("Phone", 7L)

    private val stages = listOf(
        MediaStage.None,
        MediaStage.Checking("cast", 1L),
        MediaStage.Preparing("cast", 1L),
        MediaStage.Active("cast", 1L),
        MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L),
    )
    private val surfaces = listOf(standby, open, locked, sealed, success)

    // --- The finding ---------------------------------------------------------

    /**
     * The proved sequence: pair screen → Settings (which closes the surface) →
     * screensaver → ON_START. The composition survived, so Settings is still what
     * the router draws, and the code the old rule would have minted here had
     * nothing behind it.
     */
    @Test fun comingBackIntoSettingsOpensNoCode() {
        assertFalse(
            pairingSurfaceRendered(MediaStage.None, showSettings = true, surface = standby, pairedCount = 0),
        )
        // And not for any surface state Settings can be entered from, either.
        surfaces.forEach { surface ->
            assertFalse(
                "Settings outranks Pair, so nothing under it renders a code",
                pairingSurfaceRendered(MediaStage.None, showSettings = true, surface = surface, pairedCount = 0),
            )
        }
    }

    /** The case the whole surface exists for still works. */
    @Test fun aTvWithNothingPairedSittingOnThePairScreenStillGetsACode() {
        assertTrue(
            pairingSurfaceRendered(MediaStage.None, showSettings = false, surface = standby, pairedCount = 0),
        )
        assertEquals(
            StandbySurface.Pair,
            standbySurfaceFor(showSettings = false, surface = standby, pairedCount = 0),
        )
    }

    /**
     * A lockout that was still running when the screensaver arrived comes back to
     * its own countdown. `closeSurface` published Standby on the way out, so the
     * ask has to happen for the deadline to be republished — the manager, not this
     * decision, is what turns it back into a countdown rather than a code.
     */
    @Test fun aRunningLockoutIsAskedForAgainOnTheWayBack() {
        assertTrue(
            pairingSurfaceRendered(MediaStage.None, showSettings = false, surface = standby, pairedCount = 0),
        )
        assertEquals(
            StandbySurface.Pair,
            standbySurfaceFor(showSettings = false, surface = locked, pairedCount = 0),
        )
        // …but not while Settings is the surface that survived the stop/start.
        assertFalse(
            pairingSurfaceRendered(MediaStage.None, showSettings = true, surface = locked, pairedCount = 0),
        )
    }

    /**
     * A seal is rendered — the pair screen is what carries the resume control — so
     * this says yes, and the manager says no. That split is deliberate: the ask
     * reaches `requestOpen`, which refuses while sealed and republishes the seal.
     * Nothing here can un-seal a surface, and Settings still outranks it.
     */
    @Test fun aSealIsRenderedByThePairScreenAndNeverBySettings() {
        assertTrue(
            pairingSurfaceRendered(MediaStage.None, showSettings = false, surface = sealed, pairedCount = 0),
        )
        assertFalse(
            pairingSurfaceRendered(MediaStage.None, showSettings = true, surface = sealed, pairedCount = 0),
        )
    }

    /** A cast on screen draws no code, whatever the router would otherwise choose. */
    @Test fun aCastOnScreenRendersNoCode() {
        stages.filterNot { it is MediaStage.None }.forEach { stage ->
            assertFalse(
                "$stage covers the standby surfaces entirely",
                pairingSurfaceRendered(stage, showSettings = false, surface = standby, pairedCount = 0),
            )
        }
    }

    /**
     * The load-bearing property, over every state the app can come back in: if a
     * code may be opened, the router is drawing the screen that shows it.
     */
    @Test fun everyStateThatMayOpenACodeIsOneThePairScreenIsDrawnFor() {
        stages.forEach { stage ->
            listOf(false, true).forEach { showSettings ->
                surfaces.forEach { surface ->
                    (0..2).forEach { pairedCount ->
                        if (pairingSurfaceRendered(stage, showSettings, surface, pairedCount)) {
                            assertTrue(
                                "only MediaStage.None draws a standby surface at all",
                                stage is MediaStage.None,
                            )
                            assertFalse("Settings outranks Pair", showSettings)
                            assertEquals(
                                "a code may only be opened for the pair screen",
                                StandbySurface.Pair,
                                standbySurfaceFor(showSettings, surface, pairedCount),
                            )
                        }
                    }
                }
            }
        }
    }

    // --- The router itself ---------------------------------------------------

    @Test fun settingsOutranksEverySurfaceBeneathIt() {
        surfaces.forEach { surface ->
            (0..2).forEach { pairedCount ->
                assertEquals(
                    StandbySurface.Settings,
                    standbySurfaceFor(showSettings = true, surface = surface, pairedCount = pairedCount),
                )
            }
        }
    }

    /**
     * With phones paired the `pairedCount` arm would send a seal to Idle, and the TV
     * would sit there having silently stopped accepting codes — the one thing the
     * ceiling must never look like.
     */
    @Test fun aSealRoutesToThePairScreenAtEveryPairedCount() {
        (0..3).forEach { pairedCount ->
            assertEquals(
                StandbySurface.Pair,
                standbySurfaceFor(showSettings = false, surface = sealed, pairedCount = pairedCount),
            )
        }
    }

    @Test fun belowTheSealTheRouterIsUnchanged() {
        assertEquals(StandbySurface.Pair, standbySurfaceFor(false, open, pairedCount = 2))
        assertEquals(StandbySurface.Pair, standbySurfaceFor(false, locked, pairedCount = 2))
        assertEquals(StandbySurface.Pair, standbySurfaceFor(false, standby, pairedCount = 0))
        assertEquals(StandbySurface.PairSuccess, standbySurfaceFor(false, success, pairedCount = 1))
        assertEquals(StandbySurface.Idle, standbySurfaceFor(false, standby, pairedCount = 1))
    }
}
