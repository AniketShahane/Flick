package com.flick.sender.net

import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.io.IOException

/** What a control-socket coroutine must do with a throwable that escaped its body. */
internal enum class ControlFailure {
    /** Structured cancellation. Re-raise, or `reader?.cancel()` stops meaning anything. */
    CANCELLED,

    /** The socket died. Absorb it into the same DISCONNECTED transition a close produces. */
    TRANSPORT,

    /** A defect of ours. Re-raise unchanged so it still crashes and still gets fixed. */
    BUG,
}

/**
 * The single decision behind absorbing a websocket failure instead of letting it end
 * the process, held apart from [ControlClient] so it can be exhaustively tested.
 *
 * It is an allow-list of the throwables the control socket's own coroutines can
 * actually observe, and it inspects only the throwable it is handed: never its cause
 * chain. An `IOException` whose cause is a `NullPointerException` is still the socket
 * reporting that it died, but a `RuntimeException` that merely *wraps* an
 * `IOException` is our own bug touching the socket, and walking the chain would hide
 * exactly that.
 */
internal object ControlTransportFailure {
    fun classify(error: Throwable): ControlFailure = when (error) {
        is CancellationException -> ControlFailure.CANCELLED
        // Ktor's ping/pong watchdog reports a peer that stopped answering as a plain
        // IOException("Ping timeout") and closes `incoming` with it as the channel's
        // cause; every CIO socket error reaches us as an IOException subtype too.
        is IOException -> ControlFailure.TRANSPORT
        // Matched as exact types rather than through their supertypes on purpose:
        // ClosedSendChannelException IS an IllegalStateException and
        // ClosedReceiveChannelException IS a NoSuchElementException, so a supertype
        // rule here would silently absorb every failed check() and every empty-lookup
        // bug in this package.
        is ClosedReceiveChannelException -> ControlFailure.TRANSPORT
        is ClosedSendChannelException -> ControlFailure.TRANSPORT
        // Raised by Ktor's frame reader against what the peer sent, never by us.
        is FrameTooBigException -> ControlFailure.TRANSPORT
        is ProtocolViolationException -> ControlFailure.TRANSPORT
        // Every Error lands here as well: an OutOfMemoryError absorbed as a transport
        // event would leave the app running on a broken heap behind a UI that claims
        // nothing worse happened than a TV going away.
        else -> ControlFailure.BUG
    }
}

/**
 * Runs [body] and disposes of whatever escapes it by [ControlTransportFailure.classify]:
 * a transport failure goes to [onTransportLoss] and is absorbed, anything else is
 * re-raised so the coroutine still fails and still reaches its handler. [always] runs on
 * every path, so the teardown never depends on which one was taken.
 *
 * The disposition lives here rather than inline in the reader because it — not the
 * predicate alone — is what a ping timeout got wrong: `incoming` is closed WITH the
 * failure as the channel's cause, so the loop does not end, it throws. Only a named
 * unit can be driven from a JVM test with a channel closed exactly that way, and it
 * takes its logging and teardown as parameters so that test never touches Android.
 */
internal suspend fun absorbingTransportFailure(
    onTransportLoss: (Throwable) -> Unit,
    always: () -> Unit,
    body: suspend () -> Unit,
) {
    try {
        body()
    } catch (error: Throwable) {
        if (ControlTransportFailure.classify(error) != ControlFailure.TRANSPORT) throw error
        onTransportLoss(error)
    } finally {
        always()
    }
}
