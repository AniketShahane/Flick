package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The optional external-subtitle half of the v2 `loadMedia` frame. */
class SubtitleWireTest {
    private val media = "http://192.0.2.11:8080/v/MDEyMzQ1Njc4OWFiY2RlZg"
    private val sub = "http://192.0.2.11:8080/s/ISIjJCUmJygpKissLS4vMA"

    @Test fun noSelectionAppendsNothingSoTheFrameStaysByteIdentical() {
        assertEquals(emptyList<Pair<String, String>>(), ControlProtocolV2.subtitleFields(null, "English", "en"))
        assertEquals(emptyList<Pair<String, String>>(), ControlProtocolV2.subtitleFields("", "English", "en"))
    }

    @Test fun aSelectionAppendsTheThreeFieldsInWireOrder() {
        assertEquals(
            listOf("subUrl" to sub, "subLabel" to "Movie.en.srt", "subLang" to "en"),
            ControlProtocolV2.subtitleFields(sub, "  Movie.en.srt ", "en"),
        )
    }

    @Test fun theLabelGoesThroughTheSameCanonicalizationAsEveryOtherWireLabel() {
        assertEquals(
            listOf("subUrl" to sub, "subLabel" to "My Film EN"),
            ControlProtocolV2.subtitleFields(sub, "\nMy\tFilm\u200e  EN", null),
        )
        // 200 code points is the cap; the 201st is dropped, not the frame.
        val truncated = ControlProtocolV2.subtitleFields(sub, "x".repeat(260), null).toMap()
        assertEquals(200, truncated.getValue("subLabel").length)
    }

    @Test fun anUnnameableSubtitleDropsTheAttachmentRatherThanCostingTheVideoItsLoad() {
        // The receiver rejects the whole frame when subUrl arrives without subLabel.
        assertEquals(emptyList<Pair<String, String>>(), ControlProtocolV2.subtitleFields(sub, "\n\t\u200e", null))
        assertEquals(emptyList<Pair<String, String>>(), ControlProtocolV2.subtitleFields(sub, null, null))
    }

    @Test fun onlyAWellFormedLanguageTagIsEverPutOnTheWire() {
        assertEquals("en", ControlProtocolV2.languageTag(" en "))
        assertEquals("pt-BR", ControlProtocolV2.languageTag("pt-BR"))
        assertEquals("zh-Hant-HK", ControlProtocolV2.languageTag("zh-Hant-HK"))
        assertEquals("es-419", ControlProtocolV2.languageTag("es-419"))
        assertNull(ControlProtocolV2.languageTag(null))
        assertNull(ControlProtocolV2.languageTag(""))
        assertNull(ControlProtocolV2.languageTag("e"))
        assertNull(ControlProtocolV2.languageTag("english subtitles"))
        assertNull(ControlProtocolV2.languageTag("en_US"))
        assertNull(ControlProtocolV2.languageTag("en-"))
        // Extension and private-use subtags are refused: the receiver refuses them too.
        assertNull(ControlProtocolV2.languageTag("en-US-x-flick"))
        assertNull(ControlProtocolV2.languageTag("pt-BRA"))
        assertNull(ControlProtocolV2.languageTag("en-" + "a".repeat(40)))
        assertEquals(
            listOf("subUrl" to sub, "subLabel" to "Movie.srt"),
            ControlProtocolV2.subtitleFields(sub, "Movie.srt", "unknown language"),
        )
    }

    @Test fun theSubtitleUrlIsOnlyEmittedForTheMediaUrlsOwnOrigin() {
        assertTrue(ControlProtocolV2.sameHttpOrigin(media, sub))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "http://192.0.2.12:8080/s/tok"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "http://192.0.2.11:8081/s/tok"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "https://192.0.2.11:8080/s/tok"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "http://192.0.2.11/s/tok"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "http://evil@192.0.2.11:8080/s/tok"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "http://192.0.2.11:8080/s/tok?x=1"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "http://192.0.2.11:8080/s/tok#f"))
        assertFalse(ControlProtocolV2.sameHttpOrigin(media, "/s/tok"))
        assertFalse(ControlProtocolV2.sameHttpOrigin("http://192.0.2.11/v/tok", sub))
    }
}
