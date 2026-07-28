package com.flick.sender.ui.screens

import androidx.compose.ui.geometry.Offset
import com.flick.sender.ui.components.PressToSummon
import com.flick.sender.ui.components.PressVerdict
import com.flick.sender.ui.components.RevealOrigin
import com.flick.sender.ui.components.RevealTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remote has two controls that open the same sheet — the signal chip at the top and
 * the Metrics segment at the foot — and the shell serves both from one channel. Every
 * case below is a way the sheet could be born at the wrong control.
 *
 * The coordinates are the two ends of a 384 × 832 dp phone, chosen so a swapped origin
 * is unmistakable rather than plausible.
 */
class QualityRevealOriginTest {
    private val topChip = Offset(342f, 68f)
    private val metricsSegment = Offset(304f, 706f)

    @Test
    fun theSheetIsBornAtTheControlThatWasPressedLast() {
        val origin = RevealOrigin(RevealTarget.QUALITY)
        origin.record(topChip)
        origin.record(metricsSegment)
        val spent: Offset? = origin.consume(RevealTarget.QUALITY)
        assertEquals(metricsSegment, spent)
    }

    @Test
    fun aPressThatNeverBecameAClickLeavesNothingBehindForTheNextOpen() {
        val origin = RevealOrigin(RevealTarget.QUALITY)
        val ticket = origin.record(metricsSegment)
        origin.withdraw(ticket)
        assertNull(origin.consume(RevealTarget.QUALITY))
    }

    @Test
    fun aScrolledAwayPressCannotTakeBackAnOriginPublishedAfterIt() {
        // The segment lives inside the scrolled body, so its press is the one a drag
        // steals — and the withdrawal that follows must not disarm the chip pressed since.
        val origin = RevealOrigin(RevealTarget.QUALITY)
        val stolen = origin.record(metricsSegment)
        origin.record(topChip)
        origin.withdraw(stolen)
        val spent: Offset? = origin.consume(RevealTarget.QUALITY)
        assertEquals(topChip, spent)
    }

    @Test
    fun aSurfaceThisChannelDoesNotServeStillSpendsTheOrigin() {
        val origin = RevealOrigin(RevealTarget.QUALITY)
        origin.record(metricsSegment)
        assertNull(origin.consume(RevealTarget.DIAGNOSTICS))
        assertNull(origin.consume(RevealTarget.QUALITY))
    }

    @Test
    fun anOpenNoControlPublishedForFallsBackToTheSurfacesOwnCentre() {
        val origin = RevealOrigin(RevealTarget.QUALITY)
        assertNull(origin.consume(RevealTarget.QUALITY))
        // A keyboard or TalkBack activation records nothing, so a second open in a row
        // cannot inherit the first one's control either.
        origin.record(topChip)
        origin.consume(RevealTarget.QUALITY)
        assertNull(origin.consume(RevealTarget.QUALITY))
    }

    /**
     * A press over a `clickable` is consumed by it twice — the down and the up, both on
     * the main pass, before the final pass this rule reads. Counting either as a steal
     * withdraws the origin of every press that ever becomes a click, which is the whole
     * feature: no sheet is ever born at the control that opened it.
     */
    @Test
    fun theControlsOwnClickDetectorConsumingThePressIsNotSomebodyStealingIt() {
        val press = PressToSummon()
        // The down, consumed at main by this control's own clickable.
        assertEquals(
            PressVerdict.PENDING,
            press.onFinalPass(pressed = true, consumed = true, inBounds = true),
        )
        // The up, consumed by the same detector on its way to firing the click.
        assertEquals(
            PressVerdict.SUMMONS,
            press.onFinalPass(pressed = false, consumed = true, inBounds = true),
        )
    }

    @Test
    fun aMoveTakenByTheScrollUnderTheSegmentAbandonsThePress() {
        val press = PressToSummon()
        press.onFinalPass(pressed = true, consumed = true, inBounds = true)
        assertEquals(
            PressVerdict.ABANDONED,
            press.onFinalPass(pressed = true, consumed = true, inBounds = true),
        )
    }

    @Test
    fun aPressDraggedOffTheControlOrReleasedOffItSummonsNothing() {
        val slidOff = PressToSummon()
        slidOff.onFinalPass(pressed = true, consumed = true, inBounds = true)
        assertEquals(
            PressVerdict.ABANDONED,
            slidOff.onFinalPass(pressed = true, consumed = false, inBounds = false),
        )

        val releasedOff = PressToSummon()
        releasedOff.onFinalPass(pressed = true, consumed = true, inBounds = true)
        assertEquals(
            PressVerdict.ABANDONED,
            releasedOff.onFinalPass(pressed = false, consumed = true, inBounds = false),
        )
    }

    @Test
    fun aPressHeldOnTheControlIsStillUndecided() {
        val press = PressToSummon()
        press.onFinalPass(pressed = true, consumed = true, inBounds = true)
        repeat(3) {
            assertEquals(
                PressVerdict.PENDING,
                press.onFinalPass(pressed = true, consumed = false, inBounds = true),
            )
        }
    }
}
