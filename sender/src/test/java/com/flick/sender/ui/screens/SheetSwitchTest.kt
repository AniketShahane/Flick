package com.flick.sender.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose dismissal a sheet's exit was, in the orders the two closers can arrive in.
 *
 * The window this guards is real but narrow, and the manual pairing sheet is where it
 * costs something: attributed to the app, a dismissal skips `dismissPairLaunch` and the
 * stale launch it left behind prefills the next open. It is a race between a finger and
 * an arriving control frame, so nothing about it is reproducible by hand — which is
 * exactly why the rule lives in a class that can be driven directly.
 */
class SheetSwitchTest {

    @Test fun anExitTheAppAskedForIsTheApps() {
        val sheet = SheetSwitch()
        sheet.open()
        sheet.close()
        sheet.leaving()
        assertTrue(sheet.closingByApp)
    }

    @Test fun anExitTheUserStartedIsTheirs() {
        val sheet = SheetSwitch()
        sheet.open()
        // The scrim, Back or a drag past the threshold: the sheet is still wanted when
        // it starts leaving, and it is the user who is done with it.
        sheet.leaving()
        assertFalse(sheet.closingByApp)
    }

    @Test fun anAppCloseArrivingDuringAUsersExitCannotStealIt() {
        val sheet = SheetSwitch()
        sheet.open()
        sheet.leaving()
        // An INVALID_QR landing on one of the frames the sheet spends travelling.
        sheet.close()
        assertFalse(
            "a close that arrived after the exit began must not rewrite whose exit it was",
            sheet.closingByApp,
        )
    }

    @Test fun theVerdictDoesNotOutliveTheExitItWasMadeFor() {
        val sheet = SheetSwitch()
        sheet.open()
        sheet.close()
        sheet.leaving()
        sheet.gone()
        assertFalse(sheet.closingByApp)
        // The next open is a fresh sheet, and its exit is nobody's yet.
        sheet.open()
        sheet.leaving()
        assertFalse(sheet.closingByApp)
    }

    @Test fun closingASheetThatWasNeverOpenedDoesNothing() {
        val sheet = SheetSwitch()
        sheet.close()
        assertFalse(sheet.composed)
        assertFalse(sheet.visible)
    }
}
