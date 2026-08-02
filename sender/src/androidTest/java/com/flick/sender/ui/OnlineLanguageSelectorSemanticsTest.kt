package com.flick.sender.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.sender.R
import com.flick.sender.net.OpenSubtitlesSearchPolicy
import com.flick.sender.ui.screens.OnlineLanguageSelector
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlineLanguageSelectorSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun selectorDefaultsToEnglishAndAnnouncesItsPurposeAndValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val labels = context.resources.getStringArray(R.array.subs_online_language_names)
        org.junit.Assert.assertEquals(com.flick.sender.net.OpenSubtitlesLanguage.entries.size, labels.size)
        val english = labels[
            OpenSubtitlesSearchPolicy.DefaultLanguage.ordinal
        ]
        val description = context.getString(R.string.a11y_subs_online_language, english)

        composeRule.setContent {
            FlickTheme(darkTheme = false, dynamicColor = false) {
                OnlineLanguageSelector(
                    selected = OpenSubtitlesSearchPolicy.DefaultLanguage,
                    enabled = true,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(description).assertHasClickAction()
    }
}
