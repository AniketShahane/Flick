package com.flick.sender

/**
 * The phone-side terminal codes the media server may raise about itself.
 *
 * None of these ever crosses the control link. `ControlFrameSchema.failureCodes` is an
 * INBOUND allow-list, so adding to it would only widen what an un-updated receiver is
 * permitted to say — it would buy this file nothing. These are consumed by
 * `castErrorFace` and by nothing else.
 */
object SourceFault {
    const val BIND_FAILED = "media_bind_failed"
    const val NO_LAN_ADDRESS = "no_lan_address"
    const val SOURCE_LOST = "source_lost"

    /**
     * The platform refused to start the service at all — an API 31+ background start.
     *
     * Deliberately not [BIND_FAILED]: nothing was bound, so that face's "another app may
     * be holding the port" would be a cause invented for a refusal that named itself.
     */
    const val START_REFUSED = "media_start_refused"

    /**
     * A throwable that escaped `streamSlice` after the range had already been accepted.
     *
     * Every one of them means the same thing to the viewer — the file stopped being
     * readable while the TV was playing it — so the throwable is deliberately not
     * inspected. A revoked grant, a deleted row and a provider that died are one fact
     * from the far end of the LAN, and the log line beside the call carries the class
     * name for anyone reading the diagnostics.
     */
    fun midStream(error: Throwable): String = SOURCE_LOST
}

/**
 * Receiver verdicts that were reached by watching the body THIS phone was writing, and
 * are therefore its honest guess rather than evidence.
 *
 * A truncated response reads identically to a phone that stopped serving, so the
 * receiver names the only thing it can see. `streamSlice` is the one place in the system
 * that knows why the bytes stopped, and where it recorded a reason that reason wins.
 * Nothing else does: every other receiver code was reached with the file in front of it.
 */
private val ReceiverSourceGuesses = setOf("sender_not_serving", "media_unreachable", "http_rejected")

/** The code a terminal should carry, given what this phone recorded about the same cast. */
fun preferredTerminalCode(reported: String, recordedSourceFault: String?): String =
    if (recordedSourceFault != null && reported in ReceiverSourceGuesses) recordedSourceFault else reported
