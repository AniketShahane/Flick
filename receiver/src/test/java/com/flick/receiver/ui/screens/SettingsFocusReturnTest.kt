package com.flick.receiver.ui.screens

import com.flick.receiver.net.PairedPhone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the D-pad lands after a per-phone Forget. A LazyColumn item that goes
 * away takes focus with it, so every case here has to name a control that still
 * exists — "nowhere" would leave the remote steering nothing, recoverable only
 * with Back.
 *
 * The fallback is the paired-phone pane's own Back key rather than the settings
 * column's first row: the phones live in a drill-in now, and the landing has to
 * be something that pane has whatever the list has been reduced to.
 */
class SettingsFocusReturnTest {
    private fun phone(keyId: String) = PairedPhone(keyId, "Phone $keyId", null)

    @Test fun forgettingARowLandsOnTheRowBelowIt() {
        val phones = listOf(phone("keyA"), phone("keyB"), phone("keyC"))
        assertEquals(
            SettingsFocusReturn.Phone("keyB"),
            settingsFocusReturnAfterForget(phones, "keyA"),
        )
    }

    @Test fun forgettingTheLastRowLandsOnTheRowAboveIt() {
        val phones = listOf(phone("keyA"), phone("keyB"), phone("keyC"))
        assertEquals(
            SettingsFocusReturn.Phone("keyB"),
            settingsFocusReturnAfterForget(phones, "keyC"),
        )
    }

    /**
     * One paired phone is the COMMON state, not an edge case. There is no
     * neighbour to inherit focus, so the landing has to be a control every state
     * of the pane has.
     */
    @Test fun forgettingTheOnlyPhoneLandsOnTheBackKey() {
        assertEquals(
            SettingsFocusReturn.ListBack,
            settingsFocusReturnAfterForget(listOf(phone("keyA")), "keyA"),
        )
    }

    @Test fun aKeyIdThatIsNotInTheListStillLandsSomewhere() {
        assertEquals(
            SettingsFocusReturn.ListBack,
            settingsFocusReturnAfterForget(listOf(phone("keyA")), "keyGone"),
        )
        assertEquals(
            SettingsFocusReturn.ListBack,
            settingsFocusReturnAfterForget(emptyList(), "keyA"),
        )
    }

    /** Whatever the list, the answer names a control — never nothing. */
    @Test fun everyForgetInAListResolvesToAControlThatSurvivesIt() {
        val phones = (1..4).map { phone("key$it") }
        phones.forEach { forgotten ->
            val remaining = phones.filterNot { it.keyId == forgotten.keyId }
            when (val landing = settingsFocusReturnAfterForget(phones, forgotten.keyId)) {
                is SettingsFocusReturn.Phone ->
                    assertEquals(
                        "the landing row must outlive the forget",
                        1,
                        remaining.count { it.keyId == landing.keyId },
                    )
                SettingsFocusReturn.ListBack -> Unit
            }
        }
    }
}
