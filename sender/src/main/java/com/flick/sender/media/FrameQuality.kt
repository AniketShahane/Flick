package com.flick.sender.media

import kotlin.math.abs
import kotlin.math.min

/**
 * Which frame of a film is worth showing, where to look for it, and what to remember once
 * one has been chosen.
 *
 * Films open on black — a fade up, a distributor logo, leader — so the frame a fixed
 * offset or a provider's own cached thumbnail lands on is very often a picture of
 * nothing, which is why a gallery of them reads as a wall of black tiles.
 *
 * Nothing here touches a `Bitmap`, a decoder or a `Uri`: the sampling belongs to the
 * caller, and everything below is arithmetic over the samples it took or plain data about
 * what that arithmetic decided. That is what makes the rule the tiles are chosen by
 * testable on a plain JVM, which is the only kind of test this module has.
 */

/**
 * Rec. 601 luma of one ARGB pixel, 0..255. Integer weights summing to 256 over a shift
 * rather than the float form: this runs over every sample of every candidate of every
 * tile in the grid, and the eye cannot see the last bit of a brightness estimate.
 */
internal fun lumaOf(pixel: Int): Int {
    val red = (pixel ushr 16) and 0xFF
    val green = (pixel ushr 8) and 0xFF
    val blue = pixel and 0xFF
    return (RedWeight * red + GreenWeight * green + BlueWeight * blue) shr 8
}

/**
 * What one candidate frame is worth, from its luma samples alone: how bright it is on
 * average and how far its pixels stray from that average.
 */
internal data class FrameStats(val meanLuma: Int, val spread: Int) {

    /**
     * A picture of nothing — a fade, leader, a blown-out flash, a solid slate.
     *
     * Both halves are needed and neither alone would do: a night exterior is dark AND
     * full of detail, a grey title card is bright AND perfectly flat. The two floors are
     * deliberately far below anything a real scene reads at, because the cost of a false
     * "blank" is a handful of extra decodes and the cost of a false "fine" is the black
     * tile this exists to remove.
     */
    val blank: Boolean get() = meanLuma < MinMeanLuma || spread < MinSpread

    /**
     * Which of two frames to prefer when the whole search came back blank — a film that
     * really is dark throughout still has to put something on its tile.
     *
     * Contrast dominates because that is what makes a still read as a scene at thumbnail
     * size. Brightness earns credit only up to a midtone, so a blown-out frame can never
     * outscore a lit one merely by being brighter than it.
     */
    val score: Int get() = spread * SpreadWeight + min(meanLuma, MidtoneLuma)
}

/**
 * Mean luma and the mean absolute deviation from it. Absolute deviation rather than a
 * standard deviation for the same reason the luma weights are integers — it answers the
 * same question here, in one pass, without a square root.
 *
 * No samples at all judges as blank; the caller that could not read a bitmap's pixels is
 * expected not to ask, precisely so that "unreadable" never reads as "empty".
 */
internal fun frameStats(luma: IntArray): FrameStats {
    if (luma.isEmpty()) return FrameStats(meanLuma = 0, spread = 0)
    var total = 0L
    for (value in luma) total += value
    val mean = (total / luma.size).toInt()
    var deviation = 0L
    for (value in luma) deviation += abs(value - mean)
    return FrameStats(meanLuma = mean, spread = (deviation / luma.size).toInt())
}

/**
 * Where a grid of [count] samples falls along one axis of a frame [extent] pixels long.
 *
 * The samples are spread across that axis's INTERIOR, with an eighth of the extent left
 * unread at each end. Films arrive with their bars baked into the picture, and a grid laid
 * over the whole frame reads those bars as content: for 2.39:1 in a 16:9 frame a third of
 * a full-extent grid's rows land in black, and a flat slate plus that black measures as a
 * spread the picture itself does not have — which is exactly the reading the uniformity
 * floor exists to catch.
 *
 * An eighth because it is the depth of the two paddings a phone's library actually holds:
 * 2.39:1 scope letterboxed into 16:9 costs 12.8% of the height, and 4:3 pillarboxed into
 * 16:9 costs 12.5% of the width. It does not reach a portrait film padded into a landscape
 * frame, whose bars are a third of the width deep and beyond any fixed inset. A fade to
 * black is unaffected either way: its interior is as black as its edges, so the brightness
 * floor still catches it.
 */
internal fun sampleAxis(extent: Int, count: Int): IntArray {
    val inset = extent / InteriorInsetDivisor
    val interior = extent - 2 * inset
    return IntArray(count) { index -> inset + (2 * index + 1) * interior / (2 * count) }
}

