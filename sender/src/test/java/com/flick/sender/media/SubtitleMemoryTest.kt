package com.flick.sender.media

import com.flick.sender.net.ControlProtocolV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMemoryTest {

    // --- What a record is, and what is refused as one --------------------------

    @Test fun aRecordRoundTripsThroughTheEncodingItIsFiledIn() {
        val record = SubtitleMemoryRecord("srt", "Arrival.en.srt", "en", 84_213L, 1_712_000_000_000L)
        assertEquals(record, SubtitleMemoryCodec.decode(SubtitleMemoryCodec.encode(record)))
        // A subtitle whose language nothing could name is stored with the field empty, and
        // reads back as the absent tag rather than as a tag that is the empty string.
        val unknown = record.copy(language = null)
        assertEquals(unknown, SubtitleMemoryCodec.decode(SubtitleMemoryCodec.encode(unknown)))
        assertNull(SubtitleMemoryCodec.decode(SubtitleMemoryCodec.encode(unknown))?.language)
    }

    @Test fun aDisplayNameCarryingTheDelimiterSurvivesTheEncodingIntact() {
        // This is why the `:`-joined pair the other two stores use could not be borrowed.
        // A display name is whatever a DocumentsProvider or OpenSubtitles called the file,
        // and every one of these is a name a subtitle has actually had.
        listOf(
            "Rec | 2007 | ES.srt",
            "Mission: Impossible – Fallout.srt",
            "100% Wolf [SDH] (2020).srt",
            "Ame to Yuki 雨と雪.ja.srt",
            "Amélie+Poulain.fr.srt",
            "a=b&c=d%20e.srt",
            "Спутник.ru.srt",
        ).forEach { name ->
            val record = SubtitleMemoryRecord("srt", name, null, 1L, 0L)
            assertEquals(name, SubtitleMemoryCodec.decode(SubtitleMemoryCodec.encode(record))?.displayName)
        }
    }

    @Test fun aMalformedRecordIsDiscardedRatherThanGuessedAt() {
        assertNull(SubtitleMemoryCodec.decode(""))
        assertNull(SubtitleMemoryCodec.decode("srt|Arrival.srt|en|84213"))
        assertNull(SubtitleMemoryCodec.decode("srt|Arrival.srt|en|84213|1|extra"))
        assertNull(SubtitleMemoryCodec.decode("srt|Arrival.srt|en|kilobytes|1"))
        assertNull(SubtitleMemoryCodec.decode("srt|Arrival.srt|en|84213|later"))
        assertNull(SubtitleMemoryCodec.decode("srt|Arrival.srt|en|84213|-1"))
        // A truncated percent escape is not a decode failure this app may guess past.
        assertNull(SubtitleMemoryCodec.decode("srt|Arrival%2.srt|en|84213|1"))
    }

    @Test fun aStoredExtensionMedia3CannotSideloadReadsAsNoMemoryAtAll() {
        // The copy would be served under `/s/{token}` and attached as a text track, so an
        // extension outside the set is a file the TV would draw nothing from. `sub` is the
        // one that matters: MicroDVD has no Media3 parser and a VobSub .sub is a bitmap
        // stream, which is exactly why SubtitleFiles refuses it at the picker too.
        assertNull(SubtitleMemoryCodec.decode("sub|Arrival.sub||84213|1"))
        assertNull(SubtitleMemoryCodec.decode("txt|Arrival.txt||84213|1"))
        assertNull(SubtitleMemoryCodec.decode("|Arrival||84213|1"))
        SubtitleFiles.SubtitleExtensions.forEach { extension ->
            assertEquals(
                extension,
                SubtitleMemoryCodec.decode("$extension|Arrival.$extension||84213|1")?.extension,
            )
        }
    }

    @Test fun aStoredLabelOrTagThisAppCouldNotHaveWrittenIsRefused() {
        // selectSubtitle stores the OUTPUT of these two calls, so a value either of them
        // would change is a record this app did not write. Refused rather than repaired,
        // following AudioDelayMemoryPolicy.storable: re-attaching is one tap, and a label
        // or tag quietly rewritten on the viewer's behalf is a promise nothing kept.
        assertFalse(storable(displayName = ""))
        assertFalse(storable(displayName = " Arrival.srt"))
        assertFalse(storable(displayName = "Arrival.srt "))
        // A format character a provider left in the name: invisible, stripped by the
        // normalizer, and therefore a label that is not the one that was stored.
        assertFalse(storable(displayName = "Arrival\u200B.srt"))
        assertFalse(storable(displayName = "Arrival  two.srt"))
        assertFalse(storable(displayName = "x".repeat(ControlProtocolV2.SUBTITLE_LABEL_MAX + 1)))
        assertTrue(storable(displayName = "x".repeat(ControlProtocolV2.SUBTITLE_LABEL_MAX)))

        assertFalse(storable(language = ""))
        assertFalse(storable(language = "english"))
        assertFalse(storable(language = " en"))
        assertFalse(storable(language = "en_GB"))
        assertTrue(storable(language = null))
        assertTrue(storable(language = "pt-BR"))
    }

    @Test fun aCopyLargerThanTheRouteWouldEverServeIsNotOneThisAppWrote() {
        // The same 5 MiB ceiling /s/{token} enforces and the copy counts to while reading.
        // A record above it names a file the TV could not be given anyway.
        assertFalse(storable(sizeBytes = SubtitleFiles.MaxSubtitleBytes + 1))
        assertTrue(storable(sizeBytes = SubtitleFiles.MaxSubtitleBytes))
        // Nothing writes a zero-byte copy — an empty file is not a subtitle — so its
        // presence is corruption, and reading it as absent needs no special case.
        assertFalse(storable(sizeBytes = 0L))
        assertFalse(storable(sizeBytes = -1L))
    }

    // --- Which film a copy belongs to, and where it lives ----------------------

    @Test fun theCopyIsNamedByTheSameIdentityTheResumeCheckpointIsFiledUnder() {
        val fingerprint = fingerprint()
        // The claim the whole naming scheme rests on: a fingerprint is URL-safe Base64
        // without padding, so it is already a legal file name and needs no escaping.
        assertEquals("$fingerprint.srt", SubtitleMemoryPolicy.fileName(fingerprint, "srt"))
        assertTrue(fingerprint.all { it.isLetterOrDigit() || it == '-' || it == '_' })

        val record = SubtitleMemoryRecord("srt", "Arrival.srt", "en", 1L, 0L)
        val state = SubtitleMemoryState.Ready(mapOf(fingerprint to record))
        assertEquals(record, rememberedSubtitle(state, fingerprint))
        // Every field that moves the resume checkpoint moves this too. Cues are timed
        // against the mux that file had; a re-encode is a different mux with its own.
        assertNotEquals(fingerprint, fingerprint(size = 9_000L))
        assertNull(rememberedSubtitle(state, fingerprint(size = 9_000L)))
    }

    @Test fun aFilmWithNoMemoryAndAStoreStillReadingAreBothNoSubtitleAtAll() {
        assertNull(rememberedSubtitle(SubtitleMemoryState.Ready(emptyMap()), fingerprint()))
        assertNull(rememberedSubtitle(SubtitleMemoryState.Loading, fingerprint()))
    }

    @Test fun anIdentityThatCouldReachOutOfTheDirectoryIsRefusedAName() {
        // This name is what an eviction deletes, and the keys it is built from are read
        // back out of a file on disk. Nothing this app writes looks like any of these —
        // which is the point: a key that had acquired one would otherwise aim a delete at
        // a path of the store's own choosing.
        listOf(
            "..",
            "../../shared_prefs/flick_pairings",
            "sub/dir",
            "sub\\dir",
            "trailing.",
            "",
            "a".repeat(65),
        ).forEach { assertNull(it, SubtitleMemoryPolicy.fileName(it, "srt")) }
        assertNull(SubtitleMemoryPolicy.fileName(fingerprint(), "sub"))
        assertEquals("a.vtt", SubtitleMemoryPolicy.fileName("a", "vtt"))
    }

    @Test fun everyNameAFilmCouldHaveBeenSavedUnderIsDeletedWhenItIsForgotten() {
        // A subtitle re-picked in another format leaves the previous extension's file
        // behind, so forgetting one film has to reach all four of them — otherwise the
        // bytes stay and the budget cannot see them.
        val copies = SubtitleMemoryPolicy.copyFileNames("abc")
        assertEquals(SubtitleFiles.SubtitleExtensions.size, copies.size)
        assertTrue(copies.containsAll(SubtitleFiles.SubtitleExtensions.map { "abc.$it" }))
        // And deliberately not the temps. A temp now belongs to a copy that may still be
        // reading, whose rename would then find nothing left to move, so a removal that
        // deleted one would fail a pick made AFTER it. Abandoned temps are the sweep's,
        // which matches on what records name and so collects them whatever they are called.
        assertTrue(copies.none { it.contains(SubtitleMemoryPolicy.TEMP_SUFFIX) })
        assertEquals(emptyList<String>(), SubtitleMemoryPolicy.copyFileNames("../abc"))
    }

    @Test fun twoCopiesOfOneFilmInFlightAtOnceNeverShareATempFile() {
        // The copy runs outside the store's lock now — a cloud DocumentsProvider's read
        // can block for minutes and a lock held across one is every later mutation for
        // every film silently never landing. Which lets two picks for one film read at the
        // same moment, and one temp name between them would have them writing into each
        // other's bytes and renaming whichever mixture finished last into place.
        val name = SubtitleMemoryPolicy.fileName(fingerprint(), "srt")!!
        assertNotEquals(
            SubtitleMemoryPolicy.tempFileName(name, 1L),
            SubtitleMemoryPolicy.tempFileName(name, 2L),
        )
        // And never a finished name: those are what a live record points at, and a temp
        // able to collide with one would be a copy written over by a copy still arriving.
        val temp = SubtitleMemoryPolicy.tempFileName(name, 7L)
        assertFalse(temp in SubtitleMemoryPolicy.copyFileNames(fingerprint()))
        assertTrue(temp.startsWith(name + SubtitleMemoryPolicy.TEMP_SUFFIX))
    }

    // --- Which of two gestures the store ends up agreeing with -----------------

    @Test fun aDetachMadeWhileTheCopyWasStillReadingOutranksIt() {
        // The ordering the mutex used to give by making every other mutation wait behind
        // the copy. It no longer holds one across a provider read, so order comes from
        // when the gesture was MADE: attaching a subtitle and immediately taking it back
        // off is two gestures a second apart, and without this the removal would finish
        // first and the attach would write its record back over it — a subtitle the viewer
        // explicitly took off, returning the next time they opened the film.
        assertFalse(SubtitleMemoryPolicy.landable(ticket = 1L, landed = 2L))
        assertTrue(SubtitleMemoryPolicy.landable(ticket = 3L, landed = 2L))
        // Nothing has landed for this film yet: the first mutation of a process has no
        // predecessor to lose to, which is the ordinary case and must not be refused.
        assertTrue(SubtitleMemoryPolicy.landable(ticket = 1L, landed = null))
        // A ticket never lands twice. Tickets are drawn from one counter across every
        // film, so the numbers a film sees have gaps in them and only their order means
        // anything.
        assertFalse(SubtitleMemoryPolicy.landable(ticket = 2L, landed = 2L))
        assertTrue(SubtitleMemoryPolicy.landable(ticket = 900L, landed = 2L))
    }

    // --- What a full store gives up -------------------------------------------

    @Test fun aFullStoreDropsTheFilmNobodyHasWatchedInLongest() {
        val records = (1..SubtitleMemoryPolicy.MAX_RECORDS).map { record("film-$it", it.toLong() * 1_000L) }
        assertEquals(emptyList<String>(), SubtitleMemoryPolicy.evicted(emptyList(), TYPICAL_BYTES))
        assertEquals(emptyList<String>(), SubtitleMemoryPolicy.evicted(records.dropLast(1), TYPICAL_BYTES))
        assertEquals(listOf("film-1"), SubtitleMemoryPolicy.evicted(records, TYPICAL_BYTES))
        // Preferences hand their contents back in no particular order, so the oldest has
        // to be found rather than assumed to be first.
        assertEquals(listOf("film-1"), SubtitleMemoryPolicy.evicted(records.shuffled(), TYPICAL_BYTES))
        // Dropped by when the subtitle was last attached, not by when the record first
        // appeared: a film watched again this evening is the last one to give up.
        val watchedTonight = records.toMutableList().apply { this[0] = record("film-1", Long.MAX_VALUE) }
        assertEquals(listOf("film-2"), SubtitleMemoryPolicy.evicted(watchedTonight, TYPICAL_BYTES))
    }

    @Test fun aFilmThatAlreadyHasASubtitleSpendsNobodyElsesRoom() {
        // The caller excludes its own key, so re-picking is a replacement rather than a
        // hundred-and-first record that costs some other film its memory.
        val records = (1..SubtitleMemoryPolicy.MAX_RECORDS).map { record("film-$it", it.toLong()) }
        assertEquals(
            emptyList<String>(),
            SubtitleMemoryPolicy.evicted(records.filterNot { it.first == "film-50" }, TYPICAL_BYTES),
        )
    }

    @Test fun aStoreUnderItsRecordCountAndOverItsByteBudgetStillEvicts() {
        // The bound that stops a count-only rule from spending half a gigabyte of somebody's
        // phone: MaxSubtitleBytes lets one copy weigh 5 MiB, so a hundred records has a
        // 500 MB ceiling. Five of those fill 24 MiB with nothing like a hundred films in it.
        val heavy = (1..5).map { record("film-$it", it.toLong(), SubtitleFiles.MaxSubtitleBytes) }
        assertTrue(heavy.size < SubtitleMemoryPolicy.MAX_RECORDS)
        assertEquals(
            listOf("film-1", "film-2"),
            SubtitleMemoryPolicy.evicted(heavy, SubtitleFiles.MaxSubtitleBytes),
        )
        // And it keeps evicting until the incoming copy fits, rather than dropping one
        // film per write and staying over budget for as long as the writes keep coming.
        val full = (1..8).map { record("film-$it", it.toLong(), SubtitleFiles.MaxSubtitleBytes) }
        val dropped = SubtitleMemoryPolicy.evicted(full, SubtitleFiles.MaxSubtitleBytes)
        assertEquals(listOf("film-1", "film-2", "film-3", "film-4", "film-5"), dropped)
        val remaining = full.filterNot { it.first in dropped }.sumOf { it.second.sizeBytes }
        assertTrue(remaining + SubtitleFiles.MaxSubtitleBytes <= SubtitleMemoryPolicy.MAX_TOTAL_BYTES)
    }

    @Test fun theByteBudgetIsNeverWhatBindsOnAnOrdinaryLibrary() {
        // A hundred ordinary subtitles is around 10 MB, so a viewer only ever meets the
        // record count. The budget exists for the pathological store, and a number below
        // this would start deleting memories a normal library has every right to keep.
        val ordinary = (1..SubtitleMemoryPolicy.MAX_RECORDS - 1).map {
            record("film-$it", it.toLong(), TYPICAL_BYTES)
        }
        assertEquals(emptyList<String>(), SubtitleMemoryPolicy.evicted(ordinary, TYPICAL_BYTES))
        assertTrue(
            SubtitleMemoryPolicy.MAX_RECORDS * TYPICAL_BYTES < SubtitleMemoryPolicy.MAX_TOTAL_BYTES,
        )
    }

    // --- Helpers ---------------------------------------------------------------

    private fun storable(
        extension: String = "srt",
        displayName: String = "Arrival.srt",
        language: String? = "en",
        sizeBytes: Long = 84_213L,
        updatedAtEpochMs: Long = 1L,
    ) = SubtitleMemoryPolicy.storable(
        SubtitleMemoryRecord(extension, displayName, language, sizeBytes, updatedAtEpochMs),
    )

    private fun record(
        key: String,
        updatedAtEpochMs: Long,
        sizeBytes: Long = TYPICAL_BYTES,
    ) = key to SubtitleMemoryRecord("srt", "Arrival.srt", "en", sizeBytes, updatedAtEpochMs)

    private fun fingerprint(
        uri: String = "content://media/external/video/media/42",
        size: Long = 8_000L,
        modified: Long = 123L,
        duration: Long = 180_000L,
        generation: Long? = 9L,
        version: String? = "v1",
    ) = PlaybackMediaFingerprint.of(uri, size, modified, duration, generation, version)

    private companion object {
        /** What a feature film's .srt actually weighs; nothing here depends on the figure. */
        const val TYPICAL_BYTES = 100L * 1024L
    }
}
