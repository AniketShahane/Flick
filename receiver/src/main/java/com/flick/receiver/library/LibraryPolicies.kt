package com.flick.receiver.library

/**
 * The TV's half of the library handshake (§4), one instance per socket. Pure: the server
 * sends what [onFrame] returns and closes on [Step.Deny] and [Step.Violation].
 *
 * [findKey] answers the pairing key for exactly `(tvId, keyId)`, or null. A denial changes
 * nothing in the pairing store: this class can only read it.
 */
class LibraryHandshake(
    private val tvId: String,
    private val peerIp: String,
    private val serverHost: String,
    private val serverPort: Int,
    private val findKey: (tvId: String, keyId: String) -> String?,
    private val newNonce: () -> String,
) {
    sealed interface Step {
        data class Reply(val frame: String) : Step

        /** Send `libDenied`, then close 1008. */
        data object Deny : Step

        /** Close 1008 without a word: the frame was malformed or out of place. */
        data object Violation : Step

        /** An unknown type (§7); the six-second deadline still bounds the handshake. */
        data object Ignore : Step

        /** Proven. [readyFrame] is the `libReady` to send once the session is installed. */
        data class Authenticated(val keyId: String, val readyFrame: String) : Step
    }

    private class Challenge(val keyId: String, val key: String, val clientNonce: String, val serverNonce: String)

    private var helloSeen = false
    private var challenge: Challenge? = null
    private var finished = false
    private var ignored = 0

    fun onFrame(frame: PhoneFrame): Step {
        if (finished) return Step.Violation
        return when (frame) {
            is PhoneFrame.Hello -> hello(frame)
            is PhoneFrame.Proof -> proof(frame)
            // Bounded like `/control`'s pre-auth budget: unknown frames cannot hold a permit
            // for the whole six seconds.
            is PhoneFrame.Unknown -> if (++ignored >= MAX_IGNORED) finish(Step.Violation) else Step.Ignore
            // Malformed, or a session frame before the session exists.
            else -> finish(Step.Violation)
        }
    }

    private fun hello(frame: PhoneFrame.Hello): Step {
        if (helloSeen) return finish(Step.Deny)
        helloSeen = true
        if (frame.tvId != tvId) return finish(Step.Deny)
        val key = findKey(frame.tvId, frame.keyId) ?: return finish(Step.Deny)
        val serverNonce = newNonce()
        challenge = Challenge(frame.keyId, key, frame.clientNonce, serverNonce)
        return Step.Reply(
            LibraryFrames.challenge(tvId, frame.keyId, frame.clientNonce, serverNonce, peerIp, serverHost, serverPort),
        )
    }

    private fun proof(frame: PhoneFrame.Proof): Step {
        // Consumed before anything is compared: one proof per challenge, whatever was wrong with it.
        val issued = challenge ?: return finish(Step.Deny)
        challenge = null
        if (frame.clientNonce != issued.clientNonce || frame.serverNonce != issued.serverNonce) return finish(Step.Deny)
        val expected = runCatching {
            LibraryAuth.proof(issued.key, "client", tvId, issued.keyId, issued.clientNonce, issued.serverNonce, peerIp, serverHost, serverPort)
        }.getOrNull() ?: return finish(Step.Deny)
        if (!LibraryAuth.constantTimeEquals(expected, frame.proof)) return finish(Step.Deny)
        val server = LibraryAuth.proof(issued.key, "server", tvId, issued.keyId, issued.clientNonce, issued.serverNonce, peerIp, serverHost, serverPort)
        return finish(Step.Authenticated(issued.keyId, LibraryFrames.ready(issued.serverNonce, server)))
    }

    private fun finish(step: Step): Step {
        finished = true
        challenge = null
        return step
    }

    companion object {
        /** The third ignored frame before authentication closes the socket, as on `/control`. */
        const val MAX_IGNORED = 3
    }
}