/**
 * Where to look for a still, in the order to look.
 *
 * A third in comes first because it is where this app has always looked and where a film
 * is usually past its titles; the rest walk outward through the body of the film. The
 * list is the whole budget of the search — four positions, no more — because the phone
 * running it may be serving a multi-gigabyte 4K file over its own Wi-Fi at the same time,
 * and a thumbnail is never worth contending with that.
 *
 * The tail is not sampled at all: credits are as black as leader, and a film's last
 * minutes are the one part of it nobody wants to see on a tile.
 *
 * A duration of zero is MediaStore's silence, not a zero-length film. With no length to
 * divide there is nowhere to spread the search, so it asks for one early frame — the same
 * position the fixed-offset path falls back to.
 */
internal fun thumbnailCandidatesMs(durationMs: Long): List<Long> {
    if (durationMs <= 0L) return listOf(UnknownDurationCandidateMs)
    val last = durationMs - 1L
    // Permille rather than a float fraction: a Float stops counting whole milliseconds a
    // little under five hours in, and this arithmetic should not have a ceiling the films
    // it schedules do not.
    return CandidatePermille
        .map { permille -> (durationMs * permille / 1_000L).coerceIn(0L, last) }
        .distinct()
}

/**
 * What was decided about one file's still, and on what terms.
 *
 * The two shapes answer a later sighting differently. The provider's own cached thumbnail
 * is re-read and re-judged every time, because the provider regenerates it when the file
 * underneath is rewritten; a position out of the search is decoded again, and re-judged
 * only when it was recorded on a frame that passed.
 */
internal sealed interface StillVerdict {

    /** The provider's cached thumbnail was a picture of something. There is no position. */
    object ProviderThumbnail : StillVerdict

    /**
     * The search settled at [positionMs]. [passed] separates the two ways it can settle:
     * on a frame that cleared the judgement, or — when nothing in the schedule did — on
     * the best of a bad set.
     */
    data class Searched(val positionMs: Long, val passed: Boolean) : StillVerdict {

        /**
         * Whether this position has stopped naming the frame it was recorded for: the file
         * was re-encoded under the memo, and the second that held a scene now holds black
         * or credits.
         *
         * Only a verdict recorded on a frame that PASSED can go stale. One recorded as the
         * best of a search where nothing passed remembers a blank frame on purpose — a
         * film that is dark throughout still has to put something on its tile — and
         * re-deciding it would spend the whole four-decode search on every cache miss for
         * the rest of the process.
         *
         * Null [stats] is "could not look", which overrules nothing.
         */
        fun stale(stats: FrameStats?): Boolean = passed && stats != null && stats.blank
    }
}

/**
 * The bounded record of what was decided, per file.
 *
 * Evicted oldest-first, and only when a genuinely new file arrives at the bound: a library
 * holds thousands of files, and the cost of dropping a verdict is one search the next time
 * that file is looked at — a cost there is no reason to pay on behalf of a key that is
 * already here and merely being rewritten.
 */
internal class StillMemory(private val limit: Int = VerdictLimit) {

    private val verdicts = LinkedHashMap<String, StillVerdict>()

    @Synchronized
    fun verdict(key: String): StillVerdict? = verdicts[key]

    @Synchronized
    fun remember(key: String, verdict: StillVerdict) {
        if (verdicts.size >= limit && !verdicts.containsKey(key)) {
            val eldest = verdicts.keys.firstOrNull()
            if (eldest != null) verdicts.remove(eldest)
        }
        verdicts[key] = verdict
    }

    @Synchronized
    fun forget(key: String) {
        verdicts.remove(key)
    }
}

private val CandidatePermille = listOf(330L, 500L, 680L, 120L)

private const val UnknownDurationCandidateMs = 1_000L

// 5% of the range. A frame under it is not a dark scene, it is the absence of one.
private const val MinMeanLuma = 12

// The flattest a real photograph gets. A gentle sky gradient still clears this several
// times over; a fade, a slate and a black leader do not clear it at all.
private const val MinSpread = 6

// Where the brightness credit stops. Well past a dim scene, well below a blown-out one.
private const val MidtoneLuma = 96

private const val SpreadWeight = 4

// 240 samples. Enough that a lit region of an otherwise dark frame is seen, few enough
// that judging costs a fraction of the decode that produced the frame.
internal const val SampleRows = 12
internal const val SampleColumns = 20

// An eighth of each edge is left out of the grid — the reason is in sampleAxis.
private const val InteriorInsetDivisor = 8

private const val VerdictLimit = 512

private const val RedWeight = 77
private const val GreenWeight = 150
private const val BlueWeight = 29
