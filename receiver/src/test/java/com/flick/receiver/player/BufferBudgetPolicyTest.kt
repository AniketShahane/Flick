package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024L * 1024L
private val GRANTS = listOf(96L, 128L, 256L, 512L)
private val BITRATES = listOf(40_000_000L, 60_000_000L, 80_000_000L, 100_000_000L)

class BufferBudgetPolicyTest {

    // ── The invariant the previous policy violated ───────────────────────────

    @Test fun everyBudgetIsSatisfiableAtThePlannedPeak() {
        // Retained back-buffer samples are allocated bytes charged to the same allocator
        // total, so the back buffer and the forward buffer spend ONE budget. A
        // configuration asking to hold more than the budget holds is incoherent, and the
        // numbers quoted for it are lies. The previous tuning asked for 45 s inside a
        // 21 s budget.
        for (mb in listOf(8L, 16L, 32L, 64L, 96L, 128L, 192L, 256L, 352L, 384L, 480L, 512L, 768L, 1024L, 2048L)) {
            val budget = bufferBudgetFor(mb * MB)
            assertTrue(
                "heap=${mb}MB back=${budget.backBufferMs} min=${budget.minBufferMs} fit=${budget.plannedPeakFitMs}",
                budget.backBufferMs + budget.minBufferMs <= budget.plannedPeakFitMs,
            )
        }
    }

    @Test fun theResidentSetTheConfigAsksForFitsTheByteTargetAtEveryTierAndBitrate() {
        for (mb in GRANTS) {
            val budget = bufferBudgetFor(mb * MB)
            for (bitrate in BITRATES) {
                val askedMs = (budget.backBufferMs + budget.minBufferMs).toLong()
                val residentBytes = askedMs * bitrate / 8_000L
                assertTrue(
                    "heap=${mb}MB rate=${bitrate / 1_000_000}Mbps resident=$residentBytes target=${budget.targetBufferBytes}",
                    residentBytes <= budget.targetBufferBytes,
                )
            }
        }
    }

    @Test fun theBackBufferCanNeverCrowdOutTheForwardBuffer() {
        // Back-buffer retention is passive and not gated by the byte target, so if its
        // bytes ever reach the target then targetBufferSizeReached is permanently true,
        // forward loading never resumes, and the player rebuffers forever with no error
        // raised. Up to twice the planned peak retention stays under half the budget.
        for (mb in GRANTS) {
            val budget = bufferBudgetFor(mb * MB)
            for (bitrate in BITRATES + listOf(200_000_000L)) {
                val backBytes = budget.backBufferMs.toLong() * bitrate / 8_000L
                assertTrue(
                    "heap=${mb}MB rate=${bitrate / 1_000_000}Mbps back=$backBytes target=${budget.targetBufferBytes}",
                    backBytes < budget.targetBufferBytes / 2,
                )
            }
            // And the rate at which retention alone would take the entire budget — the
            // stall-forever threshold — is past anything Wi-Fi 5 can carry and well past
            // UHD Blu-ray's 128 Mbps ceiling.
            assertTrue("heap=${mb}MB", budget.backBufferMs > 0)
            val saturationBps = budget.targetBufferBytes.toLong() * 8_000L / budget.backBufferMs
            assertTrue(
                "heap=${mb}MB saturates at ${saturationBps / 1_000_000}Mbps",
                saturationBps > 600_000_000L,
            )
        }
    }

    @Test fun noTierEverEnablesTheFlagThatUnboundsTheAllocation() {
        // With prioritizeTimeOverSizeThresholds set, shouldContinueLoading ignores the
        // byte target below minBufferMs entirely, so NO byte budget can bound the
        // min-buffer allocation. It is what made the OOM reachable; it is never on.
        for (mb in listOf(8L, 96L, 128L, 256L, 352L, 512L, 1024L, 4096L)) {
            assertFalse("heap=${mb}MB", bufferBudgetFor(mb * MB).prioritizeTimeOverSizeThresholds)
        }
    }

    // ── The numbers ─────────────────────────────────────────────────────────

    @Test fun theCeilingIsMedia3sOwnMaximumRatherThanTheOld256Mib() {
        // DEFAULT_MAX_BUFFER_SIZE = 210239488 is the largest total the library will ever
        // compute for itself; the receiver runs on weaker hardware than a phone.
        assertEquals(210_239_488, MAX_TARGET_BUFFER_BYTES)
        assertEquals(210_239_488, bufferBudgetFor(512 * MB).targetBufferBytes)
        assertEquals(210_239_488, bufferBudgetFor(4096 * MB).targetBufferBytes)
    }

