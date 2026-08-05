package com.flick.sender.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every subtitle pick takes a persistable read and nothing expires one, so without a sweep
 * the app holds a standing read on every subtitle file it has ever been pointed at. Both the
 * grants and the sweep are invisible from every screen — the same reason
 * `RetiredSubtitleFolderTest` asserts its guarantee against the source — so what makes the
 * sweep safe is asserted here rather than left to be noticed.
 */
class InheritedUriGrantTest {

    @Test fun aGrantAnEarlierProcessTookIsHandedBack() {
        assertTrue(grantPredatesProcess(persistedAtMs = 1_000L, processStartedAtMs = 2_000L))
    }

    /**
     * The pick made WHILE the sweep is running: its grant is the read on the file about to
     * be served, and releasing it would break the cast the viewer is setting up. This is
     * the case the rule exists for, and the reason it is a timestamp rather than a claim
     * that the sweep gets there first.
     */
    @Test fun aGrantTakenSinceThisProcessStartedIsKept() {
        assertFalse(grantPredatesProcess(persistedAtMs = 3_000L, processStartedAtMs = 2_000L))
    }

    /** A tie could be this process's own pick, and the ambiguous grant is the one to keep. */
    @Test fun aGrantStampedAtTheStartingMomentIsKept() {
        assertFalse(grantPredatesProcess(persistedAtMs = 2_000L, processStartedAtMs = 2_000L))
    }

    @Test fun theApplicationIsWhatSweepsAndItLeavesTheMainThreadFirst() {
        assertTrue(
            "no screen can expire a grant it never shows; the application must start this",
            application.contains("releaseInheritedUriGrants"),
        )
        val declaration = media.indexOf("suspend fun releaseInheritedUriGrants(")
        assertTrue("the sweep must be a function of its own", declaration >= 0)
        val dispatch = media.indexOf("withContext(Dispatchers.IO)", declaration)
        val enumerate = media.indexOf("persistedUriPermissions", declaration)
        assertTrue(
            "it runs on every cold start, so it must leave the main thread before it asks " +
                "the resolver anything",
            dispatch in declaration..enumerate,
        )
    }

    /**
     * The boundary has to be read on the caller's thread. Read inside the dispatch it would
     * be whenever a pool thread happened to start, which a pick could beat.
     */
    @Test fun theBoundaryIsReadBeforeTheSweepLeavesTheCallersThread() {
        val declaration = media.indexOf("suspend fun releaseInheritedUriGrants(")
        val clock = media.indexOf("System.currentTimeMillis()", declaration)
        val dispatch = media.indexOf("withContext(Dispatchers.IO)", declaration)
        assertTrue("the process start must be read at all", clock >= 0)
        assertTrue("and before the dispatch, not inside it", clock in declaration..dispatch)
    }

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
