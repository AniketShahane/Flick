package com.flick.sender.net

import com.flick.sender.model.DiscoveredTv

/**
 * Whether an advertisement is worth re-running the authenticated resume for, so a TV
 * renamed on the TV stops being shown under the name this phone last proved.
 *
 * mDNS carries no proof: any host on the LAN can advertise this pairing's `id` under any
 * name it likes. An advertised name may therefore only ever TRIGGER a re-handshake. It is
 * never returned from here, so nothing downstream can show or persist it — the one name
 * that may be believed is the one the receiver signed into the resume transcript (see
 * [ControlProtocolV2.transcript]), which the resume path already commits.
 *
 * The state below is what keeps a trigger from becoming a loop, and it is the LAST hint
 * acted on rather than every hint ever acted on. A hint has to be spent whatever the
 * resume that followed did, because success is not the only way the disagreement ends: a
 * resume can succeed and still answer a name that differs from the advertised one — the
 * platform renames a colliding service, and a rogue advertiser can claim anything — and
 * remembering only the outcome would leave that pair re-triggering per advertisement. But
 * remembering ALL of them refuses a genuine rename back to a name seen earlier, so a TV
 * that goes Old → New → Old → New would stall on the last of those for the life of the
 * process. One slot answers both: a repeat of the hint just acted on is the loop, and
 * anything else is news.
 *
 * [BUDGET] bounds what one slot cannot. An advertiser this phone cannot authenticate
 * decides how often the names it publishes change, so alternating two names is otherwise
 * a re-dial per flip.
 */
internal class TvNameRefreshGate(private val budget: Int = BUDGET) {
    private var acted = 0
    private var lastHint: Pair<String, String>? = null

    /**
     * [shownName] is the authenticated name this phone is showing now. [idle] is whether a
     * re-handshake would cost nothing this phone is in the middle of — a resume closes the
     * control session before it dials, and no cosmetic name is worth a cast.
     *
     * A hint met while not idle is deliberately left unspent: it is deferred to whenever
     * this is asked again, not dropped.
     */
    fun refreshes(
        tvId: String,
        shownName: String,
        idle: Boolean,
        discovered: List<DiscoveredTv>,
    ): Boolean {
        if (!idle || acted >= budget) return false
        // The first record that DISAGREES, rather than the first record for this id: a
        // second advertiser still holding the old name must not be able to mask a rename.
        val advertised = discovered.asSequence()
            .filter { it.tvId == tvId }
            .map { it.name }
            .firstOrNull { it != shownName }
            ?: return false
        val hint = tvId to advertised
        if (hint == lastHint) return false
        lastHint = hint
        acted += 1
        return true
    }

    private companion object {
        // Re-dials per process on a cue this phone cannot authenticate. Renaming a TV is
        // something a person does a handful of times at most in one sitting.
        const val BUDGET = 8
    }
}
