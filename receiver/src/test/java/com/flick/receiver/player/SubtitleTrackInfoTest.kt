package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTrackInfoTest {

    // --- Format token ---------------------------------------------------------

    @Test fun knownSubtitleMimesMapToTheirPanelToken() {
        assertEquals("SRT", subtitleFormatLabel(MimeTypes.APPLICATION_SUBRIP))
        assertEquals("VTT", subtitleFormatLabel(MimeTypes.TEXT_VTT))
        assertEquals("VTT", subtitleFormatLabel(MimeTypes.APPLICATION_MP4VTT))
        assertEquals("SSA", subtitleFormatLabel(MimeTypes.TEXT_SSA))
        assertEquals("TTML", subtitleFormatLabel(MimeTypes.APPLICATION_TTML))
        assertEquals("TX3G", subtitleFormatLabel(MimeTypes.APPLICATION_TX3G))
        assertEquals("PGS", subtitleFormatLabel(MimeTypes.APPLICATION_PGS))
        assertEquals("VOBSUB", subtitleFormatLabel(MimeTypes.APPLICATION_VOBSUB))
        assertEquals("DVB", subtitleFormatLabel(MimeTypes.APPLICATION_DVBSUBS))
        assertEquals("CEA-608", subtitleFormatLabel(MimeTypes.APPLICATION_CEA608))
        assertEquals("CEA-608", subtitleFormatLabel(MimeTypes.APPLICATION_MP4CEA608))
        assertEquals("CEA-708", subtitleFormatLabel(MimeTypes.APPLICATION_CEA708))
        assertEquals("RAWCC", subtitleFormatLabel(MimeTypes.APPLICATION_RAWCC))
    }

    @Test fun mimeMatchingIgnoresCase() {
        assertEquals("SRT", subtitleFormatLabel("Application/X-SubRip"))
    }

    @Test fun unknownMimeYieldsNoTokenInsteadOfAGuess() {
        assertEquals("", subtitleFormatLabel(null))
        assertEquals("", subtitleFormatLabel(""))
        assertEquals("", subtitleFormatLabel("application/x-not-a-subtitle"))
        assertEquals("", subtitleFormatLabel(MimeTypes.VIDEO_H264))
        // The transcoding wrapper is not a format the file carries.
        assertEquals("", subtitleFormatLabel(MimeTypes.APPLICATION_MEDIA3_CUES))
    }

    @Test fun onlyBitmapFormatsAreImageBased() {
        assertTrue(subtitleIsImageBased(MimeTypes.APPLICATION_PGS))
        assertTrue(subtitleIsImageBased(MimeTypes.APPLICATION_VOBSUB))
        assertTrue(subtitleIsImageBased(MimeTypes.APPLICATION_DVBSUBS))
        assertFalse(subtitleIsImageBased(MimeTypes.APPLICATION_SUBRIP))
        assertFalse(subtitleIsImageBased(MimeTypes.TEXT_VTT))
        assertFalse(subtitleIsImageBased(null))
    }

    // --- Cue-transcoding unwrap ----------------------------------------------

    @Test fun media3CueWrapperResolvesBackToTheContainerFormat() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            subtitleSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.APPLICATION_SUBRIP),
        )
        assertEquals(
            MimeTypes.APPLICATION_MEDIA3_CUES,
            subtitleSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES, null),
        )
        assertEquals(
            MimeTypes.APPLICATION_MEDIA3_CUES,
            subtitleSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES, "  "),
        )
    }

    @Test fun untranscodedFormatsPassThroughUntouched() {
        assertEquals(MimeTypes.APPLICATION_PGS, subtitleSampleMimeType(MimeTypes.APPLICATION_PGS, "ignored"))
        assertNull(subtitleSampleMimeType(null, MimeTypes.APPLICATION_SUBRIP))
    }

    // --- Labels ---------------------------------------------------------------

    @Test fun containerTitleWinsOverTheLanguage() {
        assertEquals("Director commentary", subtitleTrackLabel("  Director commentary  ", "en"))
    }

    @Test fun languageBecomesItsOwnEndonym() {
        assertEquals("English", subtitleTrackLabel(null, "en"))

        val french = subtitleTrackLabel(null, "fr")
        assertNotNull(french)
        assertFalse("fr".equals(french, ignoreCase = true))
        assertTrue(french!!.first().isUpperCase())

        val regional = subtitleTrackLabel(null, "es-419")
        assertNotNull(regional)
        assertTrue(regional!!.first().isUpperCase())
    }

    @Test fun unnamedTracksReportNoLabelSoTheCallerNumbersThem() {
        assertNull(subtitleTrackLabel(null, null))
        assertNull(subtitleTrackLabel("   ", "   "))
        assertNull(subtitleTrackLabel(null, C.LANGUAGE_UNDETERMINED))
        assertNull(subtitleTrackLabel(null, "UND"))
        assertNull(subtitleTrackLabel(null, "!!!"))
    }

    // --- Ids ------------------------------------------------------------------

    @Test fun trackIdsRoundTrip() {
        val ref = SubtitleTrackRef(groupIndex = 3, trackIndex = 2)
        assertEquals("3:2", subtitleTrackId(ref))
        assertEquals(ref, parseSubtitleTrackId("3:2"))
    }

    @Test fun malformedTrackIdsAreRejected() {
        assertNull(parseSubtitleTrackId(""))
        assertNull(parseSubtitleTrackId("3"))
        assertNull(parseSubtitleTrackId(":2"))
        assertNull(parseSubtitleTrackId("3:"))
        assertNull(parseSubtitleTrackId("a:b"))
        assertNull(parseSubtitleTrackId("-1:0"))
        assertNull(parseSubtitleTrackId("3:2:1"))
    }

    // --- Tracks mapping -------------------------------------------------------

    @Test fun onlySupportedTextTracksAreOffered() {
        val tracks = Tracks(
            listOf(
                group(videoFormat()),
                group(textFormat(language = "en", selectionFlags = C.SELECTION_FLAG_DEFAULT), selected = true),
                group(textFormat(language = "es", sampleMimeType = MimeTypes.APPLICATION_PGS)),
                group(textFormat(language = "de"), supported = false),
            ),
        )

        val result = subtitleTracksFrom(tracks)

        assertEquals(2, result.size)
        assertEquals(listOf("1:0", "2:0"), result.map { it.id })
        assertEquals(listOf(1, 2), result.map { it.trackNumber })
        assertEquals(listOf(true, false), result.map { it.isSelected })
    }

    @Test fun mappedTrackCarriesTheContainerFormatNotTheCueWrapper() {
        val tracks = Tracks(listOf(group(textFormat(language = "en"), selected = true)))

        val track = subtitleTracksFrom(tracks).single()

        assertEquals(MimeTypes.APPLICATION_SUBRIP, track.mimeType)
        assertEquals("SRT", track.formatLabel)
        assertFalse(track.isImageBased)
        assertEquals("English", track.label)
        assertTrue(track.isSelected)
        assertFalse(track.isForced)
        assertFalse(track.isHearingImpaired)
    }

    @Test fun forcedAndHearingImpairedFlagsComeFromTheContainer() {
        val tracks = Tracks(
            listOf(
                group(
                    textFormat(
                        language = "en",
                        selectionFlags = C.SELECTION_FLAG_FORCED,
                        roleFlags = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND,
                    ),
                ),
            ),
        )

        val track = subtitleTracksFrom(tracks).single()

        assertTrue(track.isForced)
        assertTrue(track.isHearingImpaired)
    }

    @Test fun imageSubtitlesAreFlaggedForTheSourceChip() {
        val tracks = Tracks(
            listOf(group(textFormat(language = "es", sampleMimeType = MimeTypes.APPLICATION_PGS))),
        )

        val track = subtitleTracksFrom(tracks).single()

        assertEquals("PGS", track.formatLabel)
        assertTrue(track.isImageBased)
    }

    @Test fun multiTrackGroupsEncodeTheTrackIndex() {
        val group = Tracks.Group(
            TrackGroup(
                textFormat(language = "en"),
                textFormat(language = "en", sampleMimeType = MimeTypes.TEXT_VTT),
            ),
            /* adaptiveSupported = */ false,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
            booleanArrayOf(false, true),
        )

        val result = subtitleTracksFrom(Tracks(listOf(group)))

        assertEquals(listOf("0:0", "0:1"), result.map { it.id })
        assertEquals(listOf("SRT", "VTT"), result.map { it.formatLabel })
        assertEquals(listOf(false, true), result.map { it.isSelected })
    }

    @Test fun mediaWithoutSubtitlesOffersNothing() {
        assertEquals(emptyList<SubtitleTrackInfo>(), subtitleTracksFrom(Tracks.EMPTY))
        assertEquals(emptyList<SubtitleTrackInfo>(), subtitleTracksFrom(Tracks(listOf(group(videoFormat())))))
    }

    // --- Override resolution --------------------------------------------------

    @Test fun anIdResolvesBackToItsTrackGroup() {
        val text = group(textFormat(language = "en"))
        val tracks = Tracks(listOf(group(videoFormat()), text))

        val selection = subtitleTrackOverride(tracks, "1:0")

        assertNotNull(selection)
        assertSame(text.mediaTrackGroup, selection!!.mediaTrackGroup)
        assertEquals(1, selection.trackIndices.size)
        assertEquals(0, selection.trackIndices[0])
    }

    @Test fun staleOrWrongTypeIdsResolveToNothing() {
        val tracks = Tracks(listOf(group(videoFormat()), group(textFormat(language = "en"))))

        assertNull(subtitleTrackOverride(tracks, "0:0"))
        assertNull(subtitleTrackOverride(tracks, "1:1"))
        assertNull(subtitleTrackOverride(tracks, "7:0"))
        assertNull(subtitleTrackOverride(tracks, "nonsense"))
    }

    // --- Fixtures -------------------------------------------------------------

    /** Mirrors what Media3 publishes after parsing subtitles during extraction. */
    private fun textFormat(
        language: String?,
        sampleMimeType: String = MimeTypes.APPLICATION_SUBRIP,
        selectionFlags: Int = 0,
        roleFlags: Int = 0,
    ): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
        .setCodecs(sampleMimeType)
        .setLanguage(language)
        .setSelectionFlags(selectionFlags)
        .setRoleFlags(roleFlags)
        .build()

    private fun videoFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .build()

    private fun group(
        format: Format,
        selected: Boolean = false,
        supported: Boolean = true,
    ): Tracks.Group = Tracks.Group(
        TrackGroup(format),
        /* adaptiveSupported = */ false,
        intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE),
        booleanArrayOf(selected),
    )
}
