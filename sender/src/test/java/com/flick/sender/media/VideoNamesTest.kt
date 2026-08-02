package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNamesTest {

    @Test fun denseMovieReleaseKeepsOnlyMeaningfulFields() {
        val parsed = VideoNames.parse(
            "Example.Movie.2024.2160p.4K.WEB.x265.10bit.AAC5.1-[GROUP].mkv",
        )
        assertEquals("Example Movie (2024)", parsed.displayName)
        assertEquals("Example Movie", parsed.searchQuery)
        assertEquals(2024, parsed.year)
        assertNull(parsed.season)
        assertNull(parsed.edition)
    }

    @Test fun editionAbbreviationIsExpanded() {
        val parsed = VideoNames.parse("Example.Movie.2004.DC.2160p.BluRay.x265-GROUP.mkv")
        assertEquals("Example Movie (2004) - Director's Cut", VideoNames.format(parsed, "Director's Cut"))
        assertEquals(VideoEdition.DIRECTORS_CUT, parsed.edition)
    }

    @Test fun editionIsEnoughStructureEvenWithoutTechnicalTags() {
        val parsed = VideoNames.parse("Example.Movie.2004.DC.mkv")
        assertEquals("Example Movie (2004) - Director's Cut", VideoNames.format(parsed, "Director's Cut"))
        assertEquals(VideoEdition.DIRECTORS_CUT, parsed.edition)
    }

    @Test fun episodeKeepsYearSeasonAndEpisode() {
        val parsed = VideoNames.parse("Example.Show.2025.S01E03.1080p.HEVC.x265-GROUP[site].mkv")
        assertEquals("Example Show (2025) S01E03", parsed.displayName)
        assertEquals("Example Show", parsed.searchQuery)
        assertEquals(2025, parsed.year)
        assertEquals(1, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test fun episodeWithoutYearIsStillStructured() {
        val parsed = VideoNames.parse("Example.Show.S1E3.1080p.WEB.mkv")
        assertEquals("Example Show S01E03", parsed.displayName)
        assertEquals(1, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test fun repackAndReleaseGroupDoNotLeakIntoDisplay() {
        assertEquals(
            "Example Movie (2024)",
            VideoNames.parse("Example.Movie.2024.REPACK.2160p.WEB.x265-SOMEGROUP[site].mkv").displayName,
        )
    }

    @Test fun structuredTitlesPreserveApostrophesHyphensDiacriticsAndSymbols() {
        val parsed = VideoNames.parse("Spider-Man.Ocean's.Amélie.&.Co.2020.1080p.WEB.mkv")
        assertEquals("Spider-Man Ocean's Amélie & Co (2020)", parsed.displayName)
        assertEquals("Spider-Man Ocean's Amélie & Co", parsed.searchQuery)
    }

    @Test fun trailingParenthesizedYearIsStructuredWithoutChangingCleanTitlePunctuation() {
        val plexStyle = VideoNames.parse("Example Film (2024).mkv")
        assertEquals("Example Film (2024)", plexStyle.displayName)
        assertEquals("Example Film", plexStyle.searchQuery)
        assertEquals(2024, plexStyle.year)

        val jellyfinStyle = VideoNames.parse("Spider-Man: Example Story (2023).mp4")
        assertEquals("Spider-Man: Example Story (2023)", jellyfinStyle.displayName)
        assertEquals("Spider-Man: Example Story", jellyfinStyle.searchQuery)
        assertEquals(2023, jellyfinStyle.year)
    }

    @Test fun compoundTechnicalSuffixProvesStructureWithoutSplittingTitleHyphens() {
        val parsed = VideoNames.parse("Spider-Man.Example.Movie.2024.1080p-WEB-DL-GROUP.mkv")
        assertEquals("Spider-Man Example Movie (2024)", parsed.displayName)
        assertEquals("Spider-Man Example Movie", parsed.searchQuery)
        assertEquals(2024, parsed.year)
    }

    @Test fun compoundTechnicalSuffixCanProveANoYearCompactRelease() {
        val parsed = VideoNames.parse("Movie.1080p-WEB-DL.mkv")
        assertEquals("Movie", parsed.displayName)
        assertEquals("Movie", parsed.searchQuery)
        assertNull(parsed.year)
    }

    @Test fun noYearCompoundReleasePreservesTitleHyphens() {
        val parsed = VideoNames.parse("Spider-Man.1080p-WEB-DL.mkv")
        assertEquals("Spider-Man", parsed.displayName)
        assertEquals("Spider-Man", parsed.searchQuery)
    }

    @Test fun oneTechnicalTokenIsNotEnoughToRewriteANoYearName() {
        assertEquals("Spider-Man.1080p", VideoNames.parse("Spider-Man.1080p.mkv").displayName)
        assertEquals("Spider-Man", VideoNames.parse("Spider-Man.mkv").displayName)
    }

    @Test fun technicalLookingWordsBeforeTheYearRemainPartOfTheTitle() {
        assertEquals(
            "The 4K Restoration Part 2 (2024)",
            VideoNames.parse("The.4K.Restoration.Part.2.2024.2160p.WEB.mkv").displayName,
        )
    }

    @Test fun aNumberThatLooksLikeAYearInsideATitleDoesNotBeatTheReleaseYear() {
        val parsed = VideoNames.parse("Future.City.2049.2017.2160p.BluRay.mkv")
        assertEquals("Future City 2049 (2017)", parsed.displayName)
        assertEquals(2017, parsed.year)
    }

    @Test fun humanTitlesWithYearsAreNotRewrittenWithoutSceneEvidence() {
        assertEquals("Class of 1984", VideoNames.parse("Class of 1984.mp4").displayName)
        assertEquals("2001 A Space Odyssey", VideoNames.parse("2001 A Space Odyssey.mp4").displayName)
        assertEquals("Class.of.1984", VideoNames.parse("Class.of.1984.mp4").displayName)
        assertEquals("Future.City.2049", VideoNames.parse("Future.City.2049.mp4").displayName)
    }

    @Test fun humanPartTitleIsNotMistakenForReleaseNoise() {
        assertEquals("My.Part.2", VideoNames.parse("My.Part.2.mp4").displayName)
    }

    @Test fun anAlreadyHumanTitleOnlyLosesItsExtension() {
        val parsed = VideoNames.parse("Quarterly Service Overview.mp4")
        assertEquals("Quarterly Service Overview", parsed.displayName)
        assertEquals("Quarterly Service Overview", parsed.searchQuery)
    }

    @Test fun arbitraryLocalSeparatorsArePreservedOnTheSafeDisplayFallback() {
        assertEquals("family-trip-day-one", VideoNames.parse("family-trip-day-one.mov").displayName)
        val snake = VideoNames.parse("family_trip_day_two.mp4")
        assertEquals("family_trip_day_two", snake.displayName)
        assertEquals("family trip day two", snake.searchQuery)
    }

    @Test fun onlyTheFinalVideoExtensionIsRemoved() {
        assertEquals("clip.mp4", VideoNames.parse("clip.mp4.mov").displayName)
        assertEquals("clip.mp4.backup", VideoNames.parse("clip.mp4.backup").displayName)
    }

    @Test fun messagingCameraAndGeneratorIdsAreDisplayOnlyNotTextQueries() {
        val names = listOf(
            "VID-20260801-WA0008.mp4",
            "20260719_110626.mp4",
            "generator_created_video_d67a00ea.mp4",
            "provider_generated_video_d67a00ea.mp4",
            "VID_20250222_160525_301.mp4",
            "Screen_Recording_20260801_121314.mp4",
        )
        names.forEach { name ->
            val parsed = VideoNames.parse(name)
            assertTrue(parsed.displayName.isNotBlank())
            assertFalse(name, parsed.searchEligible)
            assertEquals("", parsed.searchQuery)
        }
    }

    @Test fun standardCameraCountersAreOpaqueEvenWhenTheyAreOnlyFourDigits() {
        listOf("IMG_1234.MOV", "DSC_4321.mp4", "DSCF-2468.mkv", "MVIMG_1357.mov").forEach { name ->
            val parsed = VideoNames.parse(name)
            assertFalse(name, parsed.searchEligible)
            assertEquals("", parsed.searchQuery)
        }
        assertFalse(VideoNames.parse("IMG_1234 (2024).mov").searchEligible)
    }

    @Test fun uuidAndLongHashAreNotConfidentTitles() {
        assertFalse(VideoNames.parse("123e4567-e89b-12d3-a456-426614174000.mp4").searchEligible)
        assertFalse(VideoNames.parse("abcdef0123456789abcdef0123456789.mkv").searchEligible)
    }

    @Test fun malformedAndBlankNamesFailSafe() {
        assertEquals("", VideoNames.parse("").displayName)
        assertFalse(VideoNames.parse("").searchEligible)
        assertEquals(".mp4", VideoNames.parse(".mp4").displayName)
        assertFalse(VideoNames.parse(".mp4").searchEligible)
        assertEquals("trailing.", VideoNames.parse("trailing.").displayName)
    }

    @Test fun technicalTagsAloneAreNotAConfidentTextSearch() {
        assertFalse(VideoNames.parse("1080p.WEB.mkv").searchEligible)
    }

    @Test fun unsafeBidiAndControlCharactersAreRemovedBeforeDisplayOrSearch() {
        val parsed = VideoNames.parse("Safe\u202Egpj\u0000.Title.2024.1080p.WEB.mp4")
        assertEquals("Safegpj Title (2024)", parsed.displayName)
        assertFalse(parsed.displayName.contains('\u202E'))
        assertFalse(parsed.displayName.contains('\u0000'))
    }

    @Test fun everyDeprecatedBidiEmbeddingControlIsRemoved() {
        (0x206A..0x206F).forEach { codePoint ->
            val control = String(Character.toChars(codePoint))
            val parsed = VideoNames.parse("Safe${control}Title.mp4")
            assertEquals("U+${codePoint.toString(16).uppercase()}", "SafeTitle", parsed.displayName)
            assertFalse(parsed.originalName.contains(control))
        }
    }

    @Test fun interlinearAnnotationControlsAreRemoved() {
        (0xFFF9..0xFFFB).forEach { codePoint ->
            val control = String(Character.toChars(codePoint))
            assertEquals("SafeTitle", VideoNames.parse("Safe${control}Title.mp4").displayName)
        }
    }

    @Test fun unicodeIsNfcWithoutAsciiFoldingOrRemovingMeaningfulJoiners() {
        assertEquals("Amélie", VideoNames.parse("Ame\u0301lie.mp4").displayName)
        val nonLatin = "क्\u200Dष कहानी.mp4"
        assertEquals("क्\u200Dष कहानी", VideoNames.parse(nonLatin).displayName)
    }

    @Test fun turningSimplificationOffRestoresTheSanitizedOriginalFilename() {
        val name = "Example.Movie.2024.1080p.WEB.mkv"
        assertEquals("Example Movie (2024)", VideoNames.displayName(name, simplify = true, editionLabel = null))
        assertEquals(name, VideoNames.displayName(name, simplify = false, editionLabel = null))
    }

    @Test fun outOfRangeYearStaysInTheTitleInsteadOfBecomingMetadata() {
        val parsed = VideoNames.parse("Example.Movie.2100.2160p.WEB.mkv")
        assertEquals("Example Movie 2100", parsed.displayName)
        assertNull(parsed.year)
    }
}
