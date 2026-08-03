package com.flick.sender.ui.screens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class LibrarySearchPolicyTest {

    private data class Row(val id: Long, val name: String)

    private val library = listOf(
        Row(1L, "Arrival 4K.mkv"),
        Row(2L, "The Flick Test.mp4"),
        Row(3L, "arrival-behind-the-scenes.mov"),
        // What the grid shows for these is a parsed title — `The Wailing (2016)`,
        // `Example Show S01E02` — and the user searches for what they were shown.
        Row(4L, "The.Wailing.2016.1080p.BluRay.x265-GRP.mkv"),
        Row(5L, "Amélie.2001.1080p.BluRay.x264.mkv"),
        Row(6L, "Spider.Man.2002.1080p.WEB-DL.mkv"),
        Row(7L, "Example.Show.S1E2.720p.WEB-DL.mkv"),
        Row(8L, "Brødre.2004.1080p.mkv"),
    )

    private val device: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(device)
    }

    private fun matching(query: String): List<Long> =
        LibrarySearchPolicy.index(library, Row::name).matching(query).map { it.id }

    @Test fun blankQueryReturnsTheOriginalScopedList() {
        assertSame(library, LibrarySearchPolicy.index(library, Row::name).matching("  "))
    }

    @Test fun aQueryOfNothingButSeparatorsIsBlankToo() {
        // It folds to no words at all, and half-typed punctuation must not empty the grid.
        assertSame(library, LibrarySearchPolicy.index(library, Row::name).matching(" ... "))
    }

    @Test fun trimmedQueryMatchesFilenameIgnoringCaseInStableOrder() {
        assertEquals(listOf(1L, 3L), matching(" ArRiVaL "))
    }

    @Test fun theTitleOnTheTileFindsTheReleaseFilenameUnderIt() {
        // The bug this file exists for: the tile reads `The Wailing (2016)`, and typing that
        // matched nothing because the name being searched still had dots where the user
        // typed spaces.
        assertEquals(listOf(4L), matching("the wailing"))
        assertEquals(listOf(4L), matching("The Wailing (2016)"))
    }

    @Test fun everyWordIsFoundSeparatelySoAQueryCanSpanTheReleaseJunkBetweenThem() {
        // No contiguous substring of the filename contains both, in either order.
        assertEquals(listOf(4L), matching("wailing 2016"))
        assertEquals(listOf(4L), matching("2016 wailing"))
    }

    @Test fun aMissingWordRemovesTheRowBecauseEveryWordMustBeFound() {
        assertEquals(emptyList<Long>(), matching("wailing 2017"))
        assertEquals(emptyList<Long>(), matching("wailing arrival"))
    }

    @Test fun aHalfTypedWordStillNarrows() {
        assertEquals(listOf(4L), matching("wail"))
        assertEquals(listOf(4L), matching("the wail"))
    }

    @Test fun substringQueryCanMatchTheMiddleOfAFilename() {
        assertEquals(listOf(2L), matching("lick te"))
    }

    @Test fun aWordIsFoundInsideAWordAndThatIsTheDeliberateTradeOff() {
        // Each word is looked for anywhere in the row rather than only where a word of it
        // starts. The cost is here in the open: `ider` reaches `Spider`, so a very short
        // query narrows less than it looks like it should. The benefit is the whole reason —
        // failing to show a video the user has is the expensive mistake in a filter box over
        // their own library, and every further word typed is an AND that tightens it again.
        assertEquals(listOf(6L), matching("ider"))
        assertEquals(listOf(6L), matching("ider 2002"))
    }

    @Test fun separatorsAreWordBreaksWhicheverSideTheyAreTypedOn() {
        assertEquals(listOf(6L), matching("spider man"))
        assertEquals(listOf(6L), matching("spider-man"))
        assertEquals(listOf(6L), matching("spider.man"))
        assertEquals(listOf(3L), matching("behind the scenes"))
    }

    @Test fun aSeparatorOnOneSideOnlyDoesNotMakeTwoDifferentVideos() {
        // `Spider.Man` on disk is `spiderman` to half the people who type it, and a file
        // named `Spiderman` is `spider man` to the other half. Neither spelling is wrong, so
        // the boundaries come out of the row and the query is cut at them.
        assertEquals(listOf(6L), matching("spiderman"))
        val fused = listOf(Row(1L, "Spiderman.2002.1080p.mkv"))
        val index = LibrarySearchPolicy.index(fused, Row::name)
        assertEquals(listOf(1L), index.matching("spider man").map { it.id })
        assertEquals(listOf(1L), index.matching("spiderman").map { it.id })
    }

    @Test fun theNameOnDiskIsStillSearchableThoughTheTileHidesIt() {
        // The parsed title drops all of this. Searching by codec or source is the one thing
        // the old filename substring did well, and it must survive the fix.
        assertEquals(listOf(4L), matching("x265"))
        assertEquals(listOf(6L, 7L), matching("web dl"))
    }

    @Test fun theTilesZeroPaddedEpisodeFindsAnUnpaddedFilename() {
        // `Example.Show.S1E2` is shown as `Example Show S01E02`; both spellings are indexed.
        assertEquals(listOf(7L), matching("s01e02"))
        assertEquals(listOf(7L), matching("example show s1e2"))
    }

    @Test fun accentsFoldInBothDirections() {
        assertEquals(listOf(5L), matching("amelie"))
        assertEquals(listOf(5L), matching("amélie"))
        assertEquals(listOf(5L), matching("AMÉLIE"))
    }

    @Test fun aLetterCarryingItsMarkInsideTheGlyphStillFolds() {
        // No decomposition separates these, and the phone's keyboard rarely spells them.
        assertEquals(listOf(8L), matching("brodre"))
        assertEquals(listOf(8L), matching("brødre"))
    }

    @Test fun aTurkishPhoneStillFindsAnAsciiTitle() {
        // Lower-casing with the device's locale maps this I to a dotless ı on exactly one
        // side of the comparison, and the film is then unreachable from its own name.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        val dottedI = listOf(Row(1L, "INCEPTION.2010.1080p.BluRay.x264.mkv"))
        val found = LibrarySearchPolicy.index(dottedI, Row::name).matching("inception")
        assertEquals(listOf(1L), found.map { it.id })
    }

    @Test fun aScriptWrittenWithoutSpacesIsSearchableBeyondItsFirstCharacter() {
        // Nothing here marks where one word ends, so a whole Han or Hangul title is a single
        // word and only a rule that looks inside one can reach past its opening character.
        val unspaced = listOf(
            Row(1L, "君の名は.2016.1080p.mkv"),
            Row(2L, "기생충.2019.2160p.mkv"),
        )
        val index = LibrarySearchPolicy.index(unspaced, Row::name)
        assertEquals(listOf(1L), index.matching("君の名は").map { it.id })
        assertEquals(listOf(1L), index.matching("名は").map { it.id })
        assertEquals(listOf(2L), index.matching("생충").map { it.id })
    }

    @Test fun aNonLatinTitleKeepsTheMarksThatAreItsLetters() {
        // The mark here is a letter of the word, not decoration on it: folding it away the
        // way a Latin diacritic is folded would make the spelling WITHOUT it match, which is
        // a different word, and would silently answer with the wrong film.
        val devanagari = listOf(Row(1L, "दंगल.2016.1080p.mkv"))
        val index = LibrarySearchPolicy.index(devanagari, Row::name)
        assertEquals(listOf(1L), index.matching("दंगल").map { it.id })
        assertEquals(emptyList<Long>(), index.matching("दगल").map { it.id })
    }

    @Test fun noMatchReturnsAnEmptyList() {
        assertEquals(emptyList<Long>(), matching("nope"))
    }
}