/**
 * The one outstanding Play (§6.1). Times are on one monotonic clock passed in; the caller
 * schedules a [tick] at [deadlineAtMs].
 */
class LibraryPlayMachine {

    var state: LibraryPlay = LibraryPlay.Idle
        private set

    /** When [tick] must run next, or null. */
    var deadlineAtMs: Long? = null
        private set

    private var req: String? = null

    /**
     * The library and control sockets are not ordered against each other, so the phone's
     * `loadMedia` can overtake its own `playAck`.
     */
    private var castSeenWhileAsking = false

    /** True when the caller must send the `play` frame with [reqId]. */
    fun request(id: String, connected: Boolean, reqId: String, nowMs: Long): Boolean {
        if (state is LibraryPlay.Asking || state is LibraryPlay.Starting) return false
        if (!connected) {
            settle(LibraryPlay.Failed(id, LibraryPlayFailure.OFFLINE))
            return false
        }
        state = LibraryPlay.Asking(id)
        req = reqId
        castSeenWhileAsking = false
        deadlineAtMs = nowMs + ASK_TIMEOUT_MS
        return true
    }

    /** False when the ack answers no request this machine is waiting on. */
    fun onAck(reqId: String, ok: Boolean, refusal: PlayRefusal?, nowMs: Long): Boolean {
        val asking = state as? LibraryPlay.Asking ?: return false
        if (reqId != req) return false
        when {
            !ok -> settle(LibraryPlay.Failed(asking.id, failureFor(refusal ?: PlayRefusal.UNAVAILABLE)))
            castSeenWhileAsking -> settle(LibraryPlay.Idle)
            else -> {
                state = LibraryPlay.Starting(asking.id)
                deadlineAtMs = nowMs + START_TIMEOUT_MS
            }
        }
        return true
    }

    /** A control-v2 `loadMedia` this TV accepted. */
    fun onCastStarted() {
        when (state) {
            is LibraryPlay.Starting -> settle(LibraryPlay.Idle)
            is LibraryPlay.Asking -> castSeenWhileAsking = true
            else -> Unit
        }
    }

    /** No `playAck` can arrive on a socket that is gone; an accepted request may still start. */
    fun onLinkLost() {
        val asking = state as? LibraryPlay.Asking ?: return
        settle(if (castSeenWhileAsking) LibraryPlay.Idle else LibraryPlay.Failed(asking.id, LibraryPlayFailure.OFFLINE))
    }

    fun tick(nowMs: Long) {
        val due = deadlineAtMs ?: return
        if (nowMs < due) return
        when (val current = state) {
            // The cast it asked for is already playing; only the ack went missing.
            is LibraryPlay.Asking -> settle(if (castSeenWhileAsking) LibraryPlay.Idle else LibraryPlay.Failed(current.id, LibraryPlayFailure.NO_ANSWER))
            is LibraryPlay.Starting -> settle(LibraryPlay.Failed(current.id, LibraryPlayFailure.NOT_STARTED))
            else -> deadlineAtMs = null
        }
    }

    fun clearResult() {
        if (state is LibraryPlay.Failed) settle(LibraryPlay.Idle)
    }

    private fun settle(next: LibraryPlay) {
        state = next
        req = null
        deadlineAtMs = null
        castSeenWhileAsking = false
    }

    companion object {
        const val ASK_TIMEOUT_MS = 8_000L
        const val START_TIMEOUT_MS = 30_000L

        fun failureFor(refusal: PlayRefusal): LibraryPlayFailure = when (refusal) {
            PlayRefusal.MISSING -> LibraryPlayFailure.MISSING
            PlayRefusal.DISABLED -> LibraryPlayFailure.DISABLED
            PlayRefusal.BUSY -> LibraryPlayFailure.BUSY
            PlayRefusal.UNAVAILABLE -> LibraryPlayFailure.UNAVAILABLE
            PlayRefusal.BACKGROUND -> LibraryPlayFailure.BACKGROUND
        }
    }
}