    @Test fun theByteTargetIsTwoFifthsOfTheGrantUntilItReachesTheCeiling() {
        // Asserted with a tolerance rather than exactly: the share is applied in Float,
        // so the product lands on the nearest binary32 (a few bytes either way at 4e7).
        for (mb in GRANTS) {
            val expected = minOf(mb * MB * 2 / 5, MAX_TARGET_BUFFER_BYTES.toLong())
            assertEquals(
                "heap=${mb}MB",
                expected.toDouble(),
                bufferBudgetFor(mb * MB).targetBufferBytes.toDouble(),
                8.0,
            )
        }
        assertEquals(32 * MB, bufferBudgetFor(16 * MB).targetBufferBytes.toLong())
    }

    @Test fun theForwardBufferIsAllocatedFirstAndTheBackBufferTakesASmallShare() {
        val at512 = bufferBudgetFor(512 * MB)
        assertEquals(14_297, at512.minBufferMs)
        assertEquals(2_522, at512.backBufferMs)
        assertEquals(4_765, at512.bufferForPlaybackAfterRebufferMs)
        assertEquals(2_500, at512.bufferForPlaybackMs)

        val at256 = bufferBudgetFor(256 * MB)
        assertEquals(7_301, at256.minBufferMs)
        assertEquals(1_288, at256.backBufferMs)
        assertEquals(2_433, at256.bufferForPlaybackAfterRebufferMs)
        assertEquals(2_433, at256.bufferForPlaybackMs)

        val at128 = bufferBudgetFor(128 * MB)
        assertEquals(3_650, at128.minBufferMs)
        assertEquals(644, at128.backBufferMs)
        assertEquals(1_216, at128.bufferForPlaybackAfterRebufferMs)

        val at96 = bufferBudgetFor(96 * MB)
        assertEquals(2_738, at96.minBufferMs)
        assertEquals(483, at96.backBufferMs)
        assertEquals(912, at96.bufferForPlaybackAfterRebufferMs)
    }

    @Test fun theSustainableCushionClearsTheResumeThresholdThreefoldAcrossTheMatrix() {
        // A freeze avoided is not the same as playback that works. The steady-state
        // cushion equals minBufferMs at the planned peak and is SMALLER above it, so a
        // resume threshold merely equal to the min buffer leaves the player sitting at
        // the exact level it resumes from — continuous rebuffering — and above the peak
        // the cushion falls under the threshold and playback can never resume at all.
        for (mb in GRANTS) {
            val budget = bufferBudgetFor(mb * MB)
            for (bitrate in BITRATES) {
                val cushionMs = budget.protectionSecondsAt(bitrate) * 1000f
                val ratio = cushionMs / budget.bufferForPlaybackAfterRebufferMs
                assertTrue(
                    "heap=${mb}MB rate=${bitrate / 1_000_000}Mbps cushion=${cushionMs.toInt()}ms " +
                        "afterRebuffer=${budget.bufferForPlaybackAfterRebufferMs}ms ratio=$ratio",
                    ratio >= 3f,
                )
            }
        }
    }

    @Test fun theCushionOnlyFallsUnderTheResumeThresholdBeyondAnyReachableBitrate() {
        // The bitrate at which the sustainable cushion drops below the resume threshold
        // is where playback would wedge. It has to sit past UHD Blu-ray's 128 Mbps
        // ceiling and past what Wi-Fi 5 sustains for 4K.
        for (mb in GRANTS + listOf(8L, 64L, 1024L)) {
            val budget = bufferBudgetFor(mb * MB)
            val windowMs = (budget.bufferForPlaybackAfterRebufferMs + budget.backBufferMs).toLong()
            val wedgeBps = budget.targetBufferBytes.toLong() * 8_000L / windowMs
            assertTrue(
                "heap=${mb}MB wedges at ${wedgeBps / 1_000_000}Mbps",
                wedgeBps > 200_000_000L,
            )
        }
    }

