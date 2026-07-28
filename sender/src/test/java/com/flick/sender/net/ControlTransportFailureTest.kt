package com.flick.sender.net

import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException
import java.util.ConcurrentModificationException

class ControlTransportFailureTest {

    /** Exactly what Ktor's ping/pong watchdog raises (DefaultWebSocketSession.kt). */
    private fun pingTimeout() = IOException("Ping timeout")

    @Test fun theCrashingPingTimeoutIsAbsorbedAsATransportFailure() {
        assertEquals(ControlFailure.TRANSPORT, ControlTransportFailure.classify(pingTimeout()))
    }

    @Test fun everySocketLevelFailureShapeIsTransport() {
        val transport = listOf(
            pingTimeout(),
            IOException("broken pipe"),
            SocketException("Software caused connection abort"),
            SocketTimeoutException("read timed out"),
            ConnectException("ECONNREFUSED"),
            UnknownHostException("192.0.2.10"),
            EOFException(),
            ClosedChannelException(),
            ClosedReceiveChannelException("Channel was closed"),
            ClosedSendChannelException("Channel was closed"),
            FrameTooBigException(65_536L),
            ProtocolViolationException("unexpected opcode"),
        )
        transport.forEach {
            assertEquals(it.javaClass.name, ControlFailure.TRANSPORT, ControlTransportFailure.classify(it))
        }
    }

    @Test fun programmingDefectsAreNeverAbsorbed() {
        val bugs = listOf(
            NullPointerException(),
            IllegalStateException("session must be set"),
            IllegalArgumentException("port"),
            ClassCastException("JSONObject"),
            IndexOutOfBoundsException("3"),
            ConcurrentModificationException(),
            // ClosedReceiveChannelException is a NoSuchElementException and
            // ClosedSendChannelException is an IllegalStateException, so matching
            // either supertype would swallow these two ordinary defects.
            NoSuchElementException("no such frame"),
            RuntimeException("boom"),
        )
        bugs.forEach {
            assertEquals(it.javaClass.name, ControlFailure.BUG, ControlTransportFailure.classify(it))
        }
    }

    @Test fun errorsAreNeverAbsorbed() {
        assertEquals(ControlFailure.BUG, ControlTransportFailure.classify(OutOfMemoryError()))
        assertEquals(ControlFailure.BUG, ControlTransportFailure.classify(StackOverflowError()))
        assertEquals(ControlFailure.BUG, ControlTransportFailure.classify(AssertionError("invariant")))
        assertEquals(ControlFailure.BUG, ControlTransportFailure.classify(NoClassDefFoundError("io.ktor.X")))
    }

    @Test fun cancellationIsItsOwnDispositionSoStructuredCancellationStillWorks() {
        assertEquals(ControlFailure.CANCELLED, ControlTransportFailure.classify(CancellationException("closed")))
        // A subclass must classify the same way: withTimeoutOrNull ends the dial with
        // TimeoutCancellationException, and reader?.cancel() with a job cancellation.
        class NestedCancellation : CancellationException("nested")
        assertEquals(ControlFailure.CANCELLED, ControlTransportFailure.classify(NestedCancellation()))
    }

    @Test fun onlyTheThrowableItselfIsInspectedNeverItsCause() {
        // A socket failure whose cause is a defect is still the socket reporting death.
        assertEquals(
            ControlFailure.TRANSPORT,
            ControlTransportFailure.classify(IOException("write failed", NullPointerException())),
        )
        // The reverse must NOT hold: a defect that merely wrapped a socket failure has
        // to stay fatal, which is why the cause chain is never walked.
        assertEquals(
            ControlFailure.BUG,
            ControlTransportFailure.classify(IllegalStateException("unreachable", pingTimeout())),
        )
        assertEquals(
            ControlFailure.BUG,
            ControlTransportFailure.classify(RuntimeException("wrapped", ClosedChannelException())),
        )
    }

    @Test fun classifyIsTotalAndRepeatable() {
        val samples = listOf<Throwable>(pingTimeout(), CancellationException(), RuntimeException(), OutOfMemoryError())
        val expected = listOf(ControlFailure.TRANSPORT, ControlFailure.CANCELLED, ControlFailure.BUG, ControlFailure.BUG)

        assertEquals(expected, samples.map(ControlTransportFailure::classify))
        assertEquals(expected, samples.map(ControlTransportFailure::classify))
    }