/**
 * Phone thumbnails to ask for (§6.4): at most [maxOutstanding] in flight, the newest request
 * first, a `thumbMiss` never asked again, and at most [maxSends] asks per [sendWindowMs] —
 * the phone silently drops `thumbWant` past that cap.
 *
 * An outstanding request expires after [expiryMs]: the phone may have dropped it, so it goes
 * back behind the newer requests, at most [maxAttempts] asks in all. The caller re-pumps at
 * [nextPumpAtMs], since a freed slot or send budget otherwise waits for the next tile to ask.
 */
class ThumbnailQueue(
    private val maxOutstanding: Int = 6,
    private val maxQueued: Int = 60,
    private val expiryMs: Long = 15_000L,
    private val maxSends: Int = 60,
    private val sendWindowMs: Long = 10_000L,
    private val maxAttempts: Int = 3,
) {
    private val queued = ArrayDeque<String>()
    private val outstanding = LinkedHashMap<String, Long>()
    private val missed = HashSet<String>()
    private val attempts = HashMap<String, Int>()
    private val sentAt = ArrayDeque<Long>()

    val outstandingCount: Int get() = outstanding.size

    fun isMissed(id: String): Boolean = id in missed

    /** Queues [id] at the front. Returns nothing; call [pump] to learn what to send. */
    fun want(id: String, nowMs: Long) {
        expire(nowMs)
        if (id in missed || id in outstanding) return
        queued.remove(id)
        queued.addFirst(id)
        trim()
    }

    /** The ids to send `thumbWant` for now, each now outstanding. */
    fun pump(nowMs: Long): List<String> {
        expire(nowMs)
        pruneSends(nowMs)
        if (queued.isEmpty() || outstanding.size >= maxOutstanding || sentAt.size >= maxSends) return emptyList()
        val out = ArrayList<String>()
        while (outstanding.size < maxOutstanding && queued.isNotEmpty() && sentAt.size < maxSends) {
            val id = queued.removeFirst()
            outstanding[id] = nowMs
            attempts[id] = (attempts[id] ?: 0) + 1
            sentAt.addLast(nowMs)
            out += id
        }
        return out
    }

    /**
     * When [pump] may next have something to send or to expire, or null when nothing is
     * queued or in flight.
     */
    fun nextPumpAtMs(nowMs: Long): Long? {
        pruneSends(nowMs)
        var next: Long? = null
        outstanding.values.minOrNull()?.let { next = it + expiryMs }
        if (queued.isNotEmpty()) {
            val ready = when {
                outstanding.size >= maxOutstanding -> null
                sentAt.size >= maxSends -> sentAt.first() + sendWindowMs
                else -> nowMs
            }
            if (ready != null) next = next?.let { minOf(it, ready) } ?: ready
        }
        return next
    }

    /** True when [id] is still wanted, so its image is worth decoding — late frames included. */
    fun onArrived(id: String): Boolean {
        val wanted = outstanding.remove(id) != null || queued.remove(id)
        if (wanted) attempts.remove(id)
        return wanted
    }

    fun onMiss(id: String) {
        outstanding.remove(id)
        queued.remove(id)
        attempts.remove(id)
        missed += id
    }

    /** What was in flight on a socket that closed goes back to the front of the queue. */
    fun onDisconnect() {
        val inFlight = outstanding.keys.toList()
        outstanding.clear()
        for (id in inFlight.asReversed()) {
            queued.remove(id)
            queued.addFirst(id)
        }
        trim()
    }

    /** The catalog no longer holds these, or now has TMDB art for them. */
    fun retainOnly(keep: (String) -> Boolean) {
        queued.retainAll(keep)
        outstanding.keys.retainAll(keep)
        attempts.keys.retainAll(keep)
    }

    private fun expire(nowMs: Long) {
        val expired = outstanding.entries.filter { nowMs - it.value >= expiryMs }.map { it.key }
        if (expired.isEmpty()) return
        for (id in expired) {
            outstanding.remove(id)
            if ((attempts[id] ?: 0) < maxAttempts) {
                if (id !in queued) queued.addLast(id)
            } else {
                // Given up for now; a tile that asks again starts afresh.
                attempts.remove(id)
            }
        }
        trim()
    }

    private fun pruneSends(nowMs: Long) {
        while (sentAt.isNotEmpty() && nowMs - sentAt.first() >= sendWindowMs) sentAt.removeFirst()
    }

    private fun trim() {
        while (queued.size > maxQueued) attempts.remove(queued.removeLast())
    }
}