    @Test fun playbackThresholdsAreAFractionOfTheMinBufferSoTheBuilderCannotThrow() {
        // DefaultLoadControl.Builder rejects minBufferMs below either playback threshold,
        // and maxBufferMs below minBufferMs. A violation is not a degraded buffer, it is
        // a crash at player construction.
        for (mb in listOf(8L, 16L, 32L, 64L, 96L, 128L, 192L, 256L, 384L, 512L, 1024L)) {
            val budget = bufferBudgetFor(mb * MB)
            val where = "heap=${mb}MB"
            assertTrue(where, budget.minBufferMs >= budget.bufferForPlaybackMs)
            assertTrue(where, budget.minBufferMs >= budget.bufferForPlaybackAfterRebufferMs)
            assertTrue(where, budget.maxBufferMs >= budget.minBufferMs)
            assertTrue(where, budget.bufferForPlaybackMs > 0)
            assertTrue(where, budget.bufferForPlaybackAfterRebufferMs > 0)
            assertTrue(where, budget.targetBufferBytes > 0)
            assertTrue(where, budget.backBufferMs >= 0)
        }
        // At the byte floor the thresholds are a third of a 2.28 s min buffer, not the
        // ordinary 2.5 s / 5 s: waiting 5 s to resume on a device that can hold 2.3 s
        // would mean never resuming.
        val tiny = bufferBudgetFor(8 * MB)
        assertEquals(2_282, tiny.minBufferMs)
        assertEquals(402, tiny.backBufferMs)
        assertEquals(760, tiny.bufferForPlaybackMs)
        assertEquals(760, tiny.bufferForPlaybackAfterRebufferMs)
    }

    @Test fun anAbsurdOrUnreadableHeapStillProducesABuildableBudget() {
        for (heap in listOf(0L, -1L, 8 * MB)) {
            val budget = bufferBudgetFor(heap)
            assertEquals(32 * MB, budget.targetBufferBytes.toLong())
            assertTrue(budget.minBufferMs >= budget.bufferForPlaybackAfterRebufferMs)
            assertTrue(budget.backBufferMs + budget.minBufferMs <= budget.plannedPeakFitMs)
        }
    }

    // ── Ride-out, which is the only figure that may be quoted ────────────────

    @Test fun rideOutIsTheForwardBufferAtTheFilesOwnBitrateAndNever180Seconds() {
        val verified = bufferBudgetFor(512 * MB)
        assertEquals(39.5f, verified.protectionSecondsAt(40_000_000L), 0.3f)
        assertEquals(25.5f, verified.protectionSecondsAt(60_000_000L), 0.3f)
        assertEquals(18.5f, verified.protectionSecondsAt(80_000_000L), 0.3f)
        assertEquals(14.3f, verified.protectionSecondsAt(100_000_000L), 0.3f)
        // The 180 s time cap binds only on content light enough that the byte budget is
        // not reached first — below ~9.3 Mbps, never 4K.
        assertEquals(180f, verified.protectionSecondsAt(5_000_000L), 0.1f)
        assertEquals(0f, verified.protectionSecondsAt(0L), 0f)
    }

    @Test fun theNewBudgetRidesOutLongerThanTheOldFixedTuningDidAtEveryBitrate() {
        // The old config guaranteed 15 s of forward buffer and nothing more for any rate
        // at or above ~47.7 Mbps, because the 30 s back buffer had already taken the byte
        // target. Cutting the back buffer buys those forward seconds back.
        val verified = bufferBudgetFor(512 * MB)
        for (bitrate in BITRATES) {
            assertTrue(
                "rate=${bitrate / 1_000_000}Mbps got=${verified.protectionSecondsAt(bitrate)}",
                verified.protectionSecondsAt(bitrate) >= 14f,
            )
        }
        assertTrue(verified.protectionSecondsAt(60_000_000L) > 20f)
    }

    @Test fun rideOutDegradesWithTheGrantRatherThanFailing() {
        val seconds = GRANTS.map { bufferBudgetFor(it * MB).protectionSecondsAt(PLANNED_PEAK_BITRATE_BPS) }
        // Monotonic in the grant, and never zero: fewer seconds is a stall, which is
        // strictly better than the OutOfMemoryError a fixed budget caused here.
        for (i in 1 until seconds.size) {
            assertTrue(seconds.toString(), seconds[i] > seconds[i - 1])
        }
        assertTrue(seconds.toString(), seconds.all { it > 2f })
    }
}
