package com.flick.receiver.net

import android.content.Context

/**
 * 47654 is in the IANA dynamic/private range (49152 is the strict floor, but the
 * 47xxx block is unassigned and in common private use) and is deliberately clear
 * of Cast's 8008/8009/8010 and of the sender's media port 8080, so a TV running
 * Google Cast alongside Flick never collides and the number a user reads off the
 * pairing screen is never one of those.
 */
const val DEFAULT_CONTROL_PORT = 47654

/** Highest port the fixed ladder will try before falling back to ephemeral. */
private const val LADDER_LAST_PORT = 47663

/**
 * Durable control-port selection. An ephemeral port (`0`) made the number on the
 * pairing screen stale before the user finished typing it and invalidated every
 * persisted `port_<host>` on the phone, so the receiver now prefers a stable port
 * and only falls back when the LAN forces it.
 */
class ControlPortStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The port of the last SUCCESSFUL bind, or null when there is none. */
    fun lastPort(): Int? = prefs.getInt(KEY_PORT, 0).takeIf { it in 1..65535 }

    /** Persist the port that actually bound — never the one that was requested. */
    fun remember(port: Int) {
        if (port in 1..65535) prefs.edit().putInt(KEY_PORT, port).apply()
    }

    fun candidates(): List<Int> = controlPortCandidates(lastPort())

    private companion object {
        const val PREFS = "flick_control"
        const val KEY_PORT = "last_control_port"
    }
}

/**
 * The selection ladder: last successful bind, then the fixed default, then a
 * small fixed range, then `0`. The ephemeral tail exists only so a hostile or
 * unlucky collision on every fixed port can never make the TV undiscoverable.
 */
internal fun controlPortCandidates(persisted: Int?): List<Int> {
    val out = ArrayList<Int>(12)
    if (persisted != null && persisted in 1..65535) out.add(persisted)
    if (DEFAULT_CONTROL_PORT !in out) out.add(DEFAULT_CONTROL_PORT)
    for (port in DEFAULT_CONTROL_PORT + 1..LADDER_LAST_PORT) if (port !in out) out.add(port)
    out.add(0)
    return out
}

/** Which tier of [controlPortCandidates] won, for the bind diagnostics line. */
internal fun controlPortTier(port: Int, persisted: Int?): String = when {
    persisted != null && port == persisted -> "persisted"
    port == DEFAULT_CONTROL_PORT -> "default"
    port in DEFAULT_CONTROL_PORT + 1..LADDER_LAST_PORT -> "ladder"
    else -> "ephemeral"
}
