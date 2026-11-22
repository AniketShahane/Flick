package com.flick.sender.library

import com.flick.sender.library.KnockReplyPolicy.ResumeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectPromptPolicyTest {

    private fun reply(
        snoozed: Boolean = false,
        mode: TvConnectMode = TvConnectMode.ASK,
        visible: Boolean = false,
        notifications: Boolean = true,
        resuming: Boolean = false,
    ) = KnockReplyPolicy.reply(snoozed, mode, visible, notifications, resuming)

    @Test fun theReplyTable() {
        assertEquals(KnockReply.ASK, reply())
        assertEquals(KnockReply.CONNECT, reply(mode = TvConnectMode.AUTO))
        assertEquals(KnockReply.CONNECT, reply(visible = true))
        assertEquals(KnockReply.CONNECT, reply(notifications = false))
        // "Not now" outranks everything, automatic mode and the app in front included.
        assertEquals(KnockReply.DECLINED, reply(snoozed = true))
        assertEquals(KnockReply.DECLINED, reply(snoozed = true, mode = TvConnectMode.AUTO))
        assertEquals(KnockReply.DECLINED, reply(snoozed = true, visible = true, notifications = false))
        assertEquals(KnockReply.CONNECT, reply(mode = TvConnectMode.AUTO, notifications = false))
    }

    private fun step(
        reply: KnockReply,
        mode: TvConnectMode = TvConnectMode.ASK,
        visible: Boolean = false,
        prompt: Boolean = true,
    ) = KnockReplyPolicy.resumeStep(reply, mode, visible, prompt)

    @Test fun aResumeKnockFollowsItsReplyButConnectsOnceThePersonIsLooking() {
        assertEquals(ResumeStep.CONNECT, step(KnockReply.CONNECT, mode = TvConnectMode.AUTO, prompt = false))
        assertEquals(ResumeStep.PROMPT, step(KnockReply.ASK))
        assertEquals(ResumeStep.CONNECT, step(KnockReply.ASK, visible = true))
        assertEquals(ResumeStep.CONNECT_UNPROMPTED, step(KnockReply.ASK, prompt = false))
        assertEquals(ResumeStep.NOTHING, step(KnockReply.DECLINED, visible = true))
    }

    @Test fun aConnectSentBecauseNothingCouldAskIsJournaledAsUnprompted() {
        // The common case: replyFor saw notifications off, so the TV was told CONNECT.
        assertEquals(ResumeStep.CONNECT_UNPROMPTED, step(KnockReply.CONNECT, prompt = false))
        assertTrue(KnockReplyPolicy.needsPromptCheck(KnockReply.CONNECT, TvConnectMode.ASK, visibleNow = false))
        // Not when the app is in front, automatic mode is on, or the prompt has come back.
        assertEquals(ResumeStep.CONNECT, step(KnockReply.CONNECT, visible = true, prompt = false))
        assertEquals(ResumeStep.CONNECT, step(KnockReply.CONNECT, mode = TvConnectMode.AUTO, prompt = false))
        assertEquals(ResumeStep.CONNECT, step(KnockReply.CONNECT, prompt = true))
        assertFalse(KnockReplyPolicy.needsPromptCheck(KnockReply.CONNECT, TvConnectMode.AUTO, visibleNow = false))
        assertFalse(KnockReplyPolicy.needsPromptCheck(KnockReply.CONNECT, TvConnectMode.ASK, visibleNow = true))
        assertFalse(KnockReplyPolicy.needsPromptCheck(KnockReply.DECLINED, TvConnectMode.ASK, visibleNow = false))
        assertTrue(KnockReplyPolicy.needsPromptCheck(KnockReply.ASK, TvConnectMode.ASK, visibleNow = false))
    }

    @Test fun aKnockDuringASilentResumeToThatTvIsToldConnect() {
        assertEquals(KnockReply.CONNECT, reply(resuming = true))
        assertEquals(KnockReply.DECLINED, reply(resuming = true, snoozed = true))
    }

    @Test fun aLateHintIsDialedOnceAtTheStoredPortAndOnlyIfNew() {
        val dialed = setOf("192.168.1.40" to 8009)
        assertEquals("192.168.1.41" to 8009, LateResumeHint.endpoint("192.168.1.41", 8009, dialed)?.address)
        assertEquals(null, LateResumeHint.endpoint("192.168.1.40", 8009, dialed))
        assertEquals(null, LateResumeHint.endpoint(null, 8009, dialed))
        assertEquals(null, LateResumeHint.endpoint("not-an-address", 8009, dialed))
        assertEquals(null, LateResumeHint.endpoint("192.168.1.41", 0, dialed))
        assertFalse(LateResumeHint.endpoint("192.168.1.41", 8009, dialed)!!.discovered)
    }

    @Test fun onlyOpenAndPromptResumesTakeALateHint() {
        assertTrue(LateResumeHint.accepts(SilentResumeKind.OPEN))
        assertTrue(LateResumeHint.accepts(SilentResumeKind.PROMPT))
        assertFalse(LateResumeHint.accepts(SilentResumeKind.KNOCK))
    }

    @Test fun theBodyTapConnectsOnceAndNeverOnARecreation() {
        assertEquals("tv-a", ConnectPromptTap.tvId("tv-a", restored = false))
        assertEquals(null, ConnectPromptTap.tvId("tv-a", restored = true))
        assertEquals(null, ConnectPromptTap.tvId(null, restored = false))
        assertEquals(null, ConnectPromptTap.tvId(" ", restored = false))
    }

    @Test fun notNowLastsTenMinutesPerTv() {
        val snooze = PromptSnooze()
        assertFalse(snooze.isSnoozed("tv-a", 0))
        snooze.snooze("tv-a", 1_000)
        assertTrue(snooze.isSnoozed("tv-a", 1_000))
        assertTrue(snooze.isSnoozed("tv-a", 1_000 + PromptSnooze.WINDOW_MS - 1))
        assertFalse(snooze.isSnoozed("tv-b", 2_000))
        assertFalse(snooze.isSnoozed("tv-a", 1_000 + PromptSnooze.WINDOW_MS))
        // Expired stays expired.
        assertFalse(snooze.isSnoozed("tv-a", 2_000))
        snooze.snooze("tv-b", 0)
        snooze.clear("tv-b")
        assertFalse(snooze.isSnoozed("tv-b", 1))
        assertEquals(600_000L, PromptSnooze.WINDOW_MS)
    }

    @Test fun theConnectModeRoundTripsAndDefaultsToAsk() {
        TvConnectMode.entries.forEach { assertEquals(it, TvConnectMode.fromStored(it.stored)) }
        assertEquals(TvConnectMode.ASK, TvConnectMode.fromStored(null))
        assertEquals(TvConnectMode.ASK, TvConnectMode.fromStored("sometimes"))
        assertEquals(TvConnectMode.ASK, TvConnectMode.DEFAULT)
    }

    @Test fun promptIdsAreStableAndClearOfTheFixedNotifications() {
        val ids = listOf("EREREREREREREREREREREQ", "IiIiIiIiIiIiIiIiIiIiIg", "", "x").map(ConnectPromptIds::notificationId)
        ids.forEach { assertTrue("$it", it >= ConnectPromptIds.BASE) }
        assertEquals(ids[0], ConnectPromptIds.notificationId("EREREREREREREREREREREQ"))
        assertNotEquals(ids[0], ids[1])
        val codes = listOf(ConnectPromptIds.ACTION_OPEN, ConnectPromptIds.ACTION_CONNECT, ConnectPromptIds.ACTION_LATER)
            .map { ConnectPromptIds.requestCode("EREREREREREREREREREREQ", it) }
        assertEquals(3, codes.toSet().size)
        codes.forEach { assertTrue("$it", it > 12) }
    }

    private fun open(
        enabled: Boolean = true,
        pairing: Boolean = true,
        media: Boolean = true,
        idle: Boolean = true,
        castLive: Boolean = false,
        starting: Boolean = false,
        dialing: Boolean = false,
        pairUi: Boolean = false,
        last: Long? = null,
        now: Long = 100_000,
    ) = OpenResumePolicy.shouldResume(enabled, pairing, media, idle, castLive, starting, dialing, pairUi, last, now)

    @Test fun openingTheAppResumesOnlyWhenNothingElseOwnsTheLine() {
        assertTrue(open())
        assertFalse(open(enabled = false))
        assertFalse(open(pairing = false))
        assertFalse(open(media = false))
        assertFalse(open(idle = false))
        assertFalse(open(castLive = true))
        assertFalse(open(starting = true))
        assertFalse(open(dialing = true))
        assertFalse(open(pairUi = true))
    }

    @Test fun openingTheAppDialsAtMostOncePerFifteenSeconds() {
        assertFalse(open(last = 90_000, now = 100_000))
        assertFalse(open(last = 100_000 - OpenResumePolicy.THROTTLE_MS + 1, now = 100_000))
        assertTrue(open(last = 100_000 - OpenResumePolicy.THROTTLE_MS, now = 100_000))
        assertEquals(15_000L, OpenResumePolicy.THROTTLE_MS)
    }
}
