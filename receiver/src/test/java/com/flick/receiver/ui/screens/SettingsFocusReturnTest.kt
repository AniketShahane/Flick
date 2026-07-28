package com.flick.receiver.ui.screens

import com.flick.receiver.net.PairedPhone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the D-pad lands after a per-phone Forget. A LazyColumn item that goes
 * away takes focus with it, so every case here has to name a row that still
 * exists — "nowhere" would leave the remote steering nothing, recoverable only
 * with Back.
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
     * of the screen has.
     */
    @Test fun forgettingTheOnlyPhoneLandsOnDeviceName() {
        assertEquals(
            SettingsFocusReturn.DeviceName,
            settingsFocusReturnAfterForget(listOf(phone("keyA")), "keyA"),
        )
    }

    @Test fun aKeyIdThatIsNotInTheListStillLandsSomewhere() {
        assertEquals(
            SettingsFocusReturn.DeviceName,
            settingsFocusReturnAfterForget(listOf(phone("keyA")), "keyGone"),
        )
        assertEquals(
            SettingsFocusReturn.DeviceName,
            settingsFocusReturnAfterForget(emptyList(), "keyA"),
        )
    }

    /** Whatever the list, the answer names a row — never nothing. */
    @Test fun everyForgetInAListResolvesToARowThatSurvivesIt() {
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
                SettingsFocusReturn.DeviceName -> Unit
            }
        }
    }
}
