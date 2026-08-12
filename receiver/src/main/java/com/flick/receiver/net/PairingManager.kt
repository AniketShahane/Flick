package com.flick.receiver.net

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PairingSnapshot(
    val surface: PairingSurface,
    val pairedCount: Int,
    val mostRecentDeviceLabel: String?,
    val devices: List<PairedPhone> = emptyList(),
    /**
     * The phone whose Allow this TV could not write to its own storage, or null.
     *
     * Non-null only while the code minted in place of the refused one is the code on
     * screen — see [PairingManager.saveFailedForGeneration] — so the notice cannot
     * outlive its own truth. The phone is already told `denied reason=storage`; this is
     * the same fact told to the device that actually failed.
     */
    val saveFailedLabel: String? = null,
)

/**
 * One paired phone as everything outside this file is allowed to see it.
 *
 * It deliberately has no `key` field. The 256-bit pairing secret is the whole
 * authorization story and it never leaves [PairingManager]: the UI layer reads
 * this type, so no screen, snapshot or log can reach the key even by mistake.
 *
 * [pairedAtMs] is null for a record written before this TV recorded dates —
 * unknown, never guessed.
 */
data class PairedPhone(val keyId: String, val label: String, val pairedAtMs: Long?)

sealed interface PairingSurface {
    data object Standby : PairingSurface
    data class Open(val code: String, val generation: Long, val expiresAtElapsedMs: Long) : PairingSurface
    data class Locked(val generation: Long, val retryAtElapsedMs: Long) : PairingSurface
    data class Success(val deviceLabel: String, val generation: Long) : PairingSurface

    /**
     * A phone presented the right code and this TV is asking the room whether to
     * admit it. Nothing is committed yet: no key exists, and the only thing that
     * can produce one is someone pressing Allow on this screen.
     *
     * It exists because the QR carries the live code (payload v4), so reading the
     * screen is enough to submit a correct code on the first try — which the
     * cumulative-failure ceiling cannot bound, because that ceiling charges only
     * *wrong* codes. This state is what puts physical presence back in the room
     * without reintroducing typing.
     *
     * [expiresAtElapsedMs] is a real deadline on the `elapsedRealtime` timebase and
     * it resolves to a DENIAL, never to an allow. The screen draws it so a prompt
     * that vanishes is explicable rather than mysterious.
     */
    data class Confirming(
        val deviceLabel: String,
        val generation: Long,
        val expiresAtElapsedMs: Long,
    ) : PairingSurface

    /**
     * The cumulative-failure ceiling has been reached: the surface is closed, no
     * code exists, and nothing on the network can reopen it. Only
     * [PairingManager.resumePairing] does, and only something pressing a button on
     * this TV can call that.
     *
     * It carries no generation on purpose. A generation exists so a success can be
     * correlated with the code that produced it and so a countdown can be told from
     * its successor; a seal has neither, and a stable value keeps the snapshot flow
     * from re-emitting an identical state every time the surface is closed again.
     */
    data object Sealed : PairingSurface
}

sealed interface PairAttemptResult {
    data class Success(val key: String, val keyId: String, val deviceLabel: String) : PairAttemptResult
    data object SurfaceClosed : PairAttemptResult
    data object Expired : PairAttemptResult
    data object InvalidCode : PairAttemptResult
    data class LockedOut(val retryAtElapsedMs: Long) : PairAttemptResult
    /**
     * Durable storage rejected the new key.
     *
     * The code cannot be handed back — it was spent the moment it proved itself, at
     * [PairingManager.attemptPair], because a proven code must not still be admitting
     * a second phone while the room is being asked. So a fresh one is minted instead
     * and the surface reopens; [PairingManager.commitConfirmedPair] is the only place
     * this is produced.
     */
    data object PersistenceFailed : PairAttemptResult

    /**
     * The code was right and nothing has been committed. The caller must now wait on
     * [ticket] and, only on [PairConfirmationOutcome.ALLOWED], ask
     * [PairingManager.commitConfirmedPair] for the key.
     *
     * This is the ONE outcome that outlives the receiver's six-second authentication
     * window, and reaching it takes a code that passed a constant-time comparison.
     * Every other outcome is answered inside that window exactly as it was before
     * the confirmation existed.
     */
    data class NeedsConfirmation(val ticket: PairConfirmation) : PairAttemptResult
}

/** How one on-TV confirmation ended. Only [ALLOWED] may produce a pairing key. */
enum class PairConfirmationOutcome {
    ALLOWED,
    /** Someone in the room said no. */
    DENIED,
    /** The decision window elapsed with nobody answering. */
    EXPIRED,
    /**
     * The surface was taken down under the prompt — the app backgrounded, Settings
     * opened, a cast started, every phone was forgotten, or the asking phone hung up.
     * Nobody declined, but nobody allowed either, and only an allow may pair.
     */
    WITHDRAWN,
}

/**
 * One outstanding "Allow this phone?" decision, and the only channel between the
 * TV's screen and the socket that is waiting on it.
 *
 * **Single-shot in both directions.** Three independent parties can try to settle
 * it — a button on the TV, the manager's own tick past [expiresAtElapsedMs], and
 * the socket's own deadline or hang-up — so [resolve] is a compare-and-set and
 * returns the decision that actually stands rather than the one proposed. That is
 * what keeps the screen and the wire from ever reporting different answers.
 *
 * [consume] is the second single-shot: it guards the durable key write, so one
 * confirmation can mint at most one pairing key however many times a caller asks.
 */
class PairConfirmation internal constructor(
    val deviceLabel: String,
    val generation: Long,
    val expiresAtElapsedMs: Long,
    /** Carried so the commit can still clear the throttle the attempt was charged against. */
    internal val peerHost: String,
) {
    private val outcome = AtomicReference<PairConfirmationOutcome?>(null)
    private val settled = CompletableDeferred<Unit>()
    private val consumed = AtomicBoolean(false)

    /** Settles this decision if nothing has yet, and answers with the one that stands. */
    internal fun resolve(proposed: PairConfirmationOutcome): PairConfirmationOutcome {
        if (outcome.compareAndSet(null, proposed)) settled.complete(Unit)
        return outcome.get()!!
    }

    internal val decided: PairConfirmationOutcome? get() = outcome.get()

    /** Suspends until something decides. It never decides anything itself. */
    internal suspend fun await(): PairConfirmationOutcome {
        settled.await()
        return outcome.get()!!
    }

    /** True exactly once, for the caller allowed to write a key against this decision. */
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)
}

