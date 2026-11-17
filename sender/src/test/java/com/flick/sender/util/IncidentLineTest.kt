package com.flick.sender.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The journal is read out of release builds, so its shape is what keeps identifiers out. */
class IncidentLineTest {

    private enum class Sample { ACCEPT_SILENT }

    private fun line(event: String, vararg fields: Pair<String, Any?>) = IncidentLine.format(0L, event, fields)

    @Test fun formatsUtcMillisEventAndFieldsInOrder() {
        assertEquals(
            "2026-11-17T09:05:03.007Z cast_ready startup_ms=1840 retry=false",
            IncidentLine.format(1_794_906_303_007L, "cast_ready", arrayOf("startup_ms" to 1840L, "retry" to false)),
        )
    }

    @Test fun zeroMillisAreStillWritten() {
        assertEquals("1970-01-01T00:00:00.000Z process_start", line("process_start"))
    }

    @Test fun acceptsBooleansIntsLongsEnumsAndTokens() {
        assertEquals(
            "1970-01-01T00:00:00.000Z e a=true b=-3 c=9000000000 d=ACCEPT_SILENT f=echo_unanswered",
            line("e", "a" to true, "b" to -3, "c" to 9_000_000_000L, "d" to Sample.ACCEPT_SILENT, "f" to "echo_unanswered"),
        )
    }

    /** Shape alone; a token or a name that happens to fit is kept out by the call sites. */
    @Test fun addressesUrlsMacsAndMisshapenStringsAreUnrepresentable() {
        for (value in listOf(
            "192.0.2.10", "http://192.0.2.10:8080/v/abc", "02:00:00:00:00:01", "fe80::1",
            "_leading", "9starts_with_digit", "has space", "", "a".repeat(41), "dash-ed",
        )) {
            assertEquals(value, "1970-01-01T00:00:00.000Z e k=invalid", line("e", "k" to value))
        }
    }

    @Test fun otherTypesAndNullAreInvalid() {
        assertEquals(
            "1970-01-01T00:00:00.000Z e a=invalid b=invalid c=invalid",
            line("e", "a" to null, "b" to 1.5, "c" to listOf(1)),
        )
    }

    @Test fun badEventAndKeyNamesAreInvalid() {
        assertEquals("1970-01-01T00:00:00.000Z invalid invalid=1", line("Cast", "Bad-Key" to 1))
        assertEquals("1970-01-01T00:00:00.000Z invalid", line("a".repeat(33)))
        assertEquals("1970-01-01T00:00:00.000Z ${"a".repeat(32)}", line("a".repeat(32)))
    }

    @Test fun aFortyCharacterTokenIsTheLongestAccepted() {
        val token = "A" + "b".repeat(39)
        assertEquals("1970-01-01T00:00:00.000Z e k=$token", line("e", "k" to token))
    }
}
