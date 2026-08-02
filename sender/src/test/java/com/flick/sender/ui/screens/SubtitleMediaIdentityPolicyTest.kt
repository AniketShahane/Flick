package com.flick.sender.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubtitleMediaIdentityPolicyTest {

    @Test fun onlinePaneIsKeyedByMediaUriAndReceivesTheRawFilename() {
        assertTrue(
            "OnlinePane must be recreated at the media-URI boundary so remembered state and jobs cannot cross files",
            Regex(
                "SubtitleSource\\.ONLINE\\s*->\\s*key\\(item\\?\\.uri\\)\\s*\\{\\s*" +
                    "OnlinePane\\(\\s*videoName\\s*=\\s*videoName,\\s*videoUri\\s*=\\s*item\\?\\.uri,",
            ).containsMatchIn(source),
        )
    }

    @Test fun headerUsesDisplayNameWhileMatchingAndSearchKeepRawName() {
        assertTrue(source.contains("val videoName = item?.name"))
        assertTrue(source.contains("val videoDisplayName = item?.displayName()"))
        assertTrue(source.contains("text = videoDisplayName?.let"))
        assertTrue(source.contains("videoName = videoName"))
    }

    @Test fun editingSearchInputsInvalidatesAndGuardsTheActiveRequest() {
        assertTrue(source.contains("fun invalidateSearch()"))
        assertTrue(source.contains("searchJob?.cancel()"))
        assertTrue(source.contains("results = null"))
        assertTrue(source.contains("if (generation != searchGeneration) return@launch"))
        assertTrue(source.contains("movieFingerprint = requestedFingerprint"))
        assertTrue(source.contains("language = requestedLanguage"))
    }

    private val source: String by lazy {
        val marker = "src/main/java/com/flick/sender/ui/screens/SubtitlesSheet.kt"
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, marker), File(it, "sender/$marker")) }
            .firstOrNull(File::isFile)
            ?: throw AssertionError("cannot locate $marker from ${File("").absolutePath}")
        file.readText()
    }
}
