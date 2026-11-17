package com.flick.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalLineTest {

    private enum class Kind { MEDIA_UNREACHABLE }

    @Test fun aLineIsAUtcMillisecondStampTheEventAndItsFields() {
        assertEquals(
            "2026-11-17T04:05:06.007Z cast_ready startup_ms=1234 armed=true",
            JournalLine.format(STAMP_MS, "cast_ready", listOf("startup_ms" to 1_234L, "armed" to true)),
        )
    }

    @Test fun aWholeSecondStillCarriesItsMilliseconds() {
        assertEquals("2026-11-17T04:05:06.000Z process_start", JournalLine.format(STAMP_MS - 7L, "process_start", emptyList()))
    }

    @Test fun integersBooleansEnumsAndTokensAreWrittenAsThemselves() {
        assertEquals("-3", JournalLine.value(-3))
        assertEquals("9000000000", JournalLine.value(9_000_000_000L))
        assertEquals("false", JournalLine.value(false))
        assertEquals("MEDIA_UNREACHABLE", JournalLine.value(Kind.MEDIA_UNREACHABLE))
        assertEquals("SocketTimeoutException", JournalLine.value("SocketTimeoutException"))
        assertEquals("a" + "b".repeat(39), JournalLine.value("a" + "b".repeat(39)))
    }

    @Test fun addressesUrlsAndFreeTextAreUnrepresentable() {
        for (raw in listOf(
            "192.0.2.10",
            "fe80::1",
            "02:00:00:00:00:01",
            "http://192.0.2.10:8080/v/token",
            "home network",
            "5 GHz",
            "1abc",
            "_abc",
            "a" + "b".repeat(40),
            "",
        )) {
            assertEquals(raw, JournalLine.INVALID, JournalLine.value(raw))
        }
    }

    @Test fun otherTypesAndNullAreInvalid() {
        assertEquals(JournalLine.INVALID, JournalLine.value(null))
        assertEquals(JournalLine.INVALID, JournalLine.value(1.5))
        assertEquals(JournalLine.INVALID, JournalLine.value(2.toShort()))
        assertEquals(JournalLine.INVALID, JournalLine.value(listOf("a")))
    }

    @Test fun eventsAndKeysOutsideTheGrammarAreInvalid() {
        assertEquals(
            "2026-11-17T04:05:06.007Z invalid invalid=1 ok=2",
            JournalLine.format(STAMP_MS, "Cast Ready", listOf("Bad-Key" to 1, "ok" to 2)),
        )
        assertEquals("a" + "b".repeat(31), JournalLine.name("a" + "b".repeat(31)))
        assertEquals(JournalLine.INVALID, JournalLine.name("a" + "b".repeat(32)))
        assertEquals(JournalLine.INVALID, JournalLine.name("9lives"))
    }

    private companion object {
        /** 2026-11-17T04:05:06.007Z */
        const val STAMP_MS = 1_794_888_306_007L
    }
}
