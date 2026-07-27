package com.flick.receiver.ui.components

import androidx.compose.ui.input.key.Key
import com.flick.receiver.receiverPlaybackGesturesEnabled
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
        assertFalse(
            receiverPlaybackGesturesEnabled(
                playbackActive = true,
                panelOpen = false,
                volumeEngaged = true,
            ),
        )
    }
}
