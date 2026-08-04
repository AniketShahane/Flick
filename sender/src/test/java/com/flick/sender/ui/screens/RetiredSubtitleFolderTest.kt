package com.flick.sender.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The folder source is gone from the subtitles sheet, and the two things it left
 * behind on phones that used it are both invisible from the screen: a persisted SAF
 * tree grant, which nothing expires, and the preference naming it. Dropping the
 * release or clearing the preference first would strand that grant for good and
 * look identical while doing it, so the guarantee is asserted against the source —
 * the same reason `BackupExclusionsTest` reads prefs names out of the sources.
 */
class RetiredSubtitleFolderTest {

    @Test fun theSheetOffersOnlyTheFileAndOnlineSources() {
        assertTrue(
            "the sheet must declare exactly the two sources it offers",
            sheet.contains("private enum class SubtitleSource { FILE, ONLINE }"),
        )
        assertTrue(
            "a folder source must not survive anywhere in the sheet",
            !sheet.contains("FOLDER") && !sheet.contains("FolderPane"),
        )
    }

    @Test fun theRetiredFolderGrantIsReleasedBeforeItsPreferenceIsForgotten() {
        val release = media.indexOf("releasePersistableUriPermission")
        val forget = media.indexOf("store.forget()")
        assertTrue(
            "the persisted folder grant must be handed back; no other surface can now",
            release >= 0,
        )
        assertTrue("the preference naming the folder must be cleared", forget >= 0)
        assertTrue(
            "the grant must be released before the preference naming it is cleared, or " +
                "there is no URI left to release and the grant is stranded",
            release < forget,
        )
    }

    /**
     * An invisible grant cannot be expired by a screen: a phone that granted a folder in
     * the old build and never opens the subtitles sheet again would hold it for good.
     */
    @Test fun theGrantIsGivenUpWithoutWaitingForAScreenTheUserMayNeverOpen() {
        assertTrue(
            "the application must be what releases it",
            application.contains("releaseRetiredSubtitleFolder"),
        )
        assertTrue(
            "no screen may be the only thing that expires it",
            !sheet.contains("releasePersistableUriPermission"),
        )
        // Unconditional now means every cold start, so it may not touch the main thread.
        val declaration = media.indexOf("suspend fun releaseRetiredSubtitleFolder(")
        val dispatch = media.indexOf("withContext(Dispatchers.IO)", declaration)
        assertTrue("the release must be a function of its own", declaration >= 0)
        assertTrue(
            "it must leave the main thread before it reads anything, or a cold start pays " +
                "for it on the way to the first frame",
            dispatch in declaration..media.indexOf("releasePersistableUriPermission", declaration),
        )
    }

    private val sheet: String by lazy { source("ui/screens/SubtitlesSheet.kt") }
    private val media: String by lazy { source("media/SubtitleFiles.kt") }
    private val application: String by lazy { source("FlickApplication.kt") }

    private fun source(relative: String): String {
        val marker = "src/main/java/com/flick/sender/$relative"
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, marker), File(it, "sender/$marker")) }
            .firstOrNull(File::isFile)
            ?: throw AssertionError("cannot locate $marker from ${File("").absolutePath}")
        return file.readText()
    }
}
