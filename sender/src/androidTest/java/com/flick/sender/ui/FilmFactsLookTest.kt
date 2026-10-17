package com.flick.sender.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import com.flick.sender.media.FilmDetails
import com.flick.sender.ui.components.RuntimeChip
import com.flick.sender.ui.screens.SynopsisLeaf
import com.flick.sender.ui.theme.CinemaDeep
import com.flick.sender.ui.theme.FlickCinematicTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The runtime chip and the synopsis drawn with seeded data, without TMDB. Writes what it draws
 * to the app's external files dir (`looks/`) for judging by eye.
 */
@RunWith(AndroidJUnit4::class)
class FilmFactsLookTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val details = FilmDetails(
        overview = "A computer hacker learns from mysterious rebels about the true nature of his " +
            "reality and his role in the war against its controllers.",
        tagline = "Believe the unbelievable.",
        genres = listOf("Action", "Science Fiction"),
        year = 1999,
        runtimeMinutes = 136,
        creators = listOf("Lana Wachowski", "Lilly Wachowski"),
        cast = listOf("Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss", "Hugo Weaving"),
        show = false,
    )

    @Test
    fun atDoubleFontTheRuntimeChipStaysInsideItsRow() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FlickCinematicTheme {
                    Box(Modifier.width(336.dp).background(CinemaDeep).padding(8.dp).testTag("row")) {
                        RuntimeChip(text = "2h 16m", known = true)
                    }
                }
            }
        }
        val row = composeRule.onNodeWithTag("row").getBoundsInRoot()
        val chip = composeRule.onNodeWithContentDescription("Running time 2h 16m").assertIsDisplayed().getBoundsInRoot()
        assertTrue("runtime inside the row", chip.left >= row.left && chip.right <= row.right)
        save("runtime-font2x")
    }

    @Test
    fun synopsisLeafReadsWithEveryPart() {
        composeRule.setContent {
            FlickCinematicTheme {
                Box(Modifier.width(336.dp).height(900.dp).background(CinemaDeep)) {
                    SynopsisLeaf(
                        details = details,
                        title = "The Matrix",
                        logoPath = null,
                        imageLoader = ImageLoader(InstrumentationRegistry.getInstrumentation().targetContext),
                        onClose = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("The Matrix").assertIsDisplayed()
        composeRule.onNodeWithText("Believe the unbelievable.").assertIsDisplayed()
        composeRule.onNodeWithText("Keanu Reeves, Laurence Fishburne, Carrie-Anne Moss, Hugo Weaving").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close the synopsis").assertIsDisplayed()
        save("synopsis-leaf")
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onNode(isRoot()).captureToImage()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("looks") ?: return
        File(dir, "$name.png").outputStream().use { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
