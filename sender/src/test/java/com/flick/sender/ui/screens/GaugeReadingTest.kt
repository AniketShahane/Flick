package com.flick.sender.ui.screens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GaugeReadingTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun aGaugeKeepsItsDecimalPointWhereverThePhoneIs() {
        // A string resource formats against the device locale. The same measurement is
        // rendered by Format.megabits in the link pill and the dock, which pins Locale.US,
        // so a locale reaching this gauge would put "61,4" and "61.4 Mb/s" on one screen.
        Locale.setDefault(Locale.GERMANY)
        assertEquals("61.4", gaugeReading(61.4))
        assertEquals("12.0", gaugeReading(12.0))
    }

    @Test
    fun aThreeDigitRateIsStillOneUnbrokenNumber() {
        // The reading carries no unit of its own precisely so it cannot wrap at a space;
        // nothing this function returns may contain one.
        val reading = gaugeReading(104.24)
        assertEquals("104.2", reading)
        assertEquals(-1, reading.indexOf(' '))
    }

    @Test
    fun aZeroReadsAsAMeasurementRatherThanAnEmptyString() {
        // Zero is only ever shown when the sheet has decided it has a number; the gauge
        // renders its unknown copy instead when it has not.
        assertEquals("0.0", gaugeReading(0.0))
    }
}
