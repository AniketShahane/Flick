package com.flick.receiver.ui.components

import androidx.compose.ui.input.key.Key
import com.flick.receiver.tvRemoteHorizontalSeeks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VolumeKeyPolicyTest {
    @Test
    fun heldCenterConsumesRepeatsWithoutTogglingTheEngagementDispatchContract() {
        val initial = volumeKeyAction(
            key = Key.DirectionCenter,
            repeatCount = 0,
            engaged = false,
            enabled = true,
        )
        assertEquals(VolumeKeyAction.ToggleEngagement, initial)

        // The initial press makes the control engaged; every held repeat must
        // remain consumed without producing another toggle or a playback seek.
        val held = volumeKeyAction(
            key = Key.DirectionCenter,
            repeatCount = 1,
            engaged = true,
            enabled = true,
        )
        assertEquals(VolumeKeyAction.ConsumeRepeat, held)
    }

    @Test
    fun engagedVolumeNoLongerNeedsALatchToKeepItsHorizontalKeys() {
        // Volume used to publish an engagement latch to the Activity, because the
        // policy captured physical left/right before Compose could see them. It no
        // longer does: with the chrome up, horizontal keys are the focus system's
        // unless the scrub bar holds focus, and volume can only be engaged while
        // volume holds it.
        assertFalse(tvRemoteHorizontalSeeks(chromeVisible = true, scrubFocused = false))
    }
}
