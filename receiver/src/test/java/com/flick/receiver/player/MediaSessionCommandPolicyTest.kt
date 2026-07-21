package com.flick.receiver.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionCommandPolicyTest {
    @Test fun stopNextPreviousButtonsAreConsumedBeforeDefaultDispatch() {
        assertTrue(consumesUnsupportedMediaButton(MediaButtonKind.STOP))
        assertTrue(consumesUnsupportedMediaButton(MediaButtonKind.NEXT))
        assertTrue(consumesUnsupportedMediaButton(MediaButtonKind.PREVIOUS))
        assertFalse(consumesUnsupportedMediaButton(MediaButtonKind.OTHER))
    }

    @Test fun commandsThatBypassCastOwnershipAreRejected() {
        assertTrue(rejectsExternalPlayerCommand(Player.COMMAND_STOP))
        assertTrue(rejectsExternalPlayerCommand(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(rejectsExternalPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(rejectsExternalPlayerCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertTrue(rejectsExternalPlayerCommand(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(rejectsExternalPlayerCommand(Player.COMMAND_CHANGE_MEDIA_ITEMS))
    }

    @Test fun supportedTransportCommandsRemainAvailable() {
        assertFalse(rejectsExternalPlayerCommand(Player.COMMAND_PLAY_PAUSE))
        assertFalse(rejectsExternalPlayerCommand(Player.COMMAND_SEEK_BACK))
        assertFalse(rejectsExternalPlayerCommand(Player.COMMAND_SEEK_FORWARD))
    }
}
