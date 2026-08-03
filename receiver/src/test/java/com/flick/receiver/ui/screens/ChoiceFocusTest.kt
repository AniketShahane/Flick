package com.flick.receiver.ui.screens

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import com.flick.receiver.player.SubtitleTrackFocusIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The displacement rule decides whether a live track refresh may move the
 * viewer's focus. Its two exclusions are what stop it firing on someone who is
 * simply sitting somewhere while the list changes around them.
 */
class ChoiceFocusTest {

    @Test
    fun a_focused_track_that_leaves_the_list_is_displaced() {
        val gone = identity()
        val kept = identity()
        val focus = ChoiceFocus()
        focus.report(gone, focused = true)

        assertTrue(focus.displacedBy(listOf(kept)))
    }

    @Test
    fun a_focused_track_that_survives_the_refresh_is_not_displaced() {
        val kept = identity()
        val other = identity()
        val focus = ChoiceFocus()
        focus.report(kept, focused = true)

        assertFalse(focus.displacedBy(listOf(kept, other)))
    }

    @Test
    fun focus_on_off_is_never_displaced_by_a_track_disappearing() {
        val gone = identity()
        val focus = ChoiceFocus()
        focus.report(OffChoice, focused = true)

        assertFalse(focus.displacedBy(emptyList()))
        assertFalse(focus.displacedBy(listOf(identity())))
        assertSame(OffChoice, focus.current)
        assertFalse(focus.displacedBy(listOf(gone)))
    }

    @Test
    fun focus_outside_the_choice_rows_is_never_displaced() {
        val gone = identity()
        val focus = ChoiceFocus()
        focus.report(gone, focused = true)
        // Leaving the row for a size cell clears it; the size cells report nothing.
        focus.report(gone, focused = false)

        assertNull(focus.current)
        assertFalse(focus.displacedBy(emptyList()))
    }

    @Test
    fun a_row_losing_focus_after_another_gained_it_does_not_clear_the_new_one() {
        val first = identity()
        val second = identity()
        val focus = ChoiceFocus()
        focus.report(first, focused = true)
        // Compose reports the gain before the loss when focus moves between rows.
        focus.report(second, focused = true)
        focus.report(first, focused = false)

        assertSame(second, focus.current)
    }

    @Test
    fun an_empty_list_displaces_a_focused_track() {
        val gone = identity()
        val focus = ChoiceFocus()
        focus.report(gone, focused = true)

        assertTrue(focus.displacedBy(emptyList()))
    }

    private fun identity() = SubtitleTrackFocusIdentity(
        mediaTrackGroup = TrackGroup(
            Format.Builder().setSampleMimeType("application/x-subrip").build(),
        ),
        trackIndexWithinGroup = 0,
    )
}
