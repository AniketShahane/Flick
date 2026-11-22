package com.flick.sender.library

import com.flick.sender.net.PairLaunch
import com.flick.sender.net.ResumeCandidates
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

/** What `knockOk` tells the TV (contract §13.5), decided before the knock is acted on. */
internal enum class KnockReply {
    /** The phone is connecting now. */
    CONNECT,

    /** The knock was verified and the phone is asking its owner first. */
    ASK,

    /** The owner said "Not now" recently: the phone will not connect. */
    DECLINED,
}

/** "When your TV opens Flick". */
enum class TvConnectMode(internal val stored: String) {
    ASK("ask"),
    AUTO("auto");

    companion object {
        val DEFAULT = ASK

        /** Anything unknown, including nothing stored yet, reads as the default. */
        fun fromStored(value: String?): TvConnectMode = entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

/** Why a phone-side resume without a tap on the Connect screen started. */
internal enum class SilentResumeKind(val journalEvent: String, val logName: String, val personActed: Boolean) {
    /** A TV's verified knock. */
    KNOCK("knock_resume", "knock resume", personActed = false),

    /** The app came to the foreground. */
    OPEN("open_resume", "open resume", personActed = true),

    /** "Connect" on the prompt. */
    PROMPT("prompt_resume", "prompt resume", personActed = true),
}

internal object KnockReplyPolicy {
    /**
     * Runs on the doorbell's listener thread before `knockOk` is written, so every input must
     * be readable from there. [notificationsAvailable] false means nothing could ask the owner.
     */
    fun reply(
        snoozed: Boolean,
        mode: TvConnectMode,
        visible: Boolean,
        notificationsAvailable: Boolean,
        resumingThatTv: Boolean = false,
    ): KnockReply = when {
        // A silent resume to that TV is dialing already: nothing would ask, and it answers this knock.
        resumingThatTv -> KnockReply.CONNECT
        snoozed -> KnockReply.DECLINED
        mode == TvConnectMode.AUTO -> KnockReply.CONNECT
        visible -> KnockReply.CONNECT
        !notificationsAvailable -> KnockReply.CONNECT
        else -> KnockReply.ASK
    }

    /**
     * What a knock whose action is [KnockAction.RESUME] does on main, given the reply the TV was
     * sent. A CONNECT sent in ASK mode to a hidden app was sent because nothing could ask, so it
     * is journaled as such too.
     */
    fun resumeStep(reply: KnockReply, mode: TvConnectMode, visibleNow: Boolean, promptAvailable: Boolean): ResumeStep = when {
        reply == KnockReply.DECLINED -> ResumeStep.NOTHING
        visibleNow -> ResumeStep.CONNECT
        reply == KnockReply.CONNECT ->
            if (mode == TvConnectMode.ASK && !promptAvailable) ResumeStep.CONNECT_UNPROMPTED else ResumeStep.CONNECT
        !promptAvailable -> ResumeStep.CONNECT_UNPROMPTED
        else -> ResumeStep.PROMPT
    }

    /** Whether [resumeStep] reads `promptAvailable`, so main asks the notification manager only then. */
    fun needsPromptCheck(reply: KnockReply, mode: TvConnectMode, visibleNow: Boolean): Boolean =
        !visibleNow && (reply == KnockReply.ASK || (reply == KnockReply.CONNECT && mode == TvConnectMode.ASK))

    enum class ResumeStep {
        NOTHING,
        CONNECT,

        /** Asked, but the prompt cannot be shown: connect, and say so in the journal. */
        CONNECT_UNPROMPTED,
        PROMPT,
    }
}

/**
 * "Not now" per TV, in memory: process death forgets it, which only means the next knock asks
 * again. Read from the doorbell's listener thread and written on main.
 */
internal class PromptSnooze(private val windowMs: Long = WINDOW_MS) {
    private val until = HashMap<String, Long>()

    @Synchronized
    fun snooze(tvId: String, nowMs: Long) {
        until[tvId] = nowMs + windowMs
    }

    @Synchronized
    fun isSnoozed(tvId: String, nowMs: Long): Boolean {
        val end = until[tvId] ?: return false
        if (nowMs < end) return true
        until.remove(tvId)
        return false
    }

    @Synchronized
    fun clear(tvId: String) {
        until.remove(tvId)
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** The prompt's notification and PendingIntent identities, apart from Android so they are testable. */
internal object ConnectPromptIds {
    /** Above every fixed notification id the app uses (the cast's 42, the ready window's 43). */
    const val BASE = 1_000
    private const val SPAN = 1 shl 20

    /** Stable for a tvId across processes: [String.hashCode] is specified, unlike identity hashes. */
    fun notificationId(tvId: String): Int = BASE + Math.floorMod(tvId.hashCode(), SPAN)

    /** Distinct per (tvId, action), and clear of the ready notification's request codes. */
    fun requestCode(tvId: String, action: Int): Int = notificationId(tvId) * 4 + action

    const val ACTION_OPEN = 0
    const val ACTION_CONNECT = 1
    const val ACTION_LATER = 2
}

/**
 * A knock's address that reached an OPEN or PROMPT resume already dialing that TV (§13.5).
 * The resume tries it once, after its own candidates, at the stored port.
 */
internal object LateResumeHint {
    fun accepts(kind: SilentResumeKind): Boolean = kind == SilentResumeKind.OPEN || kind == SilentResumeKind.PROMPT

    /** The endpoint to dial for [hint], or null when there is none or it was dialed already. */
    fun endpoint(hint: String?, storedPort: Int, dialed: Set<Pair<String, Int>>): ResumeCandidates.Endpoint? {
        if (hint == null || !PairLaunch.isCanonicalIpv4(hint) || storedPort !in 1..65535) return null
        return ResumeCandidates.Endpoint(hint, storedPort, discovered = false).takeIf { it.address !in dialed }
    }
}

/** The prompt's body tap, as MainActivity receives it. */
internal object ConnectPromptTap {
    private const val TOKEN_BYTES = 32

    /**
     * The tvId to connect, or null. [restored] is a recreation from saved state, which
     * re-delivers the launching Intent and must not connect a second time. MainActivity is
     * exported, so the extra alone proves nothing: only an Intent carrying this install's
     * [expectedToken], which only the prompt's own PendingIntent holds, is acted on.
     */
    fun tvId(extra: String?, restored: Boolean, presentedToken: String?, expectedToken: String?): String? =
        extra?.takeIf { !restored && it.isNotBlank() && tokenMatches(presentedToken, expectedToken) }

    /** The per-install token: 32 random bytes, base64url without padding. */
    fun newToken(random: Random = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Constant-time, so a caller probing the exported activity learns nothing from timing. */
    fun tokenMatches(presented: String?, expected: String?): Boolean {
        if (presented == null || expected.isNullOrEmpty()) return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}

/** What "Connect" on the prompt does with whatever dial is already running (§13.5). */
internal object PromptConnectPolicy {
    enum class Step {
        /** A silent resume to the tapped TV is dialing: it takes the prompt's hint. */
        JOIN,

        /** Connect-on-open is dialing a different TV: the tap replaces it. */
        SUPERSEDE,

        /** Something else owns the line. */
        BUSY,
        START,
    }

    /**
     * [resumingTv] and [resumingKind] describe the silent resume still dialing, if any.
     * [dialing] is anything owning control v2, that resume included; [otherDialing] is a
     * dial, pairing, block wait or pair sheet besides it.
     */
    fun step(
        tvId: String,
        resumingTv: String?,
        resumingKind: SilentResumeKind?,
        dialing: Boolean,
        otherDialing: Boolean,
    ): Step = when {
        resumingTv == tvId -> Step.JOIN
        !dialing -> Step.START
        resumingTv != null && resumingKind == SilentResumeKind.OPEN && !otherDialing -> Step.SUPERSEDE
        else -> Step.BUSY
    }
}

internal object OpenResumePolicy {
    const val THROTTLE_MS = 15_000L

    /**
     * Whether coming to the foreground starts the silent resume. [lastAttemptAtMs] is when the
     * last one started, or null; the clock must keep counting through deep sleep.
     */
    fun shouldResume(
        libraryEnabled: Boolean,
        readyPairing: Boolean,
        mediaAccess: Boolean,
        controlIdle: Boolean,
        castLive: Boolean,
        castStarting: Boolean,
        dialInFlight: Boolean,
        pairUiOpen: Boolean,
        lastAttemptAtMs: Long?,
        nowMs: Long,
    ): Boolean = libraryEnabled && readyPairing && mediaAccess && controlIdle &&
        !castLive && !castStarting && !dialInFlight && !pairUiOpen &&
        (lastAttemptAtMs == null || nowMs - lastAttemptAtMs >= THROTTLE_MS)
}
