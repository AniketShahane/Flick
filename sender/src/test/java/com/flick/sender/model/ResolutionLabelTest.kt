package com.flick.sender.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResolutionLabelTest {

    // The observed defect: a 4.8 GB 2160p remux MediaStore never scanned was badged "SD",
    // which is a claim, and a wrong one. Absence has to read as absence.
    @Test
    fun `no reported pixels is not the smallest bucket`() {
        assertEquals(UnknownResolutionLabel, resolutionLabelFor(0, 0))
        assertEquals(UnknownResolutionLabel, resolutionLabelFor(-1, -1))
        assertNotEquals("SD", resolutionLabelFor(0, 0))
    }

    @Test
    fun `one reported dimension is still a measurement`() {
        assertEquals(FourKLabel, resolutionLabelFor(3840, 0))
        assertEquals(FourKLabel, resolutionLabelFor(0, 2160))
        assertEquals(FullHdLabel, resolutionLabelFor(0, 1080))
        assertEquals("HD", resolutionLabelFor(0, 720))
        assertEquals("SD", resolutionLabelFor(640, 0))
    }

    @Test
    fun `the measured buckets are unchanged`() {
        assertEquals(FourKLabel, resolutionLabelFor(3840, 2160))
        assertEquals(FourKLabel, resolutionLabelFor(4096, 2160))
        assertEquals(FullHdLabel, resolutionLabelFor(1920, 1080))
        assertEquals("HD", resolutionLabelFor(1280, 720))
        assertEquals("SD", resolutionLabelFor(640, 480))
    }

    // Both quality chips match by exact string, so an unmeasured file must fall out of
    // them rather than being filed under a bucket nobody established.
    @Test
    fun `an unknown label belongs to neither quality filter`() {
        assertNotEquals(FourKLabel, UnknownResolutionLabel)
        assertNotEquals(FullHdLabel, UnknownResolutionLabel)
    }
}
