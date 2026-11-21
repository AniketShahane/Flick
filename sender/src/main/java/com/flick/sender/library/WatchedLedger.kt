package com.flick.sender.library

import com.flick.sender.media.PlaybackCheckpoint
import com.flick.sender.media.PlaybackResumePolicy
import com.flick.sender.model.PlaybackPhase

/**
 * Contract §9: which casts count as watched, and how many of them are kept.
 *
 * It feeds the TV's `watched` field and Next episodes row only; nothing on the phone reads it.
 */
internal object WatchedLedgerPolicy {
    const val MAX_RECORDS = 5_000

    /** ENDED, or stopped inside the resume policy's finish zone. */
    fun finishedAt(positionMs: Long, durationMs: Long, phase: PlaybackPhase): Boolean {
        if (phase == PlaybackPhase.ENDED) return true
        if (positionMs <= 0L) return false
        return PlaybackResumePolicy.inFinishZone(positionMs, durationMs)
    }

    /**
     * When a stored checkpoint inside the finish zone says the item was watched, for a ledger
     * with no record of it: a checkpoint written before the zone widened is a finish the ledger
     * never saw. Null when the ledger already knows, or the checkpoint is not a finish.
     */
    fun backfillAtMs(checkpoint: PlaybackCheckpoint?, durationMs: Long, recorded: Boolean): Long? {
        if (recorded || checkpoint == null) return null
        if (!PlaybackResumePolicy.inFinishZone(checkpoint.positionMs, durationMs)) return null
        return checkpoint.updatedAtEpochMs
    }

    /** The keys to drop so that at most [max] remain, oldest first; ties go by key for determinism. */
    fun overflow(records: Map<String, Long>, max: Int = MAX_RECORDS): List<String> {
        if (records.size <= max) return emptyList()
        return records.entries
            .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(records.size - max)
            .map { it.key }
    }
}

/**
 * Follows one cast's confirmed position and says, once, when it became watched.
 *
 * Main-confined like the coordinator that feeds it. Armed only once a first frame proved the
 * cast, so a startup that failed while resuming inside the finish zone never counts.
 */
internal class WatchedRecorder {
    private var castId: String? = null
    private var fingerprint: String? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var phase = PlaybackPhase.BUFFERING
    private var recorded = false

    fun activate(castId: String, fingerprint: String) {
        this.castId = castId
        this.fingerprint = fingerprint
        positionMs = 0L
        durationMs = 0L
        phase = PlaybackPhase.BUFFERING
        recorded = false
    }

    /** The fingerprint the moment the cast reaches ENDED, and null every other time. */
    fun onConfirmed(castId: String, positionMs: Long, durationMs: Long, phase: PlaybackPhase): String? {
        if (castId != this.castId) return null
        this.positionMs = positionMs
        this.durationMs = durationMs
        this.phase = phase
        if (recorded || phase != PlaybackPhase.ENDED) return null
        recorded = true
        return fingerprint
    }

    /** The fingerprint when the cast ends inside its finish zone and was not already counted. */
    fun finish(castId: String): String? {
        if (castId != this.castId) return null
        val owed = fingerprint?.takeIf { !recorded && WatchedLedgerPolicy.finishedAt(positionMs, durationMs, phase) }
        this.castId = null
        fingerprint = null
        return owed
    }
}
