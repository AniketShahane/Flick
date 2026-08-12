package com.flick.receiver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.flick.receiver.session.ReceiverErrorFace
import com.flick.receiver.ui.screens.ErrorScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The D-pad contract and the RENDERED COPY, per face.
 *
 * Asserting on the enum alone is what let the shipped screen collapse a three-value
 * diagnosis into a Boolean: `errorKindFor` was exhaustively tested and its only consumer
 * ignored the third value, so every decoder failure rendered as "Your phone stopped
 * serving". A copy assertion is the one test that catches that.
 */
class ErrorScreenFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(face: ReceiverErrorFace, beforeReady: Boolean = true) {
        composeRule.setContent {
            FlickTvTheme {
                ErrorScreen(
                    face = face,
                    deviceLabel = "Pixel",
                    onDismiss = {},
                    beforeReady = beforeReady,
                )
            }
        }
    }

    @Test
    fun dismiss_action_receives_initial_focus() {
        show(ReceiverErrorFace.PHONE_UNREACHABLE, beforeReady = false)
        composeRule.onNodeWithText("End session").assertIsFocused()
    }

    /** Exactly one action, always. A retry needs a castId only the sender can mint. */
    @Test
    fun the_screen_offers_exactly_one_control() {
        show(ReceiverErrorFace.VIDEO_CODEC_UNSUPPORTED)
        composeRule.onNodeWithText("Back to standby").assertIsFocused()
        assertEquals(0, composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Keep waiting").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("End session").fetchSemanticsNodes().size)
    }

    @Test
    fun a_missing_codec_never_blames_the_phone() {
        show(ReceiverErrorFace.VIDEO_CODEC_UNSUPPORTED)
        composeRule.onNodeWithText("This TV can't decode this video").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Your phone stopped serving").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun a_refused_video_format_is_not_called_a_missing_codec() {
        show(ReceiverErrorFace.VIDEO_FORMAT_UNSUPPORTED)
        composeRule.onNodeWithText("This TV can't take this video track").assertIsDisplayed()
    }

    /** In the words the phone's own face for this event uses: no engineering nouns. */
    @Test
    fun dolby_vision_is_named_plainly() {
        show(ReceiverErrorFace.HDR_PROFILE_UNSUPPORTED)
        composeRule.onNodeWithText("This TV can't play Dolby Vision").assertIsDisplayed()
    }

    @Test
    fun an_unreadable_container_is_about_the_wrapper() {
        show(ReceiverErrorFace.CONTAINER_UNSUPPORTED)
        composeRule.onNodeWithText("This TV can't open this file").assertIsDisplayed()
    }

    @Test
    fun malformed_bytes_are_about_this_copy_of_the_film() {
        show(ReceiverErrorFace.MEDIA_MALFORMED)
        composeRule.onNodeWithText("This film's data wouldn't decode").assertIsDisplayed()
    }

    @Test
    fun a_decoder_that_would_not_open_says_the_phone_is_fine() {
        show(ReceiverErrorFace.DECODER_UNAVAILABLE)
        composeRule.onNodeWithText("This TV couldn't start a decoder").assertIsDisplayed()
    }

    @Test
    fun a_reclaimed_decoder_names_the_other_app() {
        show(ReceiverErrorFace.DECODER_TAKEN, beforeReady = false)
        composeRule.onNodeWithText("Another app took this TV's decoder").assertIsDisplayed()
        composeRule.onNodeWithText("while the film was playing", substring = true).assertIsDisplayed()
    }

    /** 4006 also fires at codec init, where no film was ever playing to be interrupted. */
    @Test
    fun a_decoder_taken_before_the_film_started_does_not_claim_one_was_playing() {
        show(ReceiverErrorFace.DECODER_TAKEN, beforeReady = true)
        composeRule.onNodeWithText("before the film could start", substring = true).assertIsDisplayed()
    }

    /** The captured incident: a perfect picture ended under "Your phone stopped serving". */
    @Test
    fun a_refused_audio_output_says_the_picture_was_fine()  {
        show(ReceiverErrorFace.AUDIO_OUTPUT_REFUSED, beforeReady = false)
        composeRule.onNodeWithText("This TV's sound output refused the film").assertIsDisplayed()
        composeRule.onNodeWithText("The picture was fine", substring = true).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Your phone stopped serving").fetchSemanticsNodes().size,
        )
    }

    /**
     * The rebuild is latched for the process, so a later film can meet the second
     * refusal inside its startup window — with no picture to have been fine.
     */
    @Test
    fun a_refusal_before_any_frame_claims_nothing_about_the_picture() {
        show(ReceiverErrorFace.AUDIO_OUTPUT_REFUSED, beforeReady = true)
        assertEquals(
            0,
            composeRule.onAllNodesWithText("The picture was fine", substring = true)
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("never started", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_startup_timeout_says_the_first_bytes_arrived() {
        show(ReceiverErrorFace.STARTUP_TIMEOUT)
        composeRule.onNodeWithText("This TV didn't reach the first frame").assertIsDisplayed()
    }

    @Test
    fun an_http_rejection_names_the_phone_turning_the_request_down() {
        show(ReceiverErrorFace.SENDER_REFUSED)
        composeRule.onNodeWithText("Your phone wouldn't hand over the file").assertIsDisplayed()
    }

    @Test
    fun the_senders_own_stop_keeps_its_shipped_title_and_promises_no_held_place() {
        show(ReceiverErrorFace.SENDER_NOT_SERVING, beforeReady = false)
        composeRule.onNodeWithText("Your phone stopped serving").assertIsDisplayed()
        composeRule.onNodeWithText("the film stopped arriving", substring = true).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Your place is held.", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    /** A pre-flight refusal: nothing was ever served, so nothing can have stopped arriving. */
    @Test
    fun a_refusal_before_the_first_frame_does_not_claim_the_film_stopped_arriving() {
        show(ReceiverErrorFace.SENDER_NOT_SERVING, beforeReady = true)
        assertEquals(
            0,
            composeRule.onAllNodesWithText("stopped arriving", substring = true)
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("never handed the film over", substring = true)
            .assertIsDisplayed()
    }

    /**
     * The probe never started the film, so a card claiming a mid-stream departure is
     * false — and the control socket was still up, which refutes both "left the network"
     * and the offer to end a session that is answering.
     */
    @Test
    fun an_unreachable_file_server_before_the_first_frame_does_not_claim_mid_film() {
        show(ReceiverErrorFace.PHONE_UNREACHABLE, beforeReady = true)
        composeRule.onNodeWithText("Your phone's file server didn't answer").assertIsDisplayed()
        composeRule.onNodeWithText("Pixel is talking to this TV", substring = true)
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Lost sight of your phone").fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("Back to standby").assertIsFocused()
    }

    @Test
    fun the_same_face_mid_film_promises_no_resume_nothing_implements() {
        show(ReceiverErrorFace.PHONE_UNREACHABLE, beforeReady = false)
        composeRule.onNodeWithText("Lost sight of your phone").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing here resumes on its own", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun a_dead_control_socket_has_its_own_sentence() {
        show(ReceiverErrorFace.LINK_LOST, beforeReady = false)
        composeRule.onNodeWithText("Your phone stopped talking to this TV").assertIsDisplayed()
        composeRule.onNodeWithText("End session").assertIsFocused()
    }

    @Test
    fun this_tv_losing_its_address_says_so_rather_than_blaming_the_phone() {
        show(ReceiverErrorFace.TV_NETWORK_CHANGED, beforeReady = false)
        composeRule.onNodeWithText("This TV's network address changed").assertIsDisplayed()
        composeRule.onNodeWithText("The film stopped", substring = true).assertIsDisplayed()
    }

    /** The address can go away mid-handshake, where no film was ever started to stop. */
    @Test
    fun losing_the_address_before_the_first_frame_stops_no_film() {
        show(ReceiverErrorFace.TV_NETWORK_CHANGED, beforeReady = true)
        composeRule.onNodeWithText("The cast couldn't start", substring = true).assertIsDisplayed()
    }

    /**
     * It may claim neither the sound nor the clock: the same verdict is reached with the
     * clock frozen, and a DTS film has already been told it is playing silent.
     */
    @Test
    fun a_stopped_picture_claims_only_what_stopped() {
        show(ReceiverErrorFace.PICTURE_STOPPED, beforeReady = false)
        composeRule.onNodeWithText("The picture stopped on this TV").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("The sound and the clock", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun the_unexplained_face_says_both_devices_may_be_fine() {
        show(ReceiverErrorFace.PLAYBACK_STOPPED)
        composeRule.onNodeWithText("This TV couldn't play this film").assertIsDisplayed()
        composeRule.onNodeWithText("may both be fine", substring = true).assertIsDisplayed()
    }
}
