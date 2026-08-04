package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFilesTest {

    @Test fun extensionDecidesWhatIsASubtitleBecauseProviderMimeTypesDisagree() {
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.srt"))
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.VTT"))
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.ass"))
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.ssa"))
        // Rejected on purpose: MicroDVD has no Media3 parser and a VobSub .sub is a
        // bitmap stream that needs its .idx companion, so neither can ever render.
        assertFalse(SubtitleFiles.isSubtitleName("Arrival.sub"))
        assertFalse(SubtitleFiles.isSubtitleName("Arrival.txt"))
        assertFalse(SubtitleFiles.isSubtitleName("Arrival.nfo"))
        assertFalse(SubtitleFiles.isSubtitleName("Arrival"))
        assertEquals("srt", SubtitleFiles.extensionOf("/tree/primary/Films/Arrival.srt"))
    }

    /**
     * The tag the picked file is labelled with on the wire. The video's own name is
     * passed in because a suffix the FILM already carries is part of its title rather
     * than a language — which is the only thing that keeps "De.Zaak.srt" out of Dutch.
     */
    @Test fun aLanguageSuffixIsReadOffTheNameAndSurfacesAsATag() {
        assertEquals("en", SubtitleFiles.languageTagOf("Arrival.mkv", "Arrival.en.srt"))
        // A three-letter tag, behind a flag word that qualifies the subtitle rather
        // than naming a language.
        assertEquals(
            "en",
            SubtitleFiles.languageTagOf(
                "The.Matrix.1999.1080p.BluRay.mkv",
                "The.Matrix.1999.1080p.BluRay.eng.forced.srt",
            ),
        )
        assertEquals("fr", SubtitleFiles.languageTagOf("Interstellar.2014.2160p.HDR.mkv", "Interstellar.2014.fr.srt"))
        // Spelled out rather than coded, and still a language.
        assertEquals(
            "en",
            SubtitleFiles.languageTagOf("Dune.Part.Two.2024.2160p.mkv", "Dune.Part.Two.2024.English.srt"),
        )
    }

    /** A region subtag survives the separator split that would otherwise eat its hyphen. */
    @Test fun aRegionQualifiedTagKeepsItsRegion() {
        assertEquals(
            "pt-BR",
            SubtitleFiles.languageTagOf("Cidade.de.Deus.2002.mkv", "Cidade.de.Deus.2002.pt-BR.srt"),
        )
    }

    @Test fun theFirstSegmentIsATitleAndNeverALanguage() {
        assertNull(SubtitleFiles.languageTagOf("", "It.srt"))
        assertNull(SubtitleFiles.languageTagOf("It.2017.mkv", "It.2017.srt"))
        // A segment the video already carries is part of the title, not a language.
        assertNull(SubtitleFiles.languageTagOf("De.Zaak.mkv", "De.Zaak.srt"))
        // Nothing after the title names a language either, so the wire carries no tag.
        assertNull(SubtitleFiles.languageTagOf("Arrival.2016.2160p.DV.mkv", "Arrival.2016.2160p.DV.srt"))
    }

    @Test fun theNameIsReducedToTheTokensBothHalvesOfTheRuleReadFrom() {
        assertEquals(listOf("my", "movie"), SubtitleFiles.tokensOf("MY_MOVIE.SRT"))
        assertEquals("Arrival.2016.2160p.DV", SubtitleFiles.baseName("Arrival.2016.2160p.DV.srt"))
        assertEquals("Arrival", SubtitleFiles.baseName("/tree/primary/Films/Arrival"))
    }
}