    /**
     * The containment contract the fix rests on: under a SupervisorJob the failing
     * `launch` is itself the root that consults a CoroutineExceptionHandler, so a
     * handler passed at that `launch` runs and the scope stays usable. The scope here
     * is shaped exactly like FlickApplication.applicationScope.
     */
    @Test fun aHandlerOnTheFailingLaunchContainsTheFailureAndLeavesTheScopeUsable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val absorbed = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error ->
            when (ControlTransportFailure.classify(error)) {
                ControlFailure.TRANSPORT -> absorbed += error
                else -> throw error
            }
        }

        runBlocking { scope.launch(handler) { throw pingTimeout() }.join() }

        assertEquals(1, absorbed.size)
        assertTrue(absorbed.single() is IOException)
        assertEquals("Ping timeout", absorbed.single().message)
        assertTrue(scope.isActive)

        // "Casting again afterwards must work": a sibling launched after the failure
        // still runs, which is what the process death used to cost.
        var reconnected = false
        runBlocking { scope.launch { reconnected = true }.join() }
        assertTrue(reconnected)
        scope.cancel()
    }

    @Test fun aHandlerOnAScopeThatDidNotLaunchTheCoroutineIsNeverConsulted() {
        val wrongPlace = mutableListOf<Throwable>()
        val unrelated = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, error -> wrongPlace += error },
        )
        val absorbed = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        runBlocking {
            scope.launch(CoroutineExceptionHandler { _, error -> absorbed += error }) { throw pingTimeout() }.join()
        }

        assertEquals(1, absorbed.size)
        assertTrue(wrongPlace.isEmpty())
        unrelated.cancel()
        scope.cancel()
    }

    /**
     * The reader's own shape, driven the way Ktor's watchdog drives it. Unconfined so
     * every resumption lands on the test thread; the scope is otherwise the shape of
     * FlickApplication.applicationScope, and the handler stands in for the one
     * ControlClient passes at the same `launch`.
     */
    private class Reader(private val incoming: Channel<Frame>) {
        val delivered = mutableListOf<Frame>()
        val lost = mutableListOf<Throwable>()
        val uncaught = mutableListOf<Throwable>()
        var teardowns = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val job = scope.launch(CoroutineExceptionHandler { _, error -> uncaught += error }) {
            absorbingTransportFailure(
                onTransportLoss = { lost += it },
                always = { teardowns++ },
            ) {
                for (frame in incoming) delivered += frame
            }
        }
    }

    @Test fun aPingTimeoutClosingIncomingIsAbsorbedAndStillRunsTheTeardown() {
        val incoming = Channel<Frame>()
        val reader = Reader(incoming)

        runBlocking {
            incoming.send(Frame.Text("{\"t\":\"progress\"}"))
            // Exactly what the watchdog does: it does not cancel the reader and does
            // not merely close the channel, it closes it WITH the failure as cause.
            incoming.close(pingTimeout())
            reader.job.join()
        }

        assertEquals(1, reader.delivered.size)
        assertEquals(1, reader.lost.size)
        assertTrue(reader.lost.single() is IOException)
        assertEquals("Ping timeout", reader.lost.single().message)
        assertEquals(1, reader.teardowns)
        // The property the whole change exists for: nothing reached a handler that
        // would hand it to the thread's default one, and the process kept running.
        assertTrue(reader.uncaught.isEmpty())
        assertTrue(reader.job.isCompleted)
        assertFalse(reader.job.isCancelled)
        assertTrue(reader.scope.isActive)
        reader.scope.cancel()
    }

    @Test fun aDefectInsideTheReadLoopStillReachesTheHandlerAndStillTearsDown() {
        val defect = IllegalStateException("session must be set")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lost = mutableListOf<Throwable>()
        val uncaught = mutableListOf<Throwable>()
        var teardowns = 0

        val job = scope.launch(CoroutineExceptionHandler { _, error -> uncaught += error }) {
            absorbingTransportFailure(onTransportLoss = { lost += it }, always = { teardowns++ }) { throw defect }
        }
        runBlocking { job.join() }

        assertTrue(lost.isEmpty())
        assertEquals(1, teardowns)
        assertEquals(1, uncaught.size)
        assertTrue(uncaught.single() is IllegalStateException)
        assertEquals("session must be set", uncaught.single().message)
        scope.cancel()
    }

    @Test fun anOrdinaryCloseEndsTheLoopWithoutReportingATransportLoss() {
        val incoming = Channel<Frame>()
        val reader = Reader(incoming)

        runBlocking { incoming.close(); reader.job.join() }

        assertTrue(reader.lost.isEmpty())
        assertEquals(1, reader.teardowns)
        assertTrue(reader.uncaught.isEmpty())
        assertFalse(reader.job.isCancelled)
        reader.scope.cancel()
    }

    @Test fun cancellingTheReaderStaysACancellationAndIsNeverAbsorbed() {
        val incoming = Channel<Frame>()
        val reader = Reader(incoming)

        // closeInternal() cancels this job on every reconnect; absorbing that would
        // leave a stale reader alive on a socket its successor has replaced.
        reader.job.cancel()
        runBlocking { reader.job.join() }

        assertTrue(reader.lost.isEmpty())
        assertEquals(1, reader.teardowns)
        assertTrue(reader.uncaught.isEmpty())
        assertTrue(reader.job.isCancelled)
        reader.scope.cancel()
    }
}