/** At most one pass per [intervalMs] (§6.5). */
class LibraryRateGate(private val intervalMs: Long) {
    private var lastAtMs: Long? = null

    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastAtMs
        if (last != null && nowMs - last < intervalMs) return false
        lastAtMs = nowMs
        return true
    }
}

/** The last few `casting` frames (§6.6): which catalog item each control-v2 cast is playing. */
class CastingMap(private val capacity: Int = 8) {
    private val entries = LinkedHashMap<String, String>()

    fun put(castId: String, itemId: String) {
        entries.remove(castId)
        entries[castId] = itemId
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    operator fun get(castId: String): String? = entries[castId]

    val size: Int get() = entries.size
}

/**
 * Assembles one show-season's `season` parts (§6.3). A part whose `parts` disagrees with
 * the parts already held restarts that season. At most [maxEpisodes] episodes are kept per
 * season; parts past that arrive empty, so a phone cannot grow one season without bound.
 */
class SeasonAssembler(private val maxEpisodes: Int = MAX_EPISODES) {
    private class Pending(val parts: Int) {
        val received = arrayOfNulls<List<LibraryEpisodeInfo>>(parts)
        var count = 0
    }

    private val pending = HashMap<LibrarySeasonKey, Pending>()

    /** The whole season once its last part lands, else null. */
    fun add(key: LibrarySeasonKey, part: Int, parts: Int, episodes: List<LibraryEpisodeInfo>): List<LibraryEpisodeInfo>? {
        if (parts <= 0 || part !in 0 until parts) return null
        val held = pending[key]?.takeIf { it.parts == parts } ?: Pending(parts).also { pending[key] = it }
        held.received[part]?.let { held.count -= it.size }
        val kept = episodes.take((maxEpisodes - held.count).coerceAtLeast(0))
        held.received[part] = kept
        held.count += kept.size
        if (held.received.any { it == null }) return null
        pending.remove(key)
        return held.received.flatMap { it!! }
    }

    fun clear() = pending.clear()

    companion object {
        /** As many as a whole catalog's parts (§5.2); no real season comes near it. */
        const val MAX_EPISODES = 4_000
    }
}

/** §10: whether, and how, an ended cast offers the next episode. */
object UpNextPolicy {
    const val AUTOPLAY_DELAY_MS = 10_000L

    /**
     * [castItemId] is what the phone's `casting` frame named for the ended cast; null when
     * this phone sent none, so the cast is not one of its catalog's files.
     */
    fun offer(
        castItemId: String?,
        catalog: LibraryCatalog?,
        connected: Boolean,
        autoplayEnabled: Boolean,
        streak: UpNextStreak,
        nowMs: Long,
        showOf: (LibraryItem) -> LibraryShow? = { null },
    ): UpNextOffer? {
        if (!connected || castItemId == null || catalog == null) return null
        val ended = catalog[castItemId]?.takeIf { it.isEpisode } ?: return null
        val next = LibraryShelving.following(ended, catalog) ?: return null
        val autoplay = streak.mayAutoplay(autoplayEnabled)
        return UpNextOffer(
            ended = ended,
            next = next,
            show = showOf(ended),
            autoplayAtMs = if (autoplay) nowMs + AUTOPLAY_DELAY_MS else null,
            stillWatching = autoplayEnabled && !autoplay,
        )
    }
}