/** The only pairing authorization gate. All code checks and key writes share this monitor. */
class PairingManager(
    context: Context,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var generation = 0L
    private var visible = false
    private var open: PairingSurface.Open? = null
    private var lockoutRound = prefs.getInt(KEY_LOCKOUT_ROUND, 0).coerceIn(0, MAX_LOCKOUT_ROUNDS)
    private var lockoutUntilWall = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
    private var lockoutUntilElapsed = restoreLockout()
    private var failures = 0

    /**
     * Wrong codes counted across the WHOLE time this surface has been offering one,
     * and the seal that trips at [MAX_SURFACE_FAILURES] of them.
     *
     * Both are read back from durable storage rather than starting at zero, and
     * both are written before the in-memory state is trusted. Process death is why:
     * the escalating lockout alone leaves a steady state of five guesses every eight
     * minutes, and a ceiling that any restart resets is that same steady state with
     * an extra step. Nothing on the LAN can restart this app — but Android can, at
     * any moment, for its own reasons, and a guessing run that has to wait for one
     * is not meaningfully slowed. The lockout deadline already persists for exactly
     * this reason; the ceiling above it would be strange not to.
     */
    private var surfaceFailures = prefs.getInt(KEY_SURFACE_FAILURES, 0).coerceIn(0, MAX_SURFACE_FAILURES)
    private var surfaceSealed = prefs.getBoolean(KEY_SEALED, false)

    /**
     * The one outstanding on-TV confirmation, or null. In memory only, and
     * deliberately: a decision nobody answered before this process died is a
     * decision nobody answered, and it must not come back as an offer to pair.
     *
     * At most one exists at a time, because [attemptPair] consumes the code the
     * moment it proves it — a second phone submitting the same digits then finds no
     * open code and is refused like any other closed surface.
     */
    private var pending: PairConfirmation? = null
    private data class HostThrottle(var failures: Int, var retryAtElapsedMs: Long)
    private val hostThrottles = LinkedHashMap<String, HostThrottle>(MAX_HOST_THROTTLES, 0.75f, true)
    // A TV that was sealed when it was last killed comes back sealed, and says so
    // from its very first frame rather than from whenever something asks for a code.
    private val _snapshot = MutableStateFlow(
        snapshot(if (surfaceSealed) PairingSurface.Sealed else PairingSurface.Standby),
    )
    val snapshot: StateFlow<PairingSnapshot> = _snapshot

    val tvName: String
        get() = prefs.getString(KEY_NAME, DEFAULT_TV_NAME)?.trim().orEmpty().ifBlank { DEFAULT_TV_NAME }

    /** Persists a canonical TV label and reports the synchronous write result. */
    @Synchronized fun renameTv(label: String): Boolean {
        val next = normalizeLabel(label, 80).ifBlank { return false }
        if (next == tvName) return true
        return prefs.edit().putString(KEY_NAME, next).commit()
    }

    val tvId: String = prefs.getString(KEY_TV_ID, null) ?: randomId().also {
        prefs.edit().putString(KEY_TV_ID, it).commit()
    }

    /**
     * Ask for a code. Every route that wants one comes through here — the app
     * returning to the foreground onto the pair screen with nothing paired, leaving
     * Settings, "Pair another phone" on the idle screen — and a sealed surface
     * refuses all of them alike. That is the whole point of the ceiling:
     * [onForeground] calls this with nobody pressing anything, so a seal any request
     * could lift would be lifted by the app simply being looked at. Clearing it
     * takes [resumePairing], which nothing on the network can reach.
     */
    @Synchronized fun requestOpen() {
        // The seal's whole job is to survive this. All it takes here is refusing to
        // set [visible] — [publishEligible] then publishes the seal rather than a
        // code, and no other path sets it either.
        when (openRefusal(sealed = surfaceSealed, confirming = pending != null)) {
            OpenRefusal.SEALED -> {
                FlickLog.d("pair", "surface=sealed (open refused)")
                return publishEligible()
            }
            // A confirmation on screen is a decision in progress. Minting a code
            // under it would leave the phone waiting on that decision holding
            // digits this TV had already replaced, and would put a fresh code on a
            // screen whose whole job at that moment is to ask one question. The
            // prompt has its own bounded deadline, so nothing is stuck: the very
            // next request after it resolves is honoured.
            OpenRefusal.CONFIRMING -> {
                FlickLog.d("pair", "surface=confirming (open refused)")
                return
            }
            OpenRefusal.NONE -> Unit
        }
        visible = true
        FlickLog.d("pair", "surface=open")
        publishEligible()
    }

    @Synchronized fun closeSurface() {
        visible = false
        open = null // a code is never valid when it is not visibly rendered.
        // A prompt nobody can see is a prompt nobody can answer, and physical
        // presence is the entire factor this confirmation adds. Withdrawing BEFORE
        // the publish below keeps the two consistent: [resolvePending] republishes
        // through the same decision, and it must not republish a surface this call
        // has not finished closing.
        pending?.let { resolvePending(it, PairConfirmationOutcome.WITHDRAWN) }
        FlickLog.d("pair", "surface=closed")
        // Through the same decision as everything else, so a seal outlives the
        // close that carried it: entering Settings closes the surface, and a plain
        // Standby here would stop the screen saying why pairing stopped.
        publishEligible()
    }

    /**
     * Clears the ceiling and offers a code again. This is the ONLY way out of
     * [PairingSurface.Sealed], and it exists to be wired to a control on the TV's
     * own screen: an attacker who can reach the control socket cannot press a
     * button on the television, so a remote guessing run ends needing someone in
     * the room. Returns false when nothing was sealed, or when the durable write
     * refused — a resume that did not persist must not be reported as one, or a
     * restart would silently re-seal a surface the user was told is open.
     *
     * It deliberately does NOT reset [lockoutRound] or the lockout deadline. Those
     * are a separate, already-earned restriction, and handing back a fresh
     * escalation ladder every time someone walked to the TV would make the ladder
     * meaningless. [publishEligible] republishes a lockout that is still running.
     */
    @Synchronized fun resumePairing(): Boolean {
        if (!surfaceSealed) return false
        return commitPairing(
            commit = { prefs.edit().putBoolean(KEY_SEALED, false).putInt(KEY_SURFACE_FAILURES, 0).commit() },
            afterCommit = {
                surfaceSealed = false
                surfaceFailures = 0
                failures = 0
                // The per-host throttle is NOT cleared. It is a ten-second
                // restriction the guessing host has already earned, and a resume is
                // not a reason to hand it back.
                visible = true
                open = null
                FlickLog.i("pair", "surface resumed on-device")
                publishEligible()
            },
        )
    }

    /**
     * The app came back to the foreground. [pairingRendered] is the caller's answer
     * to the one thing this class cannot see: whether the surface that DRAWS a code
     * is the surface on screen.
     *
     * It is a parameter rather than an assumption because the assumption was wrong.
     * This used to open a code whenever no phone was paired, on the reasoning that a
     * TV with nothing paired is a TV showing the pair screen. It is not always: the
     * pair screen offers a way into Settings — the only one a factory-fresh TV has —
     * Settings survives a stop/start in the composition, and it outranks Pair in the
     * router. A screensaver over an idle Settings screen is close to inevitable, and
     * the app resumed from one straight into a live, rotating, guessable code that
     * nothing on screen rendered. That is precisely the state [closeSurface] exists
     * to refuse, and the owner would have believed pairing was shut.
     *
     * The store's half of the decision stays here, because it is the store's fact:
     * only a TV with no phone paired is owed a code nobody asked for. See
     * `ReceiverApp.pairingSurfaceRendered` for the screen's half.
     */
    @Synchronized fun onForeground(pairingRendered: Boolean) {
        if (pairingRendered && storedPhones().isEmpty()) requestOpen()
    }
    @Synchronized fun onBackground() = closeSurface()

    @Synchronized fun tick() {
        // The prompt's deadline, which is the screen's half of a window the waiting
        // socket also bounds for itself. Two clocks for one decision is deliberate:
        // this one is what makes the card come down, and it cannot disagree with the
        // socket because [PairConfirmation] settles once and both read that answer.
        val confirming = pending
        if (confirming != null) {
            if (elapsed() >= confirming.expiresAtElapsedMs) {
                resolvePending(confirming, PairConfirmationOutcome.EXPIRED)
            }
            return
        }
        val current = open
        // An Open code and a Locked retry deadline are distinct states. In
        // particular, a normal Open code must stay stable until its own expiry.
        if (current != null) {
            if (elapsed() >= current.expiresAtElapsedMs) openNewCode()
            return
        }
        if (_snapshot.value.surface is PairingSurface.Locked && elapsed() >= lockoutUntilElapsed) publishEligible()
    }

    @Synchronized fun attemptPair(candidate: String, device: String, peerHost: String = ""): PairAttemptResult {
        // Do not call tick here: an expired candidate must be compared against
        // the generation it arrived for, never against a freshly rotated code.
        if (!visible || open == null) return PairAttemptResult.SurfaceClosed
        if (elapsed() < lockoutUntilElapsed) return PairAttemptResult.LockedOut(lockoutUntilElapsed)
        val current = open ?: return PairAttemptResult.SurfaceClosed
        if (elapsed() >= current.expiresAtElapsedMs) { openNewCode(); return PairAttemptResult.Expired }
        val host = if (MediaUrlValidator.isPrivateIpv4(peerHost)) peerHost else "unknown"
        hostThrottles[host]?.takeIf { elapsed() < it.retryAtElapsedMs }?.let { return PairAttemptResult.LockedOut(it.retryAtElapsedMs) }
        if (!constantTimeEquals(candidate, current.code)) {
            chargeHost(host)
            failures++
            surfaceFailures++
            // The answer stays the ordinary InvalidCode in every branch. Telling the
            // caller it has just sealed the surface would hand it the one fact the
            // rest of this class works to withhold, and the next attempt already
            // gets SurfaceClosed like any other closed surface does.
            when (failureCharge(surfaceFailures, failures)) {
                FailureCharge.SEAL -> sealSurface()
                FailureCharge.LOCKOUT -> {
                    persistSurfaceFailures()
                    failures = 0
                    beginLockout()
                }
                FailureCharge.RECORDED -> persistSurfaceFailures()
            }
            return PairAttemptResult.InvalidCode
        }
        val label = normalizeLabel(device, 80).ifBlank { return PairAttemptResult.InvalidCode }
        // Everything past here has proven the code, and nothing past here writes a
        // key. The code is consumed HERE rather than at the commit, for two reasons:
        // a correct code waiting on a human must not still be admitting a second
        // phone, and a decision that ends in a denial must not hand the observed
        // digits back for another attempt. The next attempt therefore meets a closed
        // surface, exactly as it did the instant after a success used to land.
        val ticket = PairConfirmation(
            deviceLabel = label,
            generation = current.generation,
            expiresAtElapsedMs = elapsed() + CONFIRM_WINDOW_MS,
            peerHost = host,
        )
        pending = ticket
        open = null
        // Never the label: it is a user-chosen name for a device on this LAN, and
        // the screen is where it belongs.
        FlickLog.i("pair", "confirmation requested gen=${current.generation} windowMs=$CONFIRM_WINDOW_MS")
        publish(PairingSurface.Confirming(label, current.generation, ticket.expiresAtElapsedMs))
        return PairAttemptResult.NeedsConfirmation(ticket)
    }

    /**
     * Someone in the room pressed Allow. Returns whether that press is what settled
     * the decision — false means it was already denied, expired or withdrawn, and a
     * late press must never revive it.
     *
     * The key is deliberately NOT written here. `SharedPreferences.commit` is
     * synchronous disk I/O and this runs on the main thread from a D-pad press; the
     * write stays where it has always been, on the socket's own thread, in
     * [commitConfirmedPair]. That also keeps a rejected write reported to the phone
     * as the same `storage` denial it was before this prompt existed.
     */
    @Synchronized fun allowPendingPair(): Boolean {
        val ticket = pending ?: return false
        return resolvePending(ticket, PairConfirmationOutcome.ALLOWED) == PairConfirmationOutcome.ALLOWED
    }

    /** Someone in the room said no. Reachable without waiting for the deadline. */
    @Synchronized fun denyPendingPair(): Boolean {
        val ticket = pending ?: return false
        return resolvePending(ticket, PairConfirmationOutcome.DENIED) == PairConfirmationOutcome.DENIED
    }

    /**
     * Settles [ticket] from a party that is not the TV's own screen — the waiting
     * socket's deadline, or that socket hanging up. Answers with the decision that
     * actually stands, which may be an Allow that got there first.
     */
    @Synchronized fun resolvePendingPair(
        ticket: PairConfirmation,
        outcome: PairConfirmationOutcome,
    ): PairConfirmationOutcome = resolvePending(ticket, outcome)

    /**
     * Writes the key for an ALLOWED confirmation, and only for one. The durable
     * transaction, its ordering and its contents are the ones [attemptPair] used to
     * run inline — this is the same commit moved behind the decision, not a new one.
     *
     * Authority comes from the ticket, not from [pending]: a decision that has been
     * allowed is allowed even if the screen has already moved on, and
     * [PairConfirmation.consume] is what stops one confirmation minting two keys.
     */
    @Synchronized fun commitConfirmedPair(ticket: PairConfirmation): PairAttemptResult {
        if (ticket.decided != PairConfirmationOutcome.ALLOWED) return PairAttemptResult.SurfaceClosed
        if (!ticket.consume()) return PairAttemptResult.SurfaceClosed
        val label = ticket.deviceLabel
        val key = randomKey(); val keyId = randomId()
        val records = storedRecords() + encodePairingRecord(keyId, key, wall(), label)
        // Key, keyId, date and label are one durable transaction before success is
        // published. KEY_LAST_DEVICE_ID rides along because the bare label alone
        // cannot say WHICH record the Idle screen is naming, and [forget] has to
        // know that to avoid leaving a name behind for a phone it just removed.
        val committed = commitPairing(
            commit = {
                prefs.edit().putStringSet(KEY_RECORDS, records)
                    .putString(KEY_LAST_DEVICE, label).putString(KEY_LAST_DEVICE_ID, keyId)
                    .putInt(KEY_LOCKOUT_ROUND, 0).putLong(KEY_LOCKOUT_UNTIL, 0L)
                    // A phone that knew the code spends the budget its mistyping
                    // ran up: the ceiling exists to bound guessing, and this was
                    // not guessing.
                    .putInt(KEY_SURFACE_FAILURES, 0).commit()
            },
            afterCommit = {
                failures = 0; lockoutRound = 0; lockoutUntilElapsed = 0L; lockoutUntilWall = 0L
                surfaceFailures = 0
                hostThrottles.remove(ticket.peerHost)
                pending = null
                open = null
            },
        )
        if (!committed) {
            // The code was already spent proving itself, so there is nothing to hand
            // back: clear the prompt and offer a fresh code rather than leaving the
            // screen asking about a phone whose key this TV failed to store.
            pending = null
            // Tagged with the generation the code below is about to take, so the notice
            // rides exactly that one code and is gone on the next rotation. A
            // publishEligible that lands anywhere but CODE simply never matches.
            saveFailedForGeneration = generation + 1
            saveFailedLabel = label
            publishEligible()
            return PairAttemptResult.PersistenceFailed
        }
        publish(PairingSurface.Success(label, ticket.generation))
        return PairAttemptResult.Success(key, keyId, label)
    }

    /**
     * Settles [ticket] and, unless it stands allowed and still inside its window,
     * takes the prompt down and offers a code again.
     *
     * An allow inside the window leaves the card standing, because the key has not
     * been written yet and [commitConfirmedPair] is what replaces it with the
     * confirmation the viewer is owed. Past the deadline it is dropped regardless: a
     * commit that never arrived — a socket cancelled between the press and the write —
     * must not leave this screen asking a question nothing can answer. Dropping it is
     * safe because the commit's authority is the ticket and not this field, so a slow
     * write still lands.
     */
    private fun resolvePending(
        ticket: PairConfirmation,
        proposed: PairConfirmationOutcome,
    ): PairConfirmationOutcome {
        val settled = ticket.resolve(proposed)
        if (pending !== ticket) return settled
        if (settled == PairConfirmationOutcome.ALLOWED && elapsed() < ticket.expiresAtElapsedMs) return settled
        pending = null
        FlickLog.i("pair", "confirmation settled=$settled gen=${ticket.generation}")
        publishEligible()
        return settled
    }

    @Synchronized fun finishSuccess() { if (_snapshot.value.surface is PairingSurface.Success) { visible = false; publish(PairingSurface.Standby) } }

    @Synchronized fun findKey(tvId: String, keyId: String): PairingRecord? =
        if (tvId != this.tvId) null else storedRecords().mapNotNull(PairingRecord::decode)
            .firstOrNull { it.keyId == keyId }

    /** Removes every credential only after the durable transaction succeeds. */
    @Synchronized fun forgetAllPairings(): Boolean {
        return commitForgetPairings(
            commit = {
                prefs.edit().remove(KEY_RECORDS).remove(KEY_LAST_DEVICE).remove(KEY_LAST_DEVICE_ID)
                    .putInt(KEY_LOCKOUT_ROUND, 0).putLong(KEY_LOCKOUT_UNTIL, 0L)
                    // Forget all is a control on this TV's own screen, so it carries
                    // the same physical presence [resumePairing] demands and lifts
                    // the same ceiling. It must: this is the path a TV reaches zero
                    // phones by, and leaving it sealed would strand a TV with no
                    // phones and no code.
                    .putInt(KEY_SURFACE_FAILURES, 0).putBoolean(KEY_SEALED, false).commit()
            },
            afterCommit = {
                failures = 0
                lockoutRound = 0
                lockoutUntilElapsed = 0L
                lockoutUntilWall = 0L
                surfaceFailures = 0
                surfaceSealed = false
                hostThrottles.clear()
                visible = true
                open = null
                // Forget all is the owner revoking every phone. A confirmation still
                // standing under it must not be allowed to write the first record
                // back moments later, so the decision is withdrawn rather than left
                // to its deadline.
                pending?.let { it.resolve(PairConfirmationOutcome.WITHDRAWN); pending = null }
                publishEligible()
            },
        )
    }

    /**
     * Removes exactly one credential, durable write first, and returns whether it
     * is gone. False means this TV still admits that phone — no record carried
     * [keyId], or `SharedPreferences` rejected the write — so a caller can never
     * report a forget that did not happen.
     *
     * Unlike [forgetAllPairings] this does NOT reopen the pairing surface while
     * any phone remains: a code is an authorization surface, and removing one of
     * several phones is not a request to admit a new one. Reaching zero is the
     * exception, and it takes exactly the [forgetAllPairings] path — including the
     * lockout reset, so a TV cannot be left with no phones and no way to re-pair
     * for the eight minutes a lockout can still be running.
     *
     * That exception is a contract on the CALLER: reaching zero opens a code, and
     * this class cannot see which surface the screen is showing. A caller that
     * keeps a higher-priority surface up after this returns leaves a code that is
     * live, rotating and accepting attempts with nothing rendering it — which is
     * exactly what [closeSurface] refuses to allow. `ReceiverApp` closes Settings
     * on the emptied result for that reason.
     */
    @Synchronized fun forget(keyId: String): Boolean {
        val records = storedRecords()
        val remaining = recordsWithout(records, keyId) ?: return false
        val emptied = remaining.isEmpty()
        val nextLast = lastDeviceAfterForget(
            remaining = remaining,
            forgottenKeyId = keyId,
            forgottenLabel = records.mapNotNull(::decodePairingRecord).firstOrNull { it.keyId == keyId }?.label,
            storedKeyId = prefs.getString(KEY_LAST_DEVICE_ID, null),
            storedLabel = prefs.getString(KEY_LAST_DEVICE, null),
        )
        return commitForgetPairings(
            commit = {
                prefs.edit().apply {
                    if (emptied) remove(KEY_RECORDS) else putStringSet(KEY_RECORDS, remaining)
                    if (nextLast != null) {
                        if (nextLast.keyId == null) {
                            remove(KEY_LAST_DEVICE); remove(KEY_LAST_DEVICE_ID)
                        } else {
                            putString(KEY_LAST_DEVICE, nextLast.label)
                            putString(KEY_LAST_DEVICE_ID, nextLast.keyId)
                        }
                    }
                    if (emptied) {
                        putInt(KEY_LOCKOUT_ROUND, 0); putLong(KEY_LOCKOUT_UNTIL, 0L)
                        putInt(KEY_SURFACE_FAILURES, 0); putBoolean(KEY_SEALED, false)
                    }
                }.commit()
            },
            afterCommit = {
                FlickLog.i("pair", "forgot keyIdFp=${FlickLog.fp(keyId)} remaining=${remaining.size}")
                if (emptied) {
                    failures = 0
                    lockoutRound = 0
                    lockoutUntilElapsed = 0L
                    lockoutUntilWall = 0L
                    surfaceFailures = 0
                    surfaceSealed = false
                    hostThrottles.clear()
                    visible = true
                    open = null
                    // Reaching zero phones takes the Forget-all path, including its
                    // withdrawal: see [forgetAllPairings].
                    pending?.let { it.resolve(PairConfirmationOutcome.WITHDRAWN); pending = null }
                    publishEligible()
                } else {
                    publish(_snapshot.value.surface)
                }
            },
        )
    }

    /**
     * Renames exactly one paired phone, durable write first, and returns whether
     * the name on this TV actually changed. False means it did not — no record
     * carries [keyId], [label] normalizes to nothing, or `SharedPreferences`
     * rejected the write — so a caller can never report a rename that did not
     * happen.
     *
     * **A rename touches the label and nothing else.** [recordsRenamed] carries
     * the key id, the 256-bit key and the pairing date across verbatim, so the
     * phone stays paired on the credential it already had: [findKey] answers the
     * same record it answered a moment ago, a live control session authenticated
     * on that key is unaffected, and an outstanding resume handshake — which
     * validates against the copy it cached at `resumeInit` — is still valid. That
     * is why this does NOT go through `ControlServer` the way [forget] must:
     * there is no session to revoke, and routing it through the server would take
     * a lock this class must never be called under.
     *
     * `last_device` follows only when it names this phone. The Idle screen renders
     * it as "Paired with …", and a rename that did not reach it would leave
     * standby naming a phone by a name the Settings list no longer shows.
     */
    @Synchronized fun rename(keyId: String, label: String): Boolean {
        val next = normalizeLabel(label, 80).ifBlank { return false }
        val records = storedRecords()
        val current = records.mapNotNull(::decodePairingRecord).firstOrNull { it.keyId == keyId } ?: return false
        val renamed = recordsRenamed(records, keyId, next) ?: return false
        val nextLast = lastDeviceAfterRename(
            renamedKeyId = keyId,
            oldLabel = current.label,
            newLabel = next,
            storedKeyId = prefs.getString(KEY_LAST_DEVICE_ID, null),
            storedLabel = prefs.getString(KEY_LAST_DEVICE, null),
        )
        return commitPairing(
            commit = {
                prefs.edit().apply {
                    putStringSet(KEY_RECORDS, renamed)
                    if (nextLast != null) {
                        putString(KEY_LAST_DEVICE, nextLast.label)
                        putString(KEY_LAST_DEVICE_ID, nextLast.keyId)
                    }
                }.commit()
            },
            afterCommit = {
                // Never the label: it is a user-chosen name for a device on this
                // LAN, and the fingerprint is all a diagnostic needs to correlate.
                FlickLog.i("pair", "renamed keyIdFp=${FlickLog.fp(keyId)}")
                publish(_snapshot.value.surface)
            },
        )
    }

    @Synchronized fun clearPairings() = forgetAllPairings()

    /** Every paired phone, newest first. Never carries the pairing key. */
    @Synchronized fun pairedDevices(): List<PairedPhone> = storedPhones()

    @Synchronized fun pairedCount(): Int = storedPhones().size
    @Synchronized fun pairedLabel(): String? = prefs.getString(KEY_LAST_DEVICE, null)

    /**
     * QR payload v4: the endpoint **and the live 4-digit code**, so one scan
     * completes pairing.
     *
     * This deliberately reverses v3, which carried only host and port and kept the
     * code an out-of-band factor read off the screen — under v3 a scan authorized
     * nothing on its own. It does now: anyone who can photograph the TV screen
     * holds everything needed to pair, and the code is no longer a second factor
     * but a second *copy* of the first. That is a product decision, not an
     * oversight, and the visible code stays on screen because manual entry is
     * still the fallback when a camera cannot read the plate.
     *
     * What still limits the exposure is the code's own life: [CODE_TTL_MS], and
     * only while the surface is visibly rendered. That makes the payload itself
     * perishable, so **the QR must be re-encoded whenever the code rotates** — a
     * QR built against a consumed code is a QR that fails. See `ReceiverApp`,
     * which keys the payload on the code for exactly that reason.
     *
     * Returns null rather than a placeholder whenever any part of the binding is
     * unreal — no host, no port, or no live code. A drawn symbol is a promise that
     * scanning it works, and there is no honest QR to draw while the surface is
     * locked or standing by.
     */
    fun qrPayload(host: String, port: Int, code: String): String? = pairingQrPayload(host, port, code)

    /** The single place that decides what the surface is showing. */
    private fun publishEligible() {
        when (surfaceDecision(surfaceSealed, visible, elapsed() < lockoutUntilElapsed)) {
            SurfaceDecision.SEALED -> publish(PairingSurface.Sealed)
            SurfaceDecision.STANDBY -> publish(PairingSurface.Standby)
            SurfaceDecision.LOCKED -> publish(PairingSurface.Locked(++generation, lockoutUntilElapsed))
            SurfaceDecision.CODE -> openNewCode()
        }
    }

    /**
     * The cumulative-failure ceiling. Same state transition [closeSurface] makes —
     * not visible, no code, therefore no code is valid — plus the durable flag that
     * stops every reopening path short of [resumePairing].
     *
     * The durable write is attempted AFTER the in-memory seal, which inverts the
     * order [commitPairing] enforces everywhere else, and deliberately. That
     * discipline exists so this class never *claims* something it did not persist:
     * a pairing that was not stored, a forget that did not happen. A seal claims
     * nothing — it withdraws — so a storage failure has to leave the surface shut
     * for this process rather than open for everyone.
     */
    private fun sealSurface() {
        visible = false
        open = null
        surfaceSealed = true
        failures = 0
        prefs.edit().putBoolean(KEY_SEALED, true).putInt(KEY_SURFACE_FAILURES, surfaceFailures).commit()
        // The count, never a code and never the host that spent it.
        FlickLog.w("pair", "surface sealed failures=$surfaceFailures")
        publishEligible()
    }

    /**
     * Durable on EVERY failure, not only at the ceiling: a budget a restart resets
     * is not a budget, and Android restarts this app for its own reasons.
     */
    private fun persistSurfaceFailures() {
        prefs.edit().putInt(KEY_SURFACE_FAILURES, surfaceFailures).commit()
    }
    private fun openNewCode() {
        val item = PairingSurface.Open(randomCode(), ++generation, elapsed() + CODE_TTL_MS)
        open = item; publish(item)
        // NEVER the code value: a SHA-256 of four digits is a 10,000-entry lookup.
        FlickLog.d("pair", "code rotated gen=${item.generation} ttlMs=$CODE_TTL_MS codeLen=${item.code.length}")
    }
    private fun beginLockout() {
        open = null
        lockoutRound = (lockoutRound + 1).coerceAtMost(MAX_LOCKOUT_ROUNDS)
        val duration = (LOCKOUT_BASE_MS shl (lockoutRound - 1)).coerceAtMost(MAX_LOCKOUT_MS)
        lockoutUntilElapsed = elapsed() + duration; lockoutUntilWall = wall() + duration
        prefs.edit().putInt(KEY_LOCKOUT_ROUND, lockoutRound).putLong(KEY_LOCKOUT_UNTIL, lockoutUntilWall).commit()
        FlickLog.w("pair", "lockout round=$lockoutRound durationMs=$duration")
        publish(PairingSurface.Locked(++generation, lockoutUntilElapsed))
    }
    private fun chargeHost(host: String) {
        val throttle = hostThrottles[host] ?: HostThrottle(0, 0L).also {
            if (hostThrottles.size >= MAX_HOST_THROTTLES) hostThrottles.entries.iterator().let { iterator -> if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
            hostThrottles[host] = it
        }
        throttle.failures++
        if (throttle.failures >= HOST_FAILURES) {
            throttle.failures = 0
            throttle.retryAtElapsedMs = elapsed() + HOST_THROTTLE_MS
        }
    }
    private fun restoreLockout(): Long {
        val remaining = (lockoutUntilWall - wall()).coerceIn(0L, MAX_LOCKOUT_MS)
        return elapsed() + remaining
    }
    /**
     * The code generation minted in place of a refused commit, and the phone it was
     * refused for. Together they bound the save-failed notice to one code rather than
     * to a flag someone has to remember to clear.
     */
    private var saveFailedForGeneration: Long? = null
    private var saveFailedLabel: String? = null

    private fun publish(surface: PairingSurface) { _snapshot.value = snapshot(surface) }
    // Count and list come from the same decoded read. A record that cannot be
    // decoded can never satisfy [findKey] either, so counting it would claim a
    // phone is paired that this TV would refuse — and would leave the Settings
    // list one row short of its own heading.
    private fun snapshot(surface: PairingSurface): PairingSnapshot {
        val phones = storedPhones()
        val saveFailed = saveFailedLabel
            ?.takeIf { (surface as? PairingSurface.Open)?.generation == saveFailedForGeneration }
        return PairingSnapshot(surface, phones.size, pairedLabel(), phones, saveFailed)
    }
    private fun storedPhones(): List<PairedPhone> = pairedPhonesOf(storedRecords())
    private fun storedRecords(): Set<String> = prefs.getStringSet(KEY_RECORDS, emptySet()) ?: emptySet()
    private fun randomCode() = random.nextInt(10_000).toString().padStart(4, '0')
    private fun randomId() = bytes(16)
    private fun randomKey() = bytes(32)
    private fun bytes(size: Int) = Base64.encodeToString(ByteArray(size).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        private const val PREFS = "flick_pairing"
        private const val KEY_NAME = "tv_name"
        private const val KEY_TV_ID = "tv_id"
        // The key is still `_v2`: v3 changed the ENCODING of a record, not the
        // store it lives in, and both shapes coexist in this one set so an
        // existing pairing survives the upgrade without re-pairing.
        private const val KEY_RECORDS = "pairing_records_v2"
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_LAST_DEVICE_ID = "last_device_key_id"
        private const val KEY_LOCKOUT_ROUND = "lockout_round"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until_epoch_ms"
        private const val KEY_SURFACE_FAILURES = "surface_failures"
        private const val KEY_SEALED = "surface_sealed"
        const val DEFAULT_TV_NAME = "Flick TV"
        /** The QR grammar the sender parses. Bumped from 3 when `c=` was added. */
        const val QR_VERSION = 4
        private const val CODE_TTL_MS = 5 * 60_000L

        /**
         * How long the on-TV confirmation waits for a person, before resolving to a
         * denial.
         *
         * Thirty seconds is chosen against the physical task and against the socket
         * that is held open for it. The task is: notice the card, pick up the remote,
         * press one button — the focus ring is already sitting on a control, so it is
         * a single press and no navigation. Thirty seconds covers that with room to
         * spare for someone who has to reach across a sofa; a full minute would not
         * buy a second real attempt at the same task, because a viewer who has not
         * answered in thirty seconds is not in the room.
         *
         * The upper bound comes from the socket. This window plus the six-second
         * authentication phase is the whole life of a pre-auth connection, and thirty
         * keeps that at thirty-six — comfortably inside Ktor CIO's 45-second server
         * connection-idle timeout, so the decision can never be lost to a reaped
         * socket even if the client's ping were to stop. Forty-five seconds would put
         * the total at fifty-one and make that a real question.
         *
         * It resolves to DENY, never to allow. That is not a tuning choice.
         */
        internal const val CONFIRM_WINDOW_MS = 30_000L
        private const val LOCKOUT_BASE_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 8 * 60_000L
        private const val MAX_LOCKOUT_ROUNDS = 5

        /** Wrong codes per escalating lockout round. Internal so the budget arithmetic is testable. */
        internal const val MAX_FAILURES = 5

        /** The whole four-digit keyspace a guess is drawn from. Named so the ceiling can be argued against it. */
        internal const val CODE_KEYSPACE = 10_000

        /**
         * Wrong codes this surface will absorb in total before it seals itself.
         *
         * The escalating lockout alone has a steady state, and the steady state is
         * the problem: five attempts per eight-minute round is 900 a day against a
         * 10,000-code keyspace — about 9 % a day, so even odds inside roughly a
         * week. That the rounds get slower does not help, because nothing ever
         * *stops*.
         *
         * Twenty stops it. It is four full rounds of five, so reaching it means
         * sitting out three complete lockouts first — 30 s, then 60 s, then 120 s,
         * three and a half minutes of deliberate retrying on top of the
         * typing. That is far past any honest mistyping of a four-digit code read
         * off the screen in front of you, and it leaves the escalating lockout in
         * charge of ordinary fumbling exactly as before. It is also 0.2 % of the
         * keyspace: an attacker's whole budget, per person who walks to the TV and
         * presses resume, is one guess in five hundred. There is no steady state
         * left to grind.
         */
        internal const val MAX_SURFACE_FAILURES = 20
        private const val HOST_FAILURES = 3
        private const val HOST_THROTTLE_MS = 10_000L
        private const val MAX_HOST_THROTTLES = 32
    }
}

/** What the surface should be showing, as a decision separable from the clock. */
internal enum class SurfaceDecision { SEALED, STANDBY, LOCKED, CODE }

/**
 * The one ordering rule the pairing surface has, pulled out where it can be
 * argued with. `PairingManager` needs a `Context` and real `SharedPreferences`,
 * so this is how the rule gets tested at all — the same reason [recordsWithout]
 * and [pairingQrPayload] live out here.
 *
 * **A seal outranks everything, including [PairingManager.requestOpen].** That is
 * the whole ceiling: [PairingManager.onForeground] asks for a code with nobody
 * pressing anything whenever the pair screen is what the app came back to, so a
 * seal that any request could lift would be lifted by the app being looked at — and
 * by the app being killed and relaunched, which Android does on its own schedule.
 * Below it the existing order is unchanged: a surface nobody is rendering shows
 * nothing, a running lockout shows its countdown, and only then is a code minted.
 */
internal fun surfaceDecision(sealed: Boolean, visible: Boolean, lockedOut: Boolean): SurfaceDecision = when {
    sealed -> SurfaceDecision.SEALED
    !visible -> SurfaceDecision.STANDBY
    lockedOut -> SurfaceDecision.LOCKED
    else -> SurfaceDecision.CODE
}

/** Why a request for a pairing code cannot be honoured, or [NONE] when it can. */
internal enum class OpenRefusal { SEALED, CONFIRMING, NONE }

/**
 * The gate on [PairingManager.requestOpen], pulled out where it can be argued with
 * for the same reason [surfaceDecision] is: the manager needs real
 * `SharedPreferences`, and both of these are rules rather than state.
 *
 * A seal stays first. It is durable, it is the ceiling from the audit's M3, and
 * nothing reachable over the network may lift it — a confirmation is a live decision
 * that will be over within thirty seconds either way, so it cannot be allowed to
 * outrank the one restriction designed to outlast a process restart.
 *
 * [confirming] is above the ordinary "yes, mint one" answer because every route that
 * asks for a code asks with nobody pressing anything:
 * [PairingManager.onForeground] does it on a lifecycle event, and leaving Settings
 * does it on the way out. Minting a code under a prompt would rotate the digits the
 * waiting phone proved and put a second offer on a screen that is asking one
 * question.
 */
internal fun openRefusal(sealed: Boolean, confirming: Boolean): OpenRefusal = when {
    sealed -> OpenRefusal.SEALED
    confirming -> OpenRefusal.CONFIRMING
    else -> OpenRefusal.NONE
}

/** What a wrong code costs, given the two budgets one attempt spends at once. */
internal enum class FailureCharge { RECORDED, LOCKOUT, SEAL }

/**
 * The ceiling outranks the lockout, and it has to: past the ceiling there is no
 * next round to wait out, so starting one would publish a countdown that ends in
 * a surface that is still shut. Both counters are read AFTER the failure has been
 * added to them, so [surfaceFailures] of [PairingManager.MAX_SURFACE_FAILURES] is
 * the attempt that seals.
 */
internal fun failureCharge(surfaceFailures: Int, roundFailures: Int): FailureCharge = when {
    surfaceFailures >= PairingManager.MAX_SURFACE_FAILURES -> FailureCharge.SEAL
    roundFailures >= PairingManager.MAX_FAILURES -> FailureCharge.LOCKOUT
    else -> FailureCharge.RECORDED
}

/** [pairedAtMs] is null for a v2 record: the date is unknown, never invented. */
data class PairingRecord(
    val keyId: String,
    val key: String,
    val label: String,
    val pairedAtMs: Long? = null,
) {
    companion object {
        fun decode(value: String): PairingRecord? = decodePairingRecord(value)
    }
}

/**
 * The version sentinel that opens a v3 record — and the reason there is one.
 *
 * v2 is `keyId|key|label`, with the label LAST precisely so it may contain `|`,
 * which [normalizeLabel] does not strip. Adding a date field ahead of the label
 * makes the two shapes ambiguous by field count alone: the perfectly legal v2
 * label `12345|home` splits into four parts whose third parses as a number, and a
 * migration that trusted the count would silently read a phone called "home"
 * paired in 1970 — a wrong date shown to the user and half a label lost. A keyId
 * is always 22 base64url characters, so a leading `v3` is a token no v2 record
 * can produce, and the two are told apart with certainty rather than by guess.
 */
private const val RECORD_V3 = "v3"

/**
 * [pairedAtMs] is null for a record whose date this TV genuinely does not have —
 * one migrated from v2 — and an empty field is how v3 says so. The decoder below
 * already reads an unparseable date back as null ("a corrupt date costs the date,
 * never the credential"), so the two round-trip. A rename has to be able to write
 * one: it may change the label and nothing else, and stamping "now" on a phone
 * paired at an unknown time would put a fact in the store that never happened.
 */
internal fun encodePairingRecord(keyId: String, key: String, pairedAtMs: Long?, label: String): String =
    "$RECORD_V3|$keyId|$key|${pairedAtMs ?: ""}|$label"

/**
 * Reads either shape. A v2 record migrates in place with an unknown date rather
 * than being dropped: it is a live credential, and the user must not have to
 * re-pair a phone because this TV started recording dates.
 */
internal fun decodePairingRecord(value: String): PairingRecord? {
    if (value.startsWith("$RECORD_V3|")) {
        val parts = value.split('|', limit = 5)
        // A corrupt date costs the date, never the credential.
        return parts.takeIf { it.size == 5 }
            ?.let { PairingRecord(it[1], it[2], it[4], it[3].toLongOrNull()) }
    }
    val parts = value.split('|', limit = 3)
    return parts.takeIf { it.size == 3 }?.let { PairingRecord(it[0], it[1], it[2], null) }
}

/**
 * The stored set with [keyId]'s record removed, or null when no record carries it
 * — a forget that removed nothing must say so rather than report success for a
 * phone this TV still admits.
 *
 * A record that cannot be decoded is left in place. It authorizes nothing (it can
 * never satisfy [PairingManager.findKey] either), and dropping it here would make
 * forgetting one phone quietly rewrite the whole store.
 */
internal fun recordsWithout(records: Collection<String>, keyId: String): Set<String>? {
    val doomed = records.firstOrNull { decodePairingRecord(it)?.keyId == keyId } ?: return null
    return records.filterNotTo(LinkedHashSet<String>()) { it == doomed }
}

/**
 * The stored set with [keyId]'s record re-encoded under [label], or null when no
 * record carries it — a rename that renamed nothing must say so rather than
 * report success for a phone this TV has no record of.
 *
 * Key id, key and pairing date are carried across verbatim. That is the whole
 * point: re-minting any of them would silently unpair the phone the user meant to
 * relabel, and inventing a date for an undated record would put a fact in the
 * store that never happened — [encodePairingRecord] takes a null date for exactly
 * this call.
 *
 * A record that cannot be decoded is left byte-identical, for the reason
 * [recordsWithout] leaves it: it authorizes nothing, and rewriting it here would
 * make renaming one phone quietly rewrite the whole store.
 */
internal fun recordsRenamed(records: Collection<String>, keyId: String, label: String): Set<String>? {
    var renamed = false
    val next = records.mapTo(LinkedHashSet<String>()) { raw ->
        val record = decodePairingRecord(raw)
        if (renamed || record == null || record.keyId != keyId) return@mapTo raw
        renamed = true
        encodePairingRecord(record.keyId, record.key, record.pairedAtMs, label)
    }
    return if (renamed) next else null
}

/** Null in both fields means the TV names no phone at all. */
internal data class LastDevice(val label: String?, val keyId: String?)

/**
 * What `last_device` must hold once [renamedKeyId] has taken [newLabel], or null
 * when it names some other phone and may be left exactly as it is.
 *
 * The Idle screen renders that value as "Paired with …", so a rename that stopped
 * at the record would leave standby naming a phone by a name the Settings list no
 * longer shows. A store written before v3 recorded no last-paired key id, and the
 * old label is then the only signal it left — the same fallback, and the same cost
 * when two phones share one name, as [lastDeviceAfterForget].
 */
internal fun lastDeviceAfterRename(
    renamedKeyId: String,
    oldLabel: String,
    newLabel: String,
    storedKeyId: String?,
    storedLabel: String?,
): LastDevice? {
    val names = if (storedKeyId != null) storedKeyId == renamedKeyId else storedLabel == oldLabel
    return if (names) LastDevice(newLabel, renamedKeyId) else null
}

/**
 * Whether [value] is a code this TV could actually have issued: exactly four
 * ASCII digits, which is the shape `PairingManager` mints and the only shape
 * `attemptPair` can ever match.
 *
 * The QR payload is built from it, so this is also the guard that keeps a
 * placeholder out of the symbol — the pair screen renders "—" while the surface
 * is locked or standing by, and encoding that would draw a code that cannot pair.
 */
internal fun isPairingCode(value: String): Boolean =
    value.length == 4 && value.all { it in '0'..'9' }

/**
 * The v4 QR payload, or null when there is nothing real to encode.
 *
 * `flick://pair?v=4&h=<host>&p=<port>&c=<4-digit-code>` — the grammar the sender
 * parses, in that fixed order. It lives out here as a pure function for the same
 * reason [recordsWithout] and [lastDeviceAfterForget] do: `PairingManager` needs a
 * `Context` and real `SharedPreferences`, and the shape of the one string a phone
 * camera has to read is worth testing without either.
 *
 * All three rejections are the same rule — **never draw a symbol that cannot
 * pair**. A blank host or an out-of-range port is a TV with no binding to hand
 * out; a code that is not four digits is the pair screen's "—" placeholder, which
 * would encode a scan that fails at `attemptPair`. Every one of them draws no QR
 * at all and leaves the manual-entry card as the whole offer.
 */
internal fun pairingQrPayload(host: String, port: Int, code: String): String? {
    if (host.isBlank() || port !in 1..65535) return null
    if (!isPairingCode(code)) return null
    return "flick://pair?v=${PairingManager.QR_VERSION}&h=$host&p=$port&c=$code"
}

/**
 * What `last_device` must hold once [forgottenKeyId] is gone, or null when it
 * still names a phone this TV admits and may be left exactly as it is.
 *
 * The Idle screen renders that value as "Paired with …", so it may never name a
 * phone that has just been removed. A store written before v3 recorded no
 * last-paired key id, and the bare label is then the only signal it left: that
 * costs a name when two phones share one, and shows "Ready" rather than a wrong
 * name, which is the right direction to be wrong in.
 *
 * The replacement is the newest phone still paired. Undated records sort last, so
 * an all-v2 remainder promotes a label alphabetically rather than by recency — it
 * is still a phone that IS paired, which is the honest half of the claim.
 */
internal fun lastDeviceAfterForget(
    remaining: Collection<String>,
    forgottenKeyId: String,
    forgottenLabel: String?,
    storedKeyId: String?,
    storedLabel: String?,
): LastDevice? {
    val stale = if (storedKeyId != null) {
        storedKeyId == forgottenKeyId
    } else {
        storedLabel != null && storedLabel == forgottenLabel
    }
    if (!stale) return null
    val next = pairedPhonesOf(remaining).firstOrNull() ?: return LastDevice(null, null)
    return LastDevice(next.label, next.keyId)
}

/**
 * Every decodable record as the UI sees it, in a TOTAL order. A `StringSet` has
 * no order of its own, so without one the Settings list would reshuffle between
 * process restarts for no reason the viewer could see. Dated records come first,
 * newest first; records migrated from v2 have no date at all and follow, by
 * label — with the key id as the final tiebreak so two identically named,
 * identically dated phones still land in the same order every read.
 */
internal fun pairedPhonesOf(records: Collection<String>): List<PairedPhone> =
    records.mapNotNull(::decodePairingRecord)
        .map { PairedPhone(it.keyId, it.label, it.pairedAtMs) }
        .sortedWith(
            compareBy<PairedPhone> { it.pairedAtMs == null }
                .thenByDescending { it.pairedAtMs ?: 0L }
                .thenBy { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.keyId },
        )

fun normalizeLabel(value: String, max: Int): String {
    val normalized = StringBuilder()
    var whitespace = false
    value.codePoints().forEach { codePoint ->
        if (Character.getType(codePoint) == Character.FORMAT.toInt()) return@forEach
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            if (normalized.isNotEmpty()) whitespace = true
        } else if (Character.isISOControl(codePoint)) {
            return@forEach
        } else {
            if (whitespace) normalized.append(' ')
            whitespace = false
            normalized.appendCodePoint(codePoint)
        }
    }
    val result = normalized.toString()
    if (result.codePointCount(0, result.length) <= max) return result
    return result.substring(0, result.offsetByCodePoints(0, max))
}

/** Keeps authorization state unchanged when SharedPreferences rejects a write. */
internal fun commitPairing(commit: () -> Boolean, afterCommit: () -> Unit): Boolean {
    if (!commit()) return false
    afterCommit()
    return true
}

internal fun commitForgetPairings(commit: () -> Boolean, afterCommit: () -> Unit): Boolean {
    if (!commit()) return false
    afterCommit()
    return true
}
