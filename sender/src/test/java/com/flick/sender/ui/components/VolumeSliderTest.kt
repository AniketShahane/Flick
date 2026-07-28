package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeSliderTest {

    @Test
    fun volumePercentReadsTheLevelAsWholePercent() {
        assertEquals(0, volumePercent(0f))
        assertEquals(50, volumePercent(0.5f))
        assertEquals(100, volumePercent(1f))
        assertEquals(43, volumePercent(0.4251f))
    }

    @Test
    fun volumePercentClampsALevelFromOffTheTrack() {
        // The level is written straight from a pointer position, so a drag that leaves
        // the track's ends hands this a figure outside 0..1.
        assertEquals(0, volumePercent(-0.4f))
        assertEquals(100, volumePercent(1.7f))
    }

    @Test
    fun volumePercentIsStableAcrossAWholeStep() {
        // The readout is what decides whether the label recomposes at all, so every
        // pointer sample inside one percent has to resolve to the same figure.
        assertEquals(volumePercent(0.615f), volumePercent(0.6199f))
    }
}
