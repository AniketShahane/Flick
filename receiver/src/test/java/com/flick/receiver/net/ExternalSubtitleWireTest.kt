package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `subUrl` is attacker-controlled from the moment it arrives, so it is held to
 * exactly the media URL's pinning rules. These cases are the ones that would
 * turn the TV into a fetcher for something the phone never served.
 */
class ExternalSubtitleWireTest {
    private val peer = "192.168.42.17"
    private val subtitleUrl = "http://192.168.42.17:8080/s/ZGVtb19zdWJfdG9rZW4wMQ"
    private val mediaUrl = "http://192.168.42.17:8080/v/ZGVtb19tZWRpYV90b2tlbg"

    // --- subUrl pinning -------------------------------------------------------

    @Test fun acceptsOnlyTheCanonicalPinnedSubtitleUrl() =
        assertTrue(MediaUrlValidator.isValidSubtitle(subtitleUrl, peer))

    @Test fun rejectsAForeignHost() {
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("192.168.42.17", "192.168.42.18"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("192.168.42.17", "10.0.0.5"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("192.168.42.17", "localhost"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("192.168.42.17", "example.test"), peer))
    }

    @Test fun rejectsAForeignPort() {
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace(":8080", ":8081"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace(":8080", ""), peer))
    }

    @Test fun rejectsEveryNonHttpScheme() {
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("http://", "https://"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("file:///sdcard/Movies/film.srt", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("content://media/external/file/17", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("ftp://192.168.42.17:8080/s/ZGVtb19zdWJfdG9rZW4wMQ", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("/s/ZGVtb19zdWJfdG9rZW4wMQ", peer))
    }

    @Test fun rejectsUserInfoAndOtherRedirectShapes() {
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("http://", "http://x@"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("http://", "http://192.168.42.17@"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("$subtitleUrl?x=1", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("$subtitleUrl#x", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("$subtitleUrl/", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("/s/", "/s/%5a"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("/s/", "//s/"), peer))
        assertFalse(MediaUrlValidator.isValidSubtitle(subtitleUrl.replace("/s/", "/s/../"), peer))
    }

    @Test fun rejectsAMalformedToken() {
        assertFalse(MediaUrlValidator.isValidSubtitle("http://192.168.42.17:8080/s/short", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("http://192.168.42.17:8080/s/", peer))
        assertFalse(MediaUrlValidator.isValidSubtitle("http://192.168.42.17:8080/s/ZGVtb19zdWJfdG9rZW4wMQ=", peer))
    }

    @Test fun rejectsAnythingWhenThePeerIsNotPrivate() {
        assertFalse(MediaUrlValidator.isValidSubtitle("http://8.8.8.8:8080/s/ZGVtb19zdWJfdG9rZW4wMQ", "8.8.8.8"))
    }

    @Test fun theTwoRoutesAreNotInterchangeable() {
        assertFalse(MediaUrlValidator.isValidSubtitle(mediaUrl, peer))
        assertFalse(MediaUrlValidator.isValid(subtitleUrl, peer))
    }

    @Test fun nullIsNotAUrl() {
        assertFalse(MediaUrlValidator.isValidSubtitle(null, peer))
    }

    // --- loadMedia field set --------------------------------------------------

    private val base = setOf("t", "v", "castId", "url", "title", "durationMs", "startMs")

    @Test fun theBaseFrameIsUnchanged() {
        assertTrue(loadFieldsAccepted(base))
        assertFalse(loadFieldsAccepted(base - "url"))
        assertFalse(loadFieldsAccepted(base + "extra"))
    }

    @Test fun theSubtitleFieldsAreAcceptedOnlyAsACoherentGroup() {
        assertTrue(loadFieldsAccepted(base + setOf("subUrl", "subLabel")))
        assertTrue(loadFieldsAccepted(base + setOf("subUrl", "subLabel", "subLang")))
        assertFalse(loadFieldsAccepted(base + "subUrl"))
        assertFalse(loadFieldsAccepted(base + "subLabel"))
        assertFalse(loadFieldsAccepted(base + "subLang"))
        assertFalse(loadFieldsAccepted(base + setOf("subUrl", "subLang")))
        assertFalse(loadFieldsAccepted(base + setOf("subLabel", "subLang")))
        assertFalse(loadFieldsAccepted(base + setOf("subUrl", "subLabel", "subMime")))
    }

    // --- subLang --------------------------------------------------------------

    @Test fun acceptsTheLanguageTagShapesASubtitleTrackUses() {
        listOf("en", "fr", "spa", "pt-BR", "zh-Hans", "sr-Latn-RS", "es-419")
            .forEach { assertTrue(it, isSubtitleLanguageTag(it)) }
    }

    @Test fun rejectsAnythingThatIsNotOne() {
        listOf("", "e", "english", "en_US", "en-", "-en", "1234", "en-US-x-private", "en US", "EN-us-extra-parts")
            .forEach { assertFalse(it, isSubtitleLanguageTag(it)) }
        assertFalse(isSubtitleLanguageTag(null))
    }
}
