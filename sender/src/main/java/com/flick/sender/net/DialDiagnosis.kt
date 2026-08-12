package com.flick.sender.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * What a failed control dial proves.
 *
 * Five kernel-level answers with five different fixes, flattened today into one "Can't
 * reach %1$s" and one "Couldn't reach that TV." The evidence was already in hand —
 * `ControlClient.open` logs the throwable's class name and then discards it.
 *
 * Deliberately NOT `ControlTransportFailure.classify`: that function decides absorb vs
 * rethrow for a live socket, and its narrow contract — inspect the handed throwable only,
 * never walk the cause chain, allow-list rather than deny-list — is what keeps a real
 * defect fatal. It must not be weakened to carry a UI taxonomy.
 */
enum class DialFault {
    /** RST from the target: its IP stack is alive and nothing is listening on that port. */
    REFUSED,

    /** EHOSTUNREACH: the router, or this phone's own stack, refused to forward. */
    NO_ROUTE,

    /** The SYN went out and nothing came back — no RST, no ICMP, no answer. */
    NO_ANSWER,

    /** ENETUNREACH / EACCES: this phone has no path to put a packet on. */
    NO_NETWORK,

    /** The upgrade completed and the receiver then closed. An ACTIVE rejection. */
    REJECTED,
}

/**
 * The arm order is load-bearing and not stylistic. Two inheritance facts drive it, and
 * getting either wrong turns a diagnosis into a confident lie:
 *
 * Ktor's [ConnectTimeoutException] EXTENDS [ConnectException], so it has to be tested
 * first or a dial that got no answer at all would be reported as a TV that answered and
 * refused — the one outcome that proves the TV is awake.
 *
 * [NoRouteToHostException] and [ConnectException] both extend [SocketException], so both
 * have to precede it or a router keeping two devices apart would be reported as a phone
 * with no network, and the user would be sent to rejoin a Wi-Fi they are already on.
 *
 * A null [error] is the enclosing `withTimeoutOrNull` having returned — the dial neither
 * completed nor threw, which is silence and nothing more.
 */
internal fun dialDiagnosis(error: Throwable?, upgraded: Boolean): DialFault = when {
    upgraded -> DialFault.REJECTED
    error is ConnectTimeoutException -> DialFault.NO_ANSWER
    error is NoRouteToHostException -> DialFault.NO_ROUTE
    error is ConnectException -> DialFault.REFUSED
    error is SocketTimeoutException -> DialFault.NO_ANSWER
    error is SocketException -> DialFault.NO_NETWORK
    else -> DialFault.NO_ANSWER
}
